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
import ai.crossmeeting.app.BuildConfig
import ai.crossmeeting.app.MainActivity
import ai.crossmeeting.app.SupabaseClientProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
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
        /** 2h10 — mesmo teto do desktop. */
        private const val DEFAULT_MAX_MEETING_SECONDS = 130 * 60

        /**
         * Encerramento por silêncio, igual ao desktop. O gatilho é palavra
         * transcrita, não nível de áudio: ventilador, ar-condicionado ou
         * digitação manteriam a sessão viva para sempre se o critério fosse som.
         */
        private const val SILENCE_WARN_MS = 13 * 60 * 1000L
        private const val SILENCE_END_MS  = 15 * 60 * 1000L

        /** Antecedência do aviso de duração, em segundos. */
        private const val DURATION_WARN_LEAD_SECONDS = 5 * 60

        const val ACTION_START = "ai.crossmeeting.app.recording.START"
        const val ACTION_STOP  = "ai.crossmeeting.app.recording.STOP"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA        = "projection_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "recording_v2"
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
    @Volatile private var wsSession: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Reconexão automática ─────────────────────────────────────────────────
    //
    // Trocar de wifi para 4G derruba o WebSocket. Antes, a falha de envio caía
    // no catch genérico do audioJob, que fazia isRecording = false e stopSelf():
    // a gravação morria na troca de rede e tudo dito depois era perdido.
    //
    // A reunião não é dividida por isso: a transcrição acumula em RecordingState,
    // não no socket. Reabrir a conexão é invisível para quem grava.

    /** 16 kHz × 16 bits mono = 32 KB/s. */
    private val bytesPerSecond = SAMPLE_RATE * 2
    /** Guarda até 30 s de áudio enquanto reconecta (~960 KB). */
    private val maxBufferBytes = 30 * bytesPerSecond

    @Volatile private var socketConnected = false
    private val pendingAudio = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private val bufferLock = Any()
    private var reconnectAttempt = 0

    // Canal para misturar os dois streams de áudio antes de enviar ao Deepgram
    private val mixChannel = Channel<ByteArray>(capacity = 16)

    // Teto de duração de uma reunião, em segundos. Vem do plano
    // (`max_meeting_minutes` em plan_features, exposto por my_usage()); o padrão
    // de 2h10 cobre a janela até a resposta chegar e o caso de a consulta
    // falhar — sem ele, uma falha de rede removeria o teto.
    //
    // Aplicado aqui, no cliente, e não na edge function: o Deepgram só verifica
    // o token no handshake, então o servidor não corta uma conexão já aberta.
    private var maxMeetingSeconds: Int = DEFAULT_MAX_MEETING_SECONDS

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

        if (BuildConfig.DEBUG) Log.d(TAG, "hasPlayback=$hasPlayback")

        startForegroundNotification(hasPlayback)
        acquireWakeLock()

        audioJob = serviceScope.launch {
            try {
                val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                    .build()
                val client = HttpClient(OkHttp) {
                    install(WebSockets) { pingIntervalMillis = 20_000 }
                    engine {
                        config {
                            readTimeout(0, TimeUnit.MILLISECONDS)
                            connectionSpecs(listOf(tlsSpec))
                        }
                    }
                }
                wsClient = client

                // Primeira conexão precisa dar certo — sem ela não há o que gravar.
                val first = openSession(client)
                    ?: error("Não foi possível conectar ao serviço de transcrição.")

                wsSession = first
                socketConnected = true
                if (BuildConfig.DEBUG) Log.d(TAG, "WebSocket conectado, hasPlayback=$hasPlayback")

                startTimer()
                startCapture(hasPlayback)

                // A captura roda independente da conexão daqui em diante: este
                // laço só cuida do transporte, reabrindo quando cair.
                var session = first
                while (RecordingState.state.value.isRecording) {
                    try {
                        for (frame in session.incoming) {
                            if (frame is Frame.Text) handleDeepgramMessage(frame.readText())
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "conexão caiu: ${e.message}")
                    }

                    socketConnected = false
                    wsSession = null
                    if (!RecordingState.state.value.isRecording) break

                    // Caiu durante a gravação — reconecta com backoff
                    RecordingState.update { it.copy(reconnecting = true) }
                    val delayMs = nextBackoffDelay()
                    if (BuildConfig.DEBUG) Log.d(TAG, "reconectando em ${delayMs}ms (tentativa $reconnectAttempt)")
                    kotlinx.coroutines.delay(delayMs)
                    if (!RecordingState.state.value.isRecording) break

                    val reopened = openSession(client)
                    if (reopened == null) continue   // falhou: tenta de novo com backoff maior

                    session = reopened
                    wsSession = reopened
                    socketConnected = true
                    reconnectAttempt = 0
                    RecordingState.update { it.copy(reconnecting = false) }
                    flushPendingAudio()
                    if (BuildConfig.DEBUG) Log.d(TAG, "reconectado")
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "laço de conexão encerrado")

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "erro no fluxo de gravação", e)
                RecordingState.update { it.copy(error = e.message, isRecording = false, reconnecting = false) }
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    /** Abre uma conexão com o Deepgram. Devolve null em falha, sem lançar. */
    private suspend fun openSession(
        client: HttpClient,
    ): io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = try {
        // Token novo a cada tentativa: hoje é uma API key longa, mas quando
        // migrarmos para token temporário (B8/Fase 4) isto já estará certo.
        val tokenResponse = SupabaseClientProvider.client.functions.invoke("deepgram-token")
        val tokenBody = LenientJson.decodeFromString<DeepgramTokenResponse>(tokenResponse.bodyAsText())
        val token = tokenBody.token ?: error(tokenBody.error ?: "Token do Deepgram não recebido")

        client.webSocketSession(
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
            // "Token", não "Bearer": a deepgram-token devolve uma API key,
            // e o Deepgram só aceita Bearer para tokens temporários do
            // endpoint /auth/grant. Com Bearer o handshake volta 401.
            header("Authorization", "Token $token")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "falha ao abrir sessão: ${e.message}")
        null
    }

    /** 0s, 1s, 2s, 4s, 8s, teto de 10s. */
    private fun nextBackoffDelay(): Long {
        val delay = if (reconnectAttempt == 0) 0L
                    else minOf(10_000L, 1000L * (1L shl minOf(reconnectAttempt - 1, 4)))
        reconnectAttempt = minOf(reconnectAttempt + 1, 10)
        return delay
    }

    private fun startCapture(hasPlayback: Boolean) {
        if (hasPlayback) {
            try {
                startMixedCapture()
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlaybackCapture falhou, usando mic-only: ${e.message}")
                micRecord?.let { runCatching { it.stop(); it.release() } }
                micRecord = null
                micJob = serviceScope.launch { startMicOnlyCapture() }
            }
        } else {
            micJob = serviceScope.launch { startMicOnlyCapture() }
        }
    }

    /**
     * Envia áudio pela conexão atual; se estiver fora do ar, guarda no buffer.
     * É o ponto único que desacopla a captura do transporte.
     */
    private suspend fun sendAudio(bytes: ByteArray) {
        val session = wsSession
        if (session != null && socketConnected) {
            try {
                session.send(Frame.Binary(true, bytes))
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Caiu entre a checagem e o envio — cai para o buffer
                socketConnected = false
            }
        }

        if (!RecordingState.state.value.isRecording) return

        synchronized(bufferLock) {
            pendingAudio.addLast(bytes)
            pendingBytes += bytes.size
            // Queda longa: descarta o mais antigo. Perde-se um trecho, mas a
            // gravação continua em vez de estourar a memória.
            while (pendingBytes > maxBufferBytes && pendingAudio.isNotEmpty()) {
                pendingBytes -= pendingAudio.removeFirst().size
            }
        }
    }

    /** Despeja o áudio acumulado. O Deepgram aceita mais rápido que tempo real. */
    private suspend fun flushPendingAudio() {
        val session = wsSession ?: return
        val chunks = synchronized(bufferLock) {
            val copy = pendingAudio.toList()
            pendingAudio.clear()
            pendingBytes = 0
            copy
        }
        if (chunks.isEmpty()) return
        if (BuildConfig.DEBUG) Log.d(TAG, "despejando ${chunks.size} chunks bufferizados")
        for (chunk in chunks) {
            try {
                session.send(Frame.Binary(true, chunk))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                socketConnected = false
                break
            }
        }
    }

    // ── Captura mista: mic + playback de outros apps ──────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startMixedCapture() {
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
        if (BuildConfig.DEBUG) Log.d(TAG, "AudioRecord mic+playback iniciados")

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
                    sendAudio(mixed)
                    AudioLevelState.set(pcm16Amplitude(mixed, len))
                    if (BuildConfig.DEBUG && ++frames % 20 == 0) Log.d(TAG, "mixed frames=$frames")
                }
            }
            AudioLevelState.set(0f)
        }
    }

    // ── Captura só microfone (fallback) ───────────────────────────────────────

    private suspend fun startMicOnlyCapture() {
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
                sendAudio(buffer.copyOf(read))
                AudioLevelState.set(pcm16Amplitude(buffer, read))
                if (BuildConfig.DEBUG && ++frames % 20 == 0) Log.d(TAG, "mic-only frames=$frames")
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
                // Interim também conta: significa que há fala sendo captada agora.
                RecordingState.markSpeech()
                RecordingState.update { cur ->
                    if (msg.isFinal) cur.copy(finalTranscript = (cur.finalTranscript + " " + transcript).trim(), interimText = "")
                    else cur.copy(interimText = transcript)
                }
            }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        loadMeetingDurationLimit()
        timerJob = serviceScope.launch {
            while (RecordingState.state.value.isRecording) {
                kotlinx.coroutines.delay(1000)
                RecordingState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }

                // Encerra pelo mesmo caminho do botão parar: salva e processa,
                // então nada do que foi falado se perde.
                val elapsed = RecordingState.state.value.elapsedSeconds
                if (elapsed >= maxMeetingSeconds) {
                    Log.i(TAG, "Limite de duracao atingido (${maxMeetingSeconds}s); encerrando")
                    stopRecording()
                    break
                }

                val nearDuration = elapsed >= maxMeetingSeconds - DURATION_WARN_LEAD_SECONDS
                if (nearDuration != RecordingState.state.value.durationWarning) {
                    RecordingState.update { it.copy(durationWarning = nearDuration) }
                }

                // Silêncio. Uma reunião pode ter minutos legítimos sem fala
                // (alguém lendo um documento), por isso o aviso vem antes, com
                // opção de continuar.
                val idle = System.currentTimeMillis() - RecordingState.lastSpeechAt
                if (idle >= SILENCE_END_MS) {
                    Log.i(TAG, "Sem transcricao ha ${idle / 1000}s; encerrando")
                    stopRecording()
                    break
                }
                if (idle >= SILENCE_WARN_MS && !RecordingState.state.value.silenceWarning) {
                    RecordingState.update { it.copy(silenceWarning = true) }
                }
            }
        }
    }

    /** Lê `max_meeting_minutes` do plano. Falha mantém o padrão. */
    private fun loadMeetingDurationLimit() {
        serviceScope.launch {
            runCatching {
                val raw = SupabaseClientProvider.client.postgrest
                    .rpc("my_usage")
                    .data
                val minutes = LenientJson
                    .decodeFromString<PlanUsageResponse>(raw)
                    .maxMeetingMinutes
                if (minutes != null && minutes > 0) {
                    maxMeetingSeconds = minutes * 60
                }
            }.onFailure {
                Log.w(TAG, "Nao foi possivel ler o limite de duracao do plano: ${it.message}")
            }
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopRecording() {
        // isRecording = false ANTES de tudo: é o sinal que faz o laço de conexão
        // parar de reconectar e os loops de captura encerrarem.
        RecordingState.update { it.copy(isRecording = false, reconnecting = false) }
        reconnectAttempt = 0

        micJob?.cancel(); pbJob?.cancel()
        micRecord?.let { runCatching { it.stop() }; it.release() }; micRecord = null
        pbRecord?.let  { runCatching { it.stop() }; it.release() }; pbRecord = null
        mediaProjection?.stop(); mediaProjection = null
        timerJob?.cancel()

        serviceScope.launch {
            // Última chance de enviar o que ficou bufferizado numa queda recente
            runCatching { flushPendingAudio() }
            synchronized(bufferLock) { pendingAudio.clear(); pendingBytes = 0 }

            val closeResult = runCatching { wsSession?.send(Frame.Text("""{"type":"CloseStream"}""")) }
            if (BuildConfig.DEBUG) Log.d(TAG, "CloseStream: ${closeResult.isSuccess}")
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
                NotificationChannel(CHANNEL_ID, "Gravação", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Mostra quando o Crossmeeting está gravando uma reunião"
                    setShowBadge(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val publicVersion: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Crossmeeting")
            .setContentText("Gravação em andamento")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎙 Crossmeeting gravando")
            .setContentText(
                if (hasPlayback) "Capturando microfone + áudio da reunião"
                else "Transcrevendo sua reunião ao vivo"
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val type = if (hasPlayback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }
}
