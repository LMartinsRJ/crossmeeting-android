package ai.crossmeeting.app.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.crossmeeting.app.MeetingDetailRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.recording.ActionItem
import ai.crossmeeting.app.recording.Decision
import ai.crossmeeting.app.recording.Enhancement
import ai.crossmeeting.app.recording.LenientJson
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.launch

/** Tela de detalhe de uma reunião: resumo/pontos/ações (se já tiver enhancement) + transcrição completa. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailScreen(meetingId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var meeting by remember { mutableStateOf<MeetingDetailRow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(meetingId) {
        scope.launch {
            runCatching {
                meeting = SupabaseClientProvider.client.postgrest.from("meetings")
                    .select(Columns.list("id", "title", "created_at", "duration_seconds", "word_count", "transcript", "enhancement")) {
                        filter { eq("id", meetingId) }
                    }
                    .decodeSingle<MeetingDetailRow>()
            }.onFailure { error = it.message }
            loading = false
        }
    }

    val enhancement = remember(meeting?.enhancement) {
        meeting?.enhancement?.let { runCatching { LenientJson.decodeFromString<Enhancement>(it) }.getOrNull() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(meeting?.title ?: "Reunião", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = CmWave)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CmWave)
                error != null -> Text(
                    "Erro: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                meeting != null -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (enhancement?.summary != null) {
                        item { SectionCard("Resumo") { Text(enhancement.summary, color = MaterialTheme.colorScheme.onSurface) } }
                    }
                    if (enhancement?.keyPoints?.isNotEmpty() == true) {
                        item {
                            SectionCard("Pontos principais") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    enhancement.keyPoints.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurface) }
                                }
                            }
                        }
                    }
                    if (enhancement?.actionItems?.isNotEmpty() == true) {
                        item {
                            SectionCard("Action items") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    enhancement.actionItems.forEach { item: ActionItem ->
                                        Text(
                                            "• ${item.text}" + (item.owner?.let { " ($it)" } ?: ""),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (enhancement?.decisions?.isNotEmpty() == true) {
                        item {
                            SectionCard("Decisões") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    enhancement.decisions.forEach { d: Decision -> Text("• ${d.text}", color = MaterialTheme.colorScheme.onSurface) }
                                }
                            }
                        }
                    }
                    item {
                        SectionCard("Transcrição completa") {
                            Text(
                                meeting?.transcript?.ifBlank { "Sem transcrição." } ?: "Sem transcrição.",
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = CmWave)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
