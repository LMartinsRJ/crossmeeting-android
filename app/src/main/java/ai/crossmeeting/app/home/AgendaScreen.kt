package ai.crossmeeting.app.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import ai.crossmeeting.app.recording.RecordingService
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.crossmeeting.app.CalendarAttendee
import ai.crossmeeting.app.CalendarEventRow
import ai.crossmeeting.app.MeetingRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmBlue
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private fun calendarEventStart(ev: CalendarEventRow): ZonedDateTime? = runCatching {
    Instant.parse(ev.startTime).atZone(deviceZone())
}.getOrNull()

private fun calendarEventEnd(ev: CalendarEventRow): ZonedDateTime? = runCatching {
    Instant.parse(ev.endTime).atZone(deviceZone())
}.getOrNull()

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now(deviceZone())
    return when {
        date == today             -> "Hoje"
        date == today.plusDays(1) -> "Amanhã"
        else -> {
            val dow = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("pt", "BR"))
                .replaceFirstChar { it.uppercase() }
            val day = date.dayOfMonth
            val month = date.month.getDisplayName(TextStyle.SHORT, Locale("pt", "BR"))
                .replaceFirstChar { it.uppercase() }.trimEnd('.')
            "$dow, $day $month"
        }
    }
}

private fun formatTime(zdt: ZonedDateTime): String = "%02d:%02d".format(zdt.hour, zdt.minute)

private fun isMeetingToday(createdAt: String): Boolean = runCatching {
    Instant.parse(createdAt).atZone(deviceZone()).toLocalDate() == LocalDate.now(deviceZone())
}.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    modifier: Modifier = Modifier,
    onOpenMeeting: (Long) -> Unit,
    onStartRecording: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingLink by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val link = pendingLink
            if (link != null) {
                context.startService(Intent(context, RecordingService::class.java).apply {
                    putExtra(RecordingService.EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
                })
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                pendingLink = null
            } else {
                onStartRecording()
            }
        }
    }

    fun openLinkAndRecord(link: String) {
        // Inicia gravação em background (notificação) e abre o link — sem navegar para RecordingScreen
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            context.startService(Intent(context, RecordingService::class.java).apply {
                putExtra(RecordingService.EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
            })
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } else {
            pendingLink = link
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var events by remember { mutableStateOf<List<CalendarEventRow>>(emptyList()) }
    var recordings by remember { mutableStateOf<List<MeetingRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val groupedEvents: Map<LocalDate, List<CalendarEventRow>> = remember(events) {
        val today = LocalDate.now()
        events.groupBy { ev ->
            calendarEventStart(ev)?.toLocalDate() ?: today
        }.toSortedMap()
    }

    val todayRecordings = remember(recordings) {
        recordings.filter { it.deletedAt == null && isMeetingToday(it.createdAt) }
            .sortedByDescending { it.createdAt }
    }

    suspend fun loadData() {
        loading = true; error = null
        // Sincroniza direto com Google Calendar (token salvo no login)
        runCatching { SupabaseClientProvider.client.functions.invoke("sync-calendar") }
        runCatching {
            val pg    = SupabaseClientProvider.client.postgrest
            val now   = ZonedDateTime.now()
            val until = now.plusDays(7)
            // Filtra por end_at >= agora (inclui reuniões em andamento) E start_at <= +7 dias
            val all = pg.from("calendar_events").select().decodeList<CalendarEventRow>()
            val zone = deviceZone()
            events = all.filter { ev ->
                runCatching {
                    val start = Instant.parse(ev.startTime).atZone(zone)
                    val end   = Instant.parse(ev.endTime).atZone(zone)
                    !end.isBefore(now) && start.isBefore(until)
                }.getOrDefault(false)
            }
            recordings = pg.from("meetings").select().decodeList<MeetingRow>()
        }.onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(Unit) { loadData() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Agenda",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        // diagnóstico temporário — remover após confirmar fuso correto
                        Text(
                            "fuso: ${deviceZone(context).id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { scope.launch { loadData() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Atualizar", tint = CmWave)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CmWave)
            error?.let {
                Text("Erro: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            // ─── Estado: sem eventos nos próximos 7 dias ──────────────────────
            if (!loading && groupedEvents.isEmpty() && todayRecordings.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        Text("Nenhuma reunião nos próximos 7 dias", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@Scaffold
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ─── Gravações de hoje ────────────────────────────────────────
                if (todayRecordings.isNotEmpty()) {
                    item {
                        Text(
                            "GRAVAÇÕES DE HOJE",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(todayRecordings, key = { "rec-${it.id}" }) { meeting ->
                        MeetingCard(meeting = meeting, onClick = { onOpenMeeting(meeting.id) })
                    }
                }

                // ─── Agenda do calendário ─────────────────────────────────────
                groupedEvents.forEach { (date, dayEvents) ->
                    item(key = "header-$date") {
                        Text(
                            dayLabel(date).uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = if (date == LocalDate.now()) CmWave
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(dayEvents, key = { "ev-${it.id}" }) { ev ->
                        CalendarEventCard(
                            event = ev,
                            onJoinAndRecord = { link -> openLinkAndRecord(link) },
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarEventCard(
    event: CalendarEventRow,
    onJoinAndRecord: (String) -> Unit = {},
) {
    val start = calendarEventStart(event)
    val end = calendarEventEnd(event)
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { showSheet = true },
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (start != null) {
                Surface(color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(10.dp)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(formatTime(start), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = CmWave)
                        if (end != null) {
                            Text(formatTime(end), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                val providerLabel = when (event.provider) {
                    "google"    -> "Google Calendar"
                    "microsoft" -> "Microsoft Calendar"
                    else        -> event.provider.replaceFirstChar { it.uppercase() }
                }
                Text(providerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            CalendarEventDetail(
                event = event,
                start = start,
                end = end,
                onJoinOnly = { link ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    showSheet = false
                },
                onJoinAndRecord = { link ->
                    showSheet = false
                    onJoinAndRecord(link)
                },
            )
        }
    }
}

@Composable
private fun CalendarEventDetail(
    event: CalendarEventRow,
    start: ZonedDateTime?,
    end: ZonedDateTime?,
    onJoinOnly: (String) -> Unit,
    onJoinAndRecord: (String) -> Unit,
) {
    var showRecordDialog by remember { mutableStateOf(false) }
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("pt", "BR"))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Título
        Text(
            event.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Data e hora
        if (start != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = CmWave, modifier = Modifier.size(20.dp))
                Column {
                    Text(
                        start.format(dateFormatter).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val timeRange = if (end != null) "${formatTime(start)} – ${formatTime(end)}" else formatTime(start)
                    Text(timeRange, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Participantes
        val attendees = event.attendees.orEmpty()
        if (attendees.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Group, contentDescription = null, tint = CmWave, modifier = Modifier.size(20.dp))
                    Text(
                        "${attendees.size} participante${if (attendees.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                attendees.forEach { attendee ->
                    AttendeeRow(attendee)
                }
            }
        }

        // Provedor
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(
                when (event.provider) {
                    "google"    -> "Google Calendar"
                    "microsoft" -> "Microsoft Calendar"
                    else        -> event.provider.replaceFirstChar { it.uppercase() }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Botão entrar
        if (event.meetingLink != null) {
            Button(
                onClick = { showRecordDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = CmWave),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.VideoCall, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Entrar na reunião", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (showRecordDialog && event.meetingLink != null) {
            AlertDialog(
                onDismissRequest = { showRecordDialog = false },
                icon = { Icon(Icons.Filled.Mic, contentDescription = null, tint = CmWave) },
                title = { Text("Gravar esta reunião?") },
                text = { Text("O Crossmeeting pode gravar e transcrever a reunião automaticamente enquanto você participa.") },
                confirmButton = {
                    Button(
                        onClick = { showRecordDialog = false; onJoinAndRecord(event.meetingLink) },
                        colors = ButtonDefaults.buttonColors(containerColor = CmWave),
                    ) { Text("Entrar e gravar") }
                },
                dismissButton = {
                    TextButton(onClick = { showRecordDialog = false; onJoinOnly(event.meetingLink) }) {
                        Text("Só entrar")
                    }
                },
            )
        }
    }
}

@Composable
private fun AttendeeRow(attendee: CalendarAttendee) {
    Row(
        modifier = Modifier.padding(start = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = CmWave.copy(alpha = 0.15f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    (attendee.name.firstOrNull() ?: attendee.email.firstOrNull() ?: '?').uppercaseChar().toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = CmWave,
                )
            }
        }
        Column {
            if (attendee.name.isNotBlank()) {
                Text(attendee.name, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            }
            Text(attendee.email, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
