package ai.crossmeeting.app.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.crossmeeting.app.MeetingRow
import ai.crossmeeting.app.SpaceRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch

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

    // Meetings grouped: null space = "Sem pasta", otherwise space name
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
        loading = true
        error = null
        scope.launch {
            runCatching {
                val postgrest = SupabaseClientProvider.client.postgrest
                spaces = postgrest.from("spaces").select().decodeList<SpaceRow>()
                meetings = postgrest.from("meetings").select().decodeList<MeetingRow>()
                    .filter { it.deletedAt == null }
                    .sortedByDescending { it.createdAt }
            }.onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Todas as Reuniões", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CmWave)
            error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((spaceId, list) in grouped) {
                    val space = spaceId?.let { spaceById[it] }
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 4.dp)) {
                            if (space != null) {
                                if (space.emoji.isNotBlank()) Text(space.emoji)
                                else Icon(Icons.Filled.Folder, contentDescription = null, tint = CmWave, modifier = Modifier.size(14.dp))
                                Text(space.name.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("SEM PASTA",
                                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(list) { meeting ->
                        MeetingCard(meeting = meeting, onClick = { onOpenMeeting(meeting.id) })
                    }
                }

                if (!loading && meetings.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Nenhuma reunião ainda",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }
}
