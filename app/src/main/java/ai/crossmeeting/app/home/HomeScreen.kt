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
import kotlinx.coroutines.launch
import java.time.Instant
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

private fun todayDateLabel(): String = runCatching {
    val now = ZonedDateTime.now()
    val day = now.dayOfMonth
    val month = now.month.getDisplayName(TextStyle.SHORT, PT_BR).uppercase().trimEnd('.')
    "$day / $month"
}.getOrDefault("")

private fun greetingByHour(): String {
    val h = ZonedDateTime.now().hour
    return when {
        h < 12 -> "Bom dia"
        h < 18 -> "Boa tarde"
        else -> "Boa noite"
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onStartRecording: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenMeeting: (Long) -> Unit,
    onOpenSpace: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var spaces by remember { mutableStateOf<List<SpaceRow>>(emptyList()) }
    var meetings by remember { mutableStateOf<List<MeetingRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var meetingToMove by remember { mutableStateOf<MeetingRow?>(null) }

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

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            runCatching {
                val postgrest = SupabaseClientProvider.client.postgrest
                spaces = postgrest.from("spaces").select().decodeList<SpaceRow>()
                meetings = postgrest.from("meetings")
                    .select()
                    .decodeList<MeetingRow>()
                    .filter { it.deletedAt == null && it.spaceId == null }
                    .sortedByDescending { it.createdAt }
                    .take(20)
            }.onFailure { error = it.message }
            loading = false
        }
    }

    fun moveMeetingToSpace(meeting: MeetingRow, spaceId: Long?) {
        scope.launch {
            runCatching {
                SupabaseClientProvider.client.postgrest.from("meetings")
                    .update(MeetingSpaceUpdate(spaceId)) { filter { eq("id", meeting.id) } }
            }.onFailure { error = it.message }
            meetingToMove = null
            refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {

                // ─── Cabeçalho ────────────────────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                "${greetingByHour()}, ${userName()}",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(todayDateLabel(), style = MaterialTheme.typography.bodyMedium, color = CmWave)
                        }
                    }
                }

                // ─── CTA Nova gravação ─────────────────────────────────────────────
                item {
                    Surface(
                        color = CmBlue,
                        shape = RoundedCornerShape(20.dp),
                        onClick = { requestRecording() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(color = Color.White.copy(alpha = 0.18f), shape = RoundedCornerShape(12.dp)) {
                                Box(modifier = Modifier.padding(10.dp)) {
                                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                            }
                            Column {
                                Text("Nova gravação",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White)
                                Text("Captura de microfone e sistema",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.75f))
                            }
                        }
                    }
                }

                // ─── Spaces ──────────────────────────────────────────────────────
                if (spaces.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                    item {
                        Text("SPACES", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(spaces) { space -> SpaceCard(space, onClick = { onOpenSpace(space.id) }) }
                }

                // ─── Reuniões sem pasta ───────────────────────────────────────────
                if (meetings.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                    item {
                        Text("RECENTES",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(meetings) { meeting ->
                        MeetingCard(
                            meeting = meeting,
                            onClick = { onOpenMeeting(meeting.id) },
                            onLongClick = { meetingToMove = meeting },
                        )
                    }
                } else if (!loading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Nenhuma reunião sem pasta aqui",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("Reuniões em pastas aparecem na aba Reuniões",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }

        meetingToMove?.let { meeting ->
            ModalBottomSheet(onDismissRequest = { meetingToMove = null }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mover \"${meeting.title}\" para...",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp))
                    spaces.forEach { space ->
                        DropdownMenuItem(text = { Text("${space.emoji} ${space.name}") },
                            onClick = { moveMeetingToSpace(meeting, space.id) })
                    }
                    DropdownMenuItem(text = { Text("Sem pasta (Início)") },
                        onClick = { moveMeetingToSpace(meeting, null) })
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
internal fun SpaceCard(space: SpaceRow, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CmWave.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp)) {
                Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                    if (space.emoji.isNotBlank()) Text(space.emoji, style = MaterialTheme.typography.titleMedium)
                    else Icon(Icons.Filled.Folder, contentDescription = null, tint = CmWave)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(space.name, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (space.isDefault) {
                Surface(color = Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                    Text("padrão", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(monthAbbrev(meeting.createdAt), style = MaterialTheme.typography.labelSmall, color = CmWave)
                    Text(dayOfMonth(meeting.createdAt), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(Icons.Filled.Description, contentDescription = null, tint = CmWave, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(meeting.title, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(timeOfDay(meeting.createdAt), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (meeting.wordCount > 0) {
                        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Text("${meeting.wordCount} palavras", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
