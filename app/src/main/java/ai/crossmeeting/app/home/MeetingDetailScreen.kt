package ai.crossmeeting.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Cores de speaker — ciclo para distinguir participantes na transcrição. */
private val speakerColors = listOf(
    Color(0xFF4A90E2), // azul
    Color(0xFF7B61FF), // roxo
    Color(0xFF26A69A), // verde-água
    Color(0xFFE85D04), // laranja
    Color(0xFF2E7D32), // verde
)

/** Segmento de fala: speaker (pode ser vazio) + texto. */
private data class TranscriptSegment(val speaker: String, val text: String)

/**
 * Parseia o texto bruto da transcrição em segmentos com speaker label.
 * Formato esperado do Deepgram com diarization: "Speaker 0: texto\nSpeaker 1: texto"
 * Se não houver labels, retorna um único segmento com speaker vazio.
 */
private fun parseTranscript(raw: String): List<TranscriptSegment> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.split("\n").filter { it.isNotBlank() }
    val speakerRegex = Regex("""^(Speaker\s+\d+|[\w\s]{1,30}):\s*(.+)$""")
    val segments = mutableListOf<TranscriptSegment>()
    var currentSpeaker = ""
    val currentText = StringBuilder()

    for (line in lines) {
        val match = speakerRegex.matchEntire(line.trim())
        if (match != null) {
            if (currentText.isNotEmpty()) {
                segments += TranscriptSegment(currentSpeaker, currentText.toString().trim())
                currentText.clear()
            }
            currentSpeaker = match.groupValues[1].trim()
            currentText.append(match.groupValues[2])
        } else {
            if (currentText.isNotEmpty()) currentText.append(" ")
            currentText.append(line.trim())
        }
    }
    if (currentText.isNotEmpty()) {
        segments += TranscriptSegment(currentSpeaker, currentText.toString().trim())
    }
    return if (segments.isEmpty()) listOf(TranscriptSegment("", raw)) else segments
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m} min" else "${s} s"
}

private fun formatDate(iso: String): String = runCatching {
    val inst = Instant.parse(iso)
    val ldt = inst.atZone(ZoneId.systemDefault())
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("pt", "BR"))
    ldt.format(fmt)
}.getOrDefault(iso)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailScreen(meetingId: Long, onBack: () -> Unit) {
    var meeting by remember { mutableStateOf<MeetingDetailRow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) }

    suspend fun loadMeeting() {
        runCatching {
            meeting = SupabaseClientProvider.client.postgrest.from("meetings")
                .select(Columns.list("id", "title", "created_at", "duration_seconds", "word_count", "transcript", "enhancement")) {
                    filter { eq("id", meetingId) }
                }
                .decodeSingle<MeetingDetailRow>()
        }.onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(meetingId) {
        loadMeeting()
        // Se não há enhancement mas há transcrição, a IA ainda está processando em background.
        // Recarrega a cada 15s até 6 vezes (90s máx) para exibir as notas assim que chegarem.
        if (meeting?.enhancement == null && (meeting?.wordCount ?: 0) > 0) {
            repeat(6) {
                delay(15_000)
                if (meeting?.enhancement == null) loadMeeting()
            }
        }
    }

    val enhancement = remember(meeting?.enhancement) {
        meeting?.enhancement?.let { runCatching { LenientJson.decodeFromString<Enhancement>(it) }.getOrNull() }
    }
    val segments = remember(meeting?.transcript) {
        meeting?.transcript?.let { parseTranscript(it) } ?: emptyList()
    }
    val speakerColorMap = remember(segments) {
        segments.map { it.speaker }.filter { it.isNotEmpty() }.distinct()
            .mapIndexed { i, name -> name to speakerColors[i % speakerColors.size] }
            .toMap()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
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
                meeting != null -> Column(modifier = Modifier.fillMaxSize()) {
                    // Cabeçalho: título + metadata
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            meeting!!.title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MetaChip(formatDate(meeting!!.createdAt))
                            MetaChip(formatDuration(meeting!!.durationSeconds))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Tabs
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = CmWave,
                        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Text(
                                    "Transcrição",
                                    color = if (selectedTab == 0) CmWave else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Text(
                                    "Notas de IA",
                                    color = if (selectedTab == 1) CmWave else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }

                    when (selectedTab) {
                        0 -> TranscriptTab(segments, speakerColorMap)
                        1 -> NotesTab(enhancement)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TranscriptTab(
    segments: List<TranscriptSegment>,
    speakerColorMap: Map<String, Color>,
) {
    if (segments.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Sem transcrição.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(segments) { _, seg ->
            TranscriptBlock(seg, speakerColorMap)
        }
    }
}

@Composable
private fun TranscriptBlock(seg: TranscriptSegment, speakerColorMap: Map<String, Color>) {
    val speakerColor = speakerColorMap[seg.speaker] ?: CmWave
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (seg.speaker.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(speakerColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        seg.speaker.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    seg.speaker,
                    color = speakerColor,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        Text(
            seg.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun NotesTab(enhancement: Enhancement?) {
    if (enhancement == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Notas de IA não disponíveis",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "As notas chegam em até 30 segundos após a gravação.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (enhancement.summary != null) {
            item {
                NoteSection("Resumo") {
                    Text(enhancement.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (enhancement.keyPoints.isNotEmpty()) {
            item {
                NoteSection("Pontos principais") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        enhancement.keyPoints.forEach { pt ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier.size(6.dp).clip(CircleShape).background(CmWave)
                                        .align(Alignment.CenterVertically),
                                )
                                Text(pt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        if (enhancement.actionItems.isNotEmpty()) {
            item {
                NoteSection("Ações") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        enhancement.actionItems.forEach { ai: ActionItem ->
                            ActionItemRow(ai)
                        }
                    }
                }
            }
        }
        if (enhancement.decisions.isNotEmpty()) {
            item {
                NoteSection("Decisões") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        enhancement.decisions.forEach { d: Decision ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF26A69A))
                                        .align(Alignment.CenterVertically),
                                )
                                Text(d.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionItemRow(ai: ActionItem) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(Color(0xFFE85D04)).align(Alignment.CenterVertically),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ai.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (ai.owner != null) {
                    Text(
                        ai.owner,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = CmWave)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
