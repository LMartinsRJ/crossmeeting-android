package ai.crossmeeting.app.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.crossmeeting.app.ActionItemRow
import ai.crossmeeting.app.MeetingRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

private fun fullDateLabel(): String = runCatching {
    val now = ZonedDateTime.now()
    val dow = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("pt", "BR"))
        .replaceFirstChar { it.uppercase() }
    val day = now.dayOfMonth
    val month = now.month.getDisplayName(TextStyle.FULL, Locale("pt", "BR"))
        .replaceFirstChar { it.uppercase() }
    "$dow, $day De $month"
}.getOrDefault("")

private fun greetingByHour2(): String {
    val h = ZonedDateTime.now().hour
    return when {
        h < 12 -> "Bom dia"
        h < 18 -> "Boa tarde"
        else -> "Boa noite"
    }
}

private fun userName2(): String {
    val email = SupabaseClientProvider.client.auth.currentUserOrNull()?.email ?: return ""
    val namePart = email.substringBefore("@")
    return namePart.split(Regex("[._-]")).filter { it.isNotBlank() }
        .firstOrNull()?.replaceFirstChar { it.uppercase() } ?: ""
}

private fun isToday(iso: String?): Boolean {
    if (iso == null) return false
    return try {
        val d = if (iso.contains("T")) LocalDate.parse(iso.substringBefore("T"))
                else LocalDate.parse(iso)
        d == LocalDate.now()
    } catch (e: Exception) { false }
}

private fun isMeetingToday(createdAt: String): Boolean {
    return try {
        val instant = java.time.Instant.parse(createdAt)
        val local = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        local == LocalDate.now()
    } catch (e: Exception) { false }
}

private fun isDueTodayOrBefore(dueDate: String?): Boolean {
    if (dueDate == null) return false
    return try {
        val d = LocalDate.parse(dueDate)
        !d.isAfter(LocalDate.now())
    } catch (e: Exception) { false }
}

private fun dueLabelShort(dueDate: String?): String {
    if (dueDate == null) return ""
    return try {
        val d = LocalDate.parse(dueDate)
        val today = LocalDate.now()
        when {
            d == today -> "Vence hoje"
            d.isBefore(today) -> "Em atraso"
            else -> "${d.dayOfMonth}/${d.monthValue}"
        }
    } catch (e: Exception) { dueDate }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefingScreen(
    modifier: Modifier = Modifier,
    onOpenMeeting: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var meetings by remember { mutableStateOf<List<MeetingRow>>(emptyList()) }
    var actions by remember { mutableStateOf<List<ActionItemRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val meetingsToday = remember(meetings) { meetings.filter { isMeetingToday(it.createdAt) } }
    val recentOther = remember(meetings, meetingsToday) {
        meetings.filter { m -> meetingsToday.none { it.id == m.id } }.take(5)
    }
    val urgentActions = remember(actions) {
        actions.filter { it.status != "concluída" && isDueTodayOrBefore(it.dueDate) }
            .sortedBy { it.dueDate }
    }
    val overdueCount = remember(actions) {
        actions.count { it.status != "concluída" && it.dueDate != null &&
            runCatching { LocalDate.parse(it.dueDate).isBefore(LocalDate.now()) }.getOrDefault(false) }
    }
    val dueTodayCount = remember(actions) {
        actions.count { it.status != "concluída" &&
            runCatching { LocalDate.parse(it.dueDate ?: "") == LocalDate.now() }.getOrDefault(false) }
    }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            runCatching {
                val postgrest = SupabaseClientProvider.client.postgrest
                meetings = postgrest.from("meetings").select().decodeList<MeetingRow>()
                    .filter { it.deletedAt == null }
                    .sortedByDescending { it.createdAt }
                actions = postgrest.from("action_items").select().decodeList<ActionItemRow>()
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.WbSunny, contentDescription = null, tint = CmWave, modifier = Modifier.size(20.dp))
                        Text("Briefing do Dia", fontWeight = FontWeight.Bold)
                    }
                },
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                // ─── Cabeçalho ──────────────────────────────────────────────────
                item {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text("${greetingByHour2()}, ${userName2()}",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground)
                        Text(fullDateLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ─── Stats ───────────────────────────────────────────────────────
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("Reuniões hoje", meetingsToday.size.toString(), modifier = Modifier.weight(1f))
                        StatCard("Vencem hoje", dueTodayCount.toString(),
                            highlight = dueTodayCount > 0, modifier = Modifier.weight(1f))
                        StatCard("Em atraso", overdueCount.toString(),
                            highlight = overdueCount > 0, error = overdueCount > 0, modifier = Modifier.weight(1f))
                    }
                }

                // ─── Ações urgentes ───────────────────────────────────────────────
                if (urgentActions.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("AÇÕES — HOJE E EM ATRASO",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(urgentActions, key = { "action-${it.id}" }) { action ->
                        BriefingActionCard(action = action,
                            onOpenMeeting = { action.meetingId?.let { onOpenMeeting(it) } })
                    }
                }

                // ─── Reuniões de hoje ────────────────────────────────────────────
                if (meetingsToday.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("REUNIÕES DE HOJE",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(meetingsToday, key = { "meeting-${it.id}" }) { meeting ->
                        MeetingCard(meeting = meeting, onClick = { onOpenMeeting(meeting.id) })
                    }
                }

                // ─── Reuniões recentes (últimas 5 não de hoje) ───────────────────
                if (recentOther.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("REUNIÕES RECENTES",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(recentOther, key = { "recent-${it.id}" }) { meeting ->
                        MeetingCard(meeting = meeting, onClick = { onOpenMeeting(meeting.id) })
                    }
                }

                if (!loading && meetings.isEmpty() && actions.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Sem dados para hoje ainda",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier,
                     highlight: Boolean = false, error: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = when {
                    error -> MaterialTheme.colorScheme.error
                    highlight -> CmWave
                    else -> MaterialTheme.colorScheme.onSurface
                })
        }
    }
}

@Composable
private fun BriefingActionCard(action: ActionItemRow, onOpenMeeting: () -> Unit) {
    val overdue = try {
        action.dueDate != null && LocalDate.parse(action.dueDate).isBefore(LocalDate.now())
    } catch (e: Exception) { false }

    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = if (overdue) MaterialTheme.colorScheme.errorContainer
                           else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp), modifier = Modifier.size(8.dp).padding(top = 6.dp)) {}
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(action.text, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (action.dueDate != null) {
                        Surface(color = if (overdue) MaterialTheme.colorScheme.errorContainer
                                       else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)) {
                            Text(dueLabelShort(action.dueDate),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (overdue) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (action.meetingTitle != null) {
                        Surface(color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(6.dp),
                            onClick = onOpenMeeting) {
                            Text(action.meetingTitle, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}
