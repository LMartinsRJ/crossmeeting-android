package ai.crossmeeting.app.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import ai.crossmeeting.app.MeetingRow
import ai.crossmeeting.app.MeetingSpaceUpdate
import ai.crossmeeting.app.SpaceRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch

/**
 * Lista de reuniões de um space específico ("pasta") — equivalente mobile de clicar numa
 * pasta na sidebar do desktop/web. Toque e segure numa reunião move ela pra outro space ou
 * remove dessa pasta (mesma ação disponível na HomeScreen).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpaceDetailScreen(spaceId: Long, onBack: () -> Unit, onOpenMeeting: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var space by remember { mutableStateOf<SpaceRow?>(null) }
    var allSpaces by remember { mutableStateOf<List<SpaceRow>>(emptyList()) }
    var meetings by remember { mutableStateOf<List<MeetingRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var meetingToMove by remember { mutableStateOf<MeetingRow?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            runCatching {
                val postgrest = SupabaseClientProvider.client.postgrest
                allSpaces = postgrest.from("spaces").select().decodeList<SpaceRow>()
                space = allSpaces.firstOrNull { it.id == spaceId }
                meetings = postgrest.from("meetings").select().decodeList<MeetingRow>()
                    .filter { it.deletedAt == null && it.spaceId == spaceId }
                    .sortedByDescending { it.createdAt }
            }.onFailure { error = it.message }
            loading = false
        }
    }

    fun moveMeetingToSpace(meeting: MeetingRow, newSpaceId: Long?) {
        scope.launch {
            runCatching {
                SupabaseClientProvider.client.postgrest.from("meetings")
                    .update(MeetingSpaceUpdate(newSpaceId)) { filter { eq("id", meeting.id) } }
            }.onFailure { error = it.message }
            meetingToMove = null
            refresh()
        }
    }

    LaunchedEffect(spaceId) { refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${space?.emoji ?: "📁"} ${space?.name ?: "Pasta"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
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
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CmWave)
            error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

            if (!loading && meetings.isEmpty() && error == null) {
                Text(
                    "Nenhuma reunião nessa pasta ainda.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(meetings) { meeting ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = { onOpenMeeting(meeting.id) },
                            onLongClick = { meetingToMove = meeting },
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(meeting.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(meeting.createdAt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        meetingToMove?.let { meeting ->
            ModalBottomSheet(onDismissRequest = { meetingToMove = null }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Mover \"${meeting.title}\" para...",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    allSpaces.filter { it.id != spaceId }.forEach { target ->
                        DropdownMenuItem(
                            text = { Text("${target.emoji} ${target.name}") },
                            onClick = { moveMeetingToSpace(meeting, target.id) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Sem pasta") },
                        onClick = { moveMeetingToSpace(meeting, null) },
                    )
                }
            }
        }
    }
}
