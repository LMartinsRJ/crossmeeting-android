package ai.crossmeeting.app.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ai.crossmeeting.app.ActionItemRow
import ai.crossmeeting.app.CalendarEventRow
import ai.crossmeeting.app.MeetingRow
import ai.crossmeeting.app.MeetingSpaceUpdate
import ai.crossmeeting.app.SpaceRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.recording.RecordingService
import ai.crossmeeting.app.ui.theme.CmBlue
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

internal val PT_BR = Locale("pt", "BR")

internal fun monthAbbrev(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).month
        .getDisplayName(TextStyle.SHORT, PT_BR).uppercase().trimEnd('.')
}.getOrDefault("—")

internal fun dayOfMonth(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).dayOfMonth.toString()
}.getOrDefault("?")

internal fun timeOfDay(iso: String): String = runCatching {
    val date = Instant.parse(iso).atZone(ZoneId.systemDefault())
    "%02d:%02d".format(date.hour, date.minute)
}.getOrDefault("")

private fun fullDateLabel(): String = runCatching {
    val now = ZonedDateTime.now()
    val dow = now.dayOfWeek.getDisplayName(TextStyle.FULL, PT_BR).replaceFirstChar { it.uppercase() }
    val day = now.dayOfMonth
    val month = now.month.getDisplayName(TextStyle.FULL, PT_BR).replaceFirstChar { it.uppercase() }
    "$dow, $day de $month"
}.getOrDefault("")

private fun greetingByHour(): String {
    val h = ZonedDateTime.now().hour
    return when {
        h < 12 -> "Bom dia"
        h < 18 -> "Boa tarde"
        else   -> "Boa noite"
    }
}

internal fun userInitials(): String {
    val email = SupabaseClientProvider.client.auth.currentUserOrNull()?.email ?: return "?"
    val namePart = email.substringBefore("@")
    return namePart.split(Regex("[._-]")).filter { it.isNotBlank() }
        .take(2).joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }
}

private fun userName(): String {
    val email = SupabaseClientProvider.client.auth.currentUserOrNull()?.email ?: return ""
    val namePart = email.substringBefore("@")
    return namePart.split(Regex("[._-]")).filter { it.isNotBlank() }
        .firstOrNull()?.replaceFirstChar { it.uppercase() } ?: ""
}

private fun isMeetingToday(createdAt: String): Boolean = runCatching {
    Instant.parse(createdAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
}.getOrDefault(false)

private fun isDueTodayOrBefore(dueDate: String?): Boolean {
    if (dueDate == null) return false
    return runCatching { !LocalDate.parse(dueDate).isAfter(LocalDate.now()) }.getOrDefault(false)
}

private fun dueLabelShort(dueDate: String?): String {
    if (dueDate == null) return ""
    return runCatching {
        val d = LocalDate.parse(dueDate)
        val today = LocalDate.now()
        when {
            d == today        -> "Vence hoje"
            d.isBefore(today) -> "Em atraso"
            else              -> "${d.dayOfMonth}/${d.monthValue}"
        }
    }.getOrDefault(dueDate)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onStartRecording: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenMeeting: (Long) -> Unit,
    onGoToMeetings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var meetings by remember { mutableStateOf<List<MeetingRow>>(emptyList()) }
    var actions by remember { mutableStateOf<List<ActionItemRow>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<SpaceRow>>(emptyList()) }
    var calendarToday by remember { mutableStateOf<List<CalendarEventRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var meetingToMove by remember { mutableStateOf<MeetingRow?>(null) }

    val meetingsToday = remember(meetings) { meetings.filter { isMeetingToday(it.createdAt) } }
    val calendarTodayCount = remember(calendarToday) { calendarToday.size }
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

    fun launchRecording() {
        context.startService(Intent(context, RecordingService::class.java).apply {
            putExtra(RecordingService.EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
        })
        onStartRecording()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchRecording() }

    fun requestRecording() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) launchRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun moveMeetingToSpace(meeting: MeetingRow, spaceId: Long?) {
        scope.launch {
            runCatching {
                SupabaseClientProvider.client.postgrest.from("meetings")
                    .update(MeetingSpaceUpdate(spaceId)) { filter { eq("id", meeting.id) } }
            }.onFailure { error = it.message }
            meetingToMove = null
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
                actions = pg.from("action_items").select().decodeList<ActionItemRow>()
                val nowZdt     = ZonedDateTime.now()
                val endOfToday = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                calendarToday = pg.from("calendar_events").select()
                    .decodeList<CalendarEventRow>()
                    .filter { ev ->
                        runCatching {
                            val start = java.time.Instant.parse(ev.startTime).atZone(ZoneId.systemDefault())
                            val end   = java.time.Instant.parse(ev.endTime).atZone(ZoneId.systemDefault())
                            !end.isBefore(nowZdt) && start.isBefore(endOfToday)
                        }.getOrDefault(false)
                    }
            }.onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        // Re-consulta o calendário a cada minuto para atualizar o contador de reuniões futuras
        while (true) {
            delay(60_000L)
            runCatching {
                val pg         = SupabaseClientProvider.client.postgrest
                val nowZdt     = ZonedDateTime.now()
                val endOfToday = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                calendarToday = pg.from("calendar_events").select()
                    .decodeList<CalendarEventRow>()
                    .filter { ev ->
                        runCatching {
                            val start = java.time.Instant.parse(ev.startTime).atZone(ZoneId.systemDefault())
                            val end   = java.time.Instant.parse(ev.endTime).atZone(ZoneId.systemDefault())
                            !end.isBefore(nowZdt) && start.isBefore(endOfToday)
                        }.getOrDefault(false)
                    }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ai.crossmeeting.app.ui.theme.AppLogo()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            buildAnnotatedString {
                                append("CROSS")
                                withStyle(SpanStyle(color = CmWave)) { append("MEETING") }
                            },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Atualizar", tint = CmWave)
                    }
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Perguntar sobre reuniões", tint = CmWave)
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Surface(color = CmBlue, shape = androidx.compose.foundation.shape.CircleShape) {
                                Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                                    Text(userInitials(), style = MaterialTheme.typography.labelMedium, color = Color.White)
                                }
                            }
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Sair") },
                                leadingIcon = { Icon(Icons.Filled.ExitToApp, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch { SupabaseClientProvider.client.auth.signOut() }
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { requestRecording() }, containerColor = CmBlue) {
                Icon(Icons.Filled.Mic, contentDescription = "Nova gravação", tint = Color.White)
            }
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                // ─── Saudação ─────────────────────────────────────────────────
                item {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            "${greetingByHour()}, ${userName()}",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            fullDateLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ─── Stats (Reuniões hoje é clicável → aba Agenda) ───────────
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Clicável → vai para aba Reuniões (Agenda)
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            onClick = onGoToMeetings,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "Reuniões hoje",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CmWave,
                                )
                                Text(
                                    calendarTodayCount.toString(),
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = CmWave,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        "ver agenda",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CmWave,
                                    )
                                }
                            }
                        }
                        StatCard(
                            "Vencem hoje",
                            dueTodayCount.toString(),
                            highlight = dueTodayCount > 0,
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            "Em atraso",
                            overdueCount.toString(),
                            highlight = overdueCount > 0,
                            error = overdueCount > 0,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // ─── CTA Nova gravação ────────────────────────────────────────
                item {
                    Surface(
                        color = CmBlue,
                        shape = RoundedCornerShape(20.dp),
                        onClick = { requestRecording() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(color = Color.White.copy(alpha = 0.18f), shape = RoundedCornerShape(12.dp)) {
                                Box(modifier = Modifier.padding(10.dp)) {
                                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                            Text(
                                "Nova gravação",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                            )
                        }
                    }
                }

                // ─── Ações urgentes ───────────────────────────────────────────
                if (urgentActions.isNotEmpty()) {
                    item {
                        Text(
                            "AÇÕES — HOJE E EM ATRASO",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                    }
                    items(urgentActions, key = { "action-${it.id}" }) { action ->
                        BriefingActionCard(action = action,
                            onOpenMeeting = { action.meetingId?.let { onOpenMeeting(it) } })
                    }
                }

                // ─── Reuniões gravadas hoje ───────────────────────────────────
                if (meetingsToday.isNotEmpty()) {
                    item {
                        Text(
                            "REUNIÕES DE HOJE",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                    }
                    items(meetingsToday, key = { "today-${it.id}" }) { meeting ->
                        MeetingCard(meeting = meeting, onClick = { onOpenMeeting(meeting.id) })
                    }
                }

                // ─── Reuniões recentes ────────────────────────────────────────
                if (recentOther.isNotEmpty()) {
                    item {
                        Text(
                            "REUNIÕES RECENTES",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                    }
                    items(recentOther, key = { "recent-${it.id}" }) { meeting ->
                        MeetingCard(meeting = meeting, onClick = { onOpenMeeting(meeting.id) })
                    }
                }

                if (!loading && meetings.isEmpty() && actions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Sem dados para hoje ainda", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }

        meetingToMove?.let { meeting ->
            ModalBottomSheet(onDismissRequest = { meetingToMove = null }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Mover \"${meeting.title}\" para...",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    spaces.forEach { space ->
                        DropdownMenuItem(text = { Text("${space.emoji} ${space.name}") },
                            onClick = { moveMeetingToSpace(meeting, space.id) })
                    }
                    DropdownMenuItem(text = { Text("Sem pasta") },
                        onClick = { moveMeetingToSpace(meeting, null) })
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
internal fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    error: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = when {
                    error     -> MaterialTheme.colorScheme.error
                    highlight -> CmWave
                    else      -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
internal fun BriefingActionCard(action: ActionItemRow, onOpenMeeting: () -> Unit) {
    val overdue = runCatching {
        action.dueDate != null && LocalDate.parse(action.dueDate).isBefore(LocalDate.now())
    }.getOrDefault(false)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = if (overdue) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(8.dp).padding(top = 6.dp),
            ) {}
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(action.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (action.dueDate != null) {
                        Surface(
                            color = if (overdue) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                dueLabelShort(action.dueDate),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (overdue) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (action.meetingTitle != null) {
                        Surface(color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(6.dp), onClick = onOpenMeeting) {
                            Text(
                                action.meetingTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MeetingCard(meeting: MeetingRow, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(12.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(monthAbbrev(meeting.createdAt), style = MaterialTheme.typography.labelSmall, color = CmWave)
                    Text(dayOfMonth(meeting.createdAt), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(Icons.Filled.Description, contentDescription = null, tint = CmWave, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(meeting.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(timeOfDay(meeting.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (meeting.wordCount > 0) {
                        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text("${meeting.wordCount} palavras", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
