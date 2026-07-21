package ai.crossmeeting.app.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import ai.crossmeeting.app.MainActivity
import ai.crossmeeting.app.SupabaseClientProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import io.github.jan.supabase.functions.functions
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "ai.crossmeeting.app.recording.START"
        const val ACTION_STOP  = "ai.crossmeeting.app.recording.STOP"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA        = "projection_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "recording"
        private const val SAMPLE_RATE     = 16000
        private const val BUFFER_SIZE     = 4096
        private const val TAG             = "CMRecording"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerJob: Job? = null
    private var audioJob: Job? = null
    private var micJob: Job? = null
    private var pbJob: Job? = null

    private var micRecord: AudioRecord? = null
    private var pbRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null

    private var wsClient: HttpClient? = null
    private var wsSession: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Canal para misturar os dois streams de áudio antes de enviar ao Deepgram
    private val mixChannel = Channel<ByteArray>(capacity = 16)

    private var projectionResultCode: Int = Activity.RESULT_CANCELED
    private var projectionData: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
        } else {
            projectionResultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            projectionData = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
            startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (RecordingState.state.value.isRecording) return
        RecordingState.update { it.copy(isRecording = true, error = null) }

        // Tenta criar o MediaProjection para captura de playback (API 29+, resultado OK)
        val hasPlayback = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            projectionResultCode == Activity.RESULT_OK &&
            projectionData != null
        ) {
            try {
                val mgr = getSystemService<MediaProjectionManager>()
                val mp = mgr?.getMediaProjection(projectionResultCode, projectionData!!)
                mediaProjection = mp
                mp != null
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjection não disponível: ${e.message}")
                false
            }
        } else false

        Log.d(TAG, "hasPlayback=$hasPlayback")

        startForegroundNotification(hasPlayback)
        acquireWakeLock()

        audioJob = serviceScope.launch {
            try {
                val tokenResponse = SupabaseClientProvider.client.functions.invoke("deepgram-token")
                val tokenBody = LenientJson.decodeFromString<DeepgramTokenResponse>(tokenResponse.bodyAsText())
                val token = tokenBody.token ?: error(tokenBody.error ?: "Token do Deepgram não recebido")

                val client = HttpClient(OkHttp) {
                    install(WebSockets) { pingInterval = 20_000 }
                    engine { config { readTimeout(0, TimeUnit.MILLISECONDS) } }
                }
                wsClient = client

                val session = client.webSocketSession(
                    method = HttpMethod.Get,
                    host = "api.deepgram.com",
                    path = "/v1/listen",
                ) {
                    url.protocol = URLProtocol.WSS
                    url.port = URLProtocol.WSS.defaultPort
                    url.parameters.append("model", "nova-3")
                    url.parameters.append("language", "pt-BR")
                    url.parameters.append("smart_format", "true")
                    url.parameters.append("interim_results", "true")
                    url.parameters.append("punctuate", "true")
                    url.parameters.append("encoding", "linear16")
                    url.parameters.append("sample_rate", SAMPLE_RATE.toString())
                    url.parameters.append("channels", "1")
                    header("Authorization", "Bearer $token")
                }
                wsSession = session
                Log.d(TAG, "WebSocket conectado, hasPlayback=$hasPlayback")

                startTimer()

                if (hasPlayback) {
                    try {
                        startMixedCapture(session)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioPlaybackCapture falhou, usando mic-only: ${e.message}")
                        micRecord?.let { runCatching { it.stop(); it.release() } }
                        micRecord = null
                        micJob = serviceScope.launch { startMicOnlyCapture(session) }
                    }
                } else {
                    micJob = serviceScope.launch { startMicOnlyCapture(session) }
                }

                for (frame in session.incoming) {
                    if (frame is Frame.Text) handleDeepgramMessage(frame.readText())
                }
                Log.d(TAG, "incoming loop encerrado")

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "erro no fluxo de gravação", e)
                RecordingState.update { it.copy(error = e.message, isRecording = false) }
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    // ── Captura mista: mic + playback de outros apps ──────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startMixedCapture(session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSz  = maxOf(minBuf, SAMPLE_RATE)

        // AudioRecord para microfone
        val mic = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSz)
        micRecord = mic

        // AudioRecord para playback de outros apps
        val pbConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val pb = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(pbConfig)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(bufSz)
            .build()
        pbRecord = pb

        mic.startRecording()
        pb.startRecording()
        Log.d(TAG, "AudioRecord mic+playback iniciados")

        val micBuf = ByteArray(BUFFER_SIZE)
        val pbBuf  = ByteArray(BUFFER_SIZE)

        micJob = serviceScope.launch {
            var frames = 0
            while (RecordingState.state.value.isRecording) {
                val micRead = mic.read(micBuf, 0, BUFFER_SIZE)
                val pbRead  = pb.read(pbBuf, 0, BUFFER_SIZE)
                val len = minOf(micRead, pbRead).coerceAtLeast(0)
                if (len > 0) {
                    val mixed = mixPcm16(micBuf, pbBuf, len)
                    session.send(Frame.Binary(true, mixed))
                    AudioLevelState.set(pcm16Amplitude(mixed, len))
                    if (++frames % 20 == 0) Log.d(TAG, "mixed frames=$frames")
                }
            }
            AudioLevelState.set(0f)
        }
    }

    // ── Captura só microfone (fallback) ───────────────────────────────────────

    private suspend fun startMicOnlyCapture(session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSz  = maxOf(minBuf, SAMPLE_RATE)
        val mic = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSz)
        micRecord = mic
        mic.startRecording()

        val buffer = ByteArray(BUFFER_SIZE)
        var frames = 0
        while (RecordingState.state.value.isRecording) {
            val read = mic.read(buffer, 0, buffer.size)
            if (read > 0) {
                session.send(Frame.Binary(true, buffer.copyOf(read)))
                AudioLevelState.set(pcm16Amplitude(buffer, read))
                if (++frames % 20 == 0) Log.d(TAG, "mic-only frames=$frames")
            }
        }
        AudioLevelState.set(0f)
    }

    // ── Mixing PCM 16-bit ─────────────────────────────────────────────────────

    private fun mixPcm16(a: ByteArray, b: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var i = 0
        while (i + 1 < length) {
            val sa = (a[i].toInt() and 0xFF) or (a[i + 1].toInt() shl 8)
            val sb = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
            val mixed = ((sa + sb).coerceIn(-32768, 32767)).toShort()
            out[i]     = (mixed.toInt() and 0xFF).toByte()
            out[i + 1] = (mixed.toInt() ushr 8).toByte()
            i += 2
        }
        return out
    }

    private fun pcm16Amplitude(buffer: ByteArray, length: Int): Float {
        var sum = 0.0; var n = 0; var i = 0
        while (i + 1 < length) {
            val s = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += s.toDouble() * s; n++; i += 2
        }
        if (n == 0) return 0f
        return (kotlin.math.sqrt(sum / n) / 6000.0).coerceIn(0.0, 1.0).toFloat()
    }

    // ── Deepgram messages ─────────────────────────────────────────────────────

    private fun handleDeepgramMessage(text: String) {
        runCatching { LenientJson.decodeFromString<DeepgramMessage>(text) }
            .onFailure { if (BuildConfig.DEBUG) Log.e(TAG, "decode Deepgram: $text", it) else Log.e(TAG, "decode Deepgram failed", it) }
            .onSuccess { msg ->
                val transcript = msg.channel?.alternatives?.firstOrNull()?.transcript.orEmpty()
                if (transcript.isBlank()) return
                RecordingState.update { cur ->
                    if (msg.isFinal) cur.copy(finalTranscript = (cur.finalTranscript + " " + transcript).trim(), interimText = "")
                    else cur.copy(interimText = transcript)
                }
            }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        timerJob = serviceScope.launch {
            while (RecordingState.state.value.isRecording) {
                kotlinx.coroutines.delay(1000)
                RecordingState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
            }
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopRecording() {
        RecordingState.update { it.copy(isRecording = false) }

        micJob?.cancel(); pbJob?.cancel()
        micRecord?.let { runCatching { it.stop() }; it.release() }; micRecord = null
        pbRecord?.let  { runCatching { it.stop() }; it.release() }; pbRecord = null
        mediaProjection?.stop(); mediaProjection = null
        timerJob?.cancel()

        serviceScope.launch {
            val closeResult = runCatching { wsSession?.send(Frame.Text("""{"type":"CloseStream"}""")) }
            Log.d(TAG, "CloseStream: ${closeResult.isSuccess}")
            kotlinx.coroutines.delay(1200)
            audioJob?.cancel()
            runCatching { wsClient?.close() }
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            RecordingState.update { it.copy(stoppedAndFlushed = true) }
            releaseWakeLock()
            stopSelf()
        }
    }

    // ── Wake lock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Crossmeeting:recording")
            .apply { acquire(3 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startForegroundNotification(hasPlayback: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gravação", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gravação em andamento")
            .setContentText(
                if (hasPlayback) "Capturando microfone + áudio da reunião"
                else "Crossmeeting está transcrevendo sua reunião"
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()

        val type = if (hasPlayback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }
}
