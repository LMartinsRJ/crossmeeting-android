package ai.crossmeeting.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.crossmeeting.app.MeetingRow
import ai.crossmeeting.app.SpaceRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun historyDate(iso: String): String = runCatching {
    val ldt = Instant.parse(iso).atZone(deviceZone())
    val fmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("pt", "BR"))
    ldt.format(fmt)
}.getOrDefault("")

private fun historyDuration(seconds: Int): String {
    val m = seconds / 60
    return if (m > 0) "$m min" else "${seconds}s"
}

/** Iniciais de até 2 palavras de um nome (ex: "Marina Alves" → "MA"). */
private fun initials(name: String): String =
    name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }

/** Cores de avatar — mesmas da transcrição para consistência visual. */
private val avatarColors = listOf(
    Color(0xFF4A90E2),
    Color(0xFF7B61FF),
    Color(0xFF26A69A),
    Color(0xFFE85D04),
    Color(0xFF2E7D32),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllMeetingsScreen(
    modifier: Modifier = Modifier,
    onOpenMeeting: (Long) -> Unit,
    onOpenSpace: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var meetings by remember { mutableStateOf<List<MeetingRow>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<SpaceRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val spaceById = remember(spaces) { spaces.associateBy { it.id } }

    val grouped = remember(meetings, spaceById) {
        val semPasta = meetings.filter { it.spaceId == null }
        val comPasta = meetings.filter { it.spaceId != null }
            .groupBy { it.spaceId!! }
            .entries
            .sortedBy { spaceById[it.key]?.name ?: "" }
        buildList {
            if (semPasta.isNotEmpty()) add(null to semPasta)
            for ((spaceId, list) in comPasta) add(spaceId to list)
        }
    }

    fun refresh() {
        loading = true; error = null
        scope.launch {
            runCatching {
                val pg = SupabaseClientProvider.client.postgrest
                spaces = pg.from("spaces").select().decodeList<SpaceRow>()
                meetings = pg.from("meetings").select().decodeList<MeetingRow>()
                    .filter { it.deletedAt == null }
                    .sortedByDescending { it.createdAt }
            }.onFailure { error = it.message }
            loading = false
        }
    }

    /**
     * Move para a lixeira (soft delete). Mesmo comportamento do desktop e da web:
     * marca `deleted_at` em vez de apagar, e o pg_cron limpa depois de 15 dias.
     */
    fun moveToTrash(meeting: MeetingRow) {
        // Some da lista na hora; volta se o servidor recusar
        val before = meetings
        meetings = meetings.filter { it.id != meeting.id }
        scope.launch {
            runCatching {
                SupabaseClientProvider.client.postgrest.from("meetings")
                    .update(MeetingTrashUpdate(java.time.Instant.now().toString())) {
                        filter { eq("id", meeting.id) }
                    }
            }.onFailure {
                meetings = before
                error = "Não foi possível excluir: ${it.message}"
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Histórico",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            "Reuniões capturadas e transcritas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CmWave)
            error?.let {
                Text("Erro: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((spaceId, list) in grouped) {
                    val space = spaceId?.let { spaceById[it] }
                    if (grouped.size > 1) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            ) {
                                if (space != null) {
                                    if (space.emoji.isNotBlank()) Text(space.emoji)
                                    else Icon(Icons.Filled.Folder, contentDescription = null, tint = CmWave, modifier = Modifier.size(14.dp))
                                    Text(
                                        space.name.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        "SEM PASTA",
                                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    items(list, key = { it.id }) { meeting ->
                        SwipeToDeleteMeeting(
                            onDelete = { moveToTrash(meeting) },
                        ) {
                            HistoryCard(meeting = meeting, onClick = { onOpenMeeting(meeting.id) })
                        }
                    }
                }

                if (!loading && meetings.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Nenhuma reunião ainda",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
/** Payload do soft delete — só o campo que muda, para não sobrescrever o resto. */
@kotlinx.serialization.Serializable
data class MeetingTrashUpdate(
    @kotlinx.serialization.SerialName("deleted_at") val deletedAt: String,
)

/**
 * Deslizar para a esquerda exclui a reunião.
 *
 * Um arraste curto mostra a faixa vermelha com o ícone e volta ao soltar; passar
 * de 55% da largura confirma a exclusão. É o padrão que o usuário já conhece de
 * apps de e-mail: dá para "espiar" a ação sem cometê-la, e um gesto decidido
 * resolve de uma vez.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteMeeting(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        // 55% da largura: exige intenção, mas não obriga a arrastar a tela toda
        positionalThreshold = { total -> total * 0.55f },
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,   // só da direita para a esquerda
        enableDismissFromEndToStart = true,
        backgroundContent = {
            // Só pinta o fundo quando o gesto é o de exclusão
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Excluir",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Excluir",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        },
        content = { content() },
    )
}

@Composable
private fun HistoryCard(meeting: MeetingRow, onClick: () -> Unit) {
    val hasTranscript = meeting.wordCount > 0

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Linha 1: data · duração + badge Transcrita
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append(historyDate(meeting.createdAt))
                        if (meeting.durationSeconds > 0) append(" · ${historyDuration(meeting.durationSeconds)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasTranscript) {
                    TranscritaBadge()
                }
            }

            // Linha 2: título
            Text(
                meeting.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TranscritaBadge() {
    Surface(
        color = CmWave.copy(alpha = 0.15f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = CmWave,
                modifier = Modifier.size(12.dp),
            )
            Text(
                "Transcrita",
                style = MaterialTheme.typography.labelSmall,
                color = CmWave,
            )
        }
    }
}

@Composable
private fun AttendeeChips(names: List<String>) {
    if (names.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        names.take(4).forEachIndexed { i, name ->
            val color = avatarColors[i % avatarColors.size]
            val inits = initials(name).ifEmpty { "?" }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(inits, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
