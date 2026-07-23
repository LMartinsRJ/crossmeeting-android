package ai.crossmeeting.app.recording

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ai.crossmeeting.app.MeetingIdRow
import ai.crossmeeting.app.NewMeeting
import ai.crossmeeting.app.ProfileRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmBlue
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Tela de gravação, no estilo do Granola: sem transcrição ao vivo na tela — só título,
 * onda sonora e timer enquanto grava. Ao parar, mostra uma tela de "gerando notas" enquanto
 * chama `enhance-transcript` e grava a reunião (Postgrest); ao terminar, abre o detalhe da
 * reunião já com o resumo gerado. O [RecordingService] já está rodando quando esta tela
 * aparece (iniciado pelo botão na HomeScreen); aqui só observamos [RecordingState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(onSaved: (Long) -> Unit, onDiscarded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by RecordingState.state.collectAsState()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }

    fun stopServiceAndDiscard() {
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )
        RecordingState.reset()
        onDiscarded()
    }

    fun stopAndSave() {
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )
        saving = true
        saveError = null
        scope.launch {
            val result = runCatching {
                // Espera o RecordingService terminar o desligamento gracioso (CloseStream +
                // tempo pro Deepgram mandar o resultado final) antes de ler a transcrição —
                // sem isso, fala dita perto do fim da gravação nunca chega a ser salva.
                withTimeoutOrNull(4000) {
                    RecordingState.state.first { it.stoppedAndFlushed }
                }
                val snapshot = RecordingState.state.value
                // O Deepgram só marca um trecho como "final" depois de uma pausa na fala —
                // se o usuário parar no meio de uma frase, o que sobrou fica só em interimText.
                val transcript = (snapshot.finalTranscript + " " + snapshot.interimText).trim()
                val wordCount = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
                val userId = SupabaseClientProvider.client.postgrest.from("profiles")
                    .select().decodeSingle<ProfileRow>().id
                val fallbackTitle = title.trim().ifBlank {
                    "Reunião " + java.text.SimpleDateFormat("dd/MM HH:mm").format(java.util.Date())
                }

                // Salva a reunião imediatamente com título provisório, sem esperar a IA.
                // O enhance-transcript roda em background depois da navegação para garantir
                // que a reunião seja sempre gravada mesmo se a IA demorar ou falhar.
                val meetingId = SupabaseClientProvider.client.postgrest.from("meetings").insert(
                    NewMeeting(
                        title = fallbackTitle,
                        userId = userId,
                        createdAt = java.time.Instant.now().toString(),
                        durationSeconds = snapshot.elapsedSeconds,
                        wordCount = wordCount,
                        transcript = transcript,
                        enhancement = null,
                    ),
                ) {
                    select(Columns.list("id"))
                }.decodeSingle<MeetingIdRow>().id

                // Dispara o enhance em background — fora do scope da composição para não ser
                // cancelado quando o RecordingScreen for removido da pilha de navegação.
                // A edge function recebe o meetingId e ela mesma faz o UPDATE via adminDb
                // (SERVICE_ROLE_KEY, sem RLS), eliminando qualquer complexidade de sessão
                // ou timeout no cliente Android.
                if (transcript.isNotBlank()) {
                    @Suppress("OPT_IN_USAGE")
                    GlobalScope.launch {
                        runCatching {
                            val response = SupabaseClientProvider.client.functions.invoke("enhance-transcript") {
                                contentType(ContentType.Application.Json)
                                setBody(EnhanceTranscriptRequest(transcript = transcript, meetingId = meetingId))
                            }
                            val bodyText = response.bodyAsText()
                            android.util.Log.d("CMSave", "enhance ok para meetingId=$meetingId body=${bodyText.take(200)}")
                        }.onFailure { ex ->
                            android.util.Log.e("CMSave", "enhance falhou para meetingId=$meetingId: ${ex::class.simpleName} ${ex.message}", ex)
                        }
                    }
                }

                meetingId
            }
            saving = false
            result.onSuccess { meetingId ->
                RecordingState.reset()
                onSaved(meetingId)
            }.onFailure { saveError = it.message }
        }
    }

    if (saving) {
        GeneratingNotesScreen()
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    val minutes = state.elapsedSeconds / 60
                    val secs = state.elapsedSeconds % 60
                    Text(
                        "%02d:%02d".format(minutes, secs),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = { stopServiceAndDiscard() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancelar e voltar", tint = CmWave)
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    FloatingActionButton(
                        onClick = { stopAndSave() },
                        containerColor = CmBlue,
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Parar e salvar")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
        ) {
            (state.error ?: saveError)?.let {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("Erro: $it", color = MaterialTheme.colorScheme.error)
                    if (saveError != null) {
                        TextButton(onClick = { stopServiceAndDiscard() }) {
                            Text("Descartar e voltar")
                        }
                    }
                }
            }

            BasicTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                textStyle = TextStyle(
                    fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CmWave),
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            "Adicionar título...",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val amplitude by AudioLevelState.amplitude.collectAsState()
                    VoiceWaveform(
                        amplitude = amplitude,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Gravando...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratingNotesScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = CmWave)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Gerando suas notas...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Avisamos quando estiver pronto",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Onda sonora baseada na amplitude real do microfone ([RecordingState.amplitude], calculada
 * em [RecordingService.pcm16Amplitude]) — só se move quando o áudio capturado tem volume de
 * verdade; fica praticamente parada (barras baixas) quando o microfone está em silêncio,
 * o que também ajuda a perceber se a captura de áudio está funcionando.
 */
@Composable
private fun VoiceWaveform(amplitude: Float, modifier: Modifier = Modifier) {
    val barCount = 28
    val seeds = remember { List(barCount) { Random.nextFloat() } }
    val smoothed by animateFloatAsState(targetValue = amplitude, label = "amplitude")

    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 1.6f)
        val gap = barWidth * 0.6f
        for (i in 0 until barCount) {
            // pequena variação por barra pra parecer onda, mas tudo escalado pela amplitude real
            val variation = 0.5f + 0.5f * seeds[i]
            val level = (0.06f + smoothed * variation).coerceIn(0.06f, 1f)
            val barHeight = size.height * level
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = CmWave,
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
