package ai.crossmeeting.app.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.crossmeeting.app.ActionItemRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate

private fun isOverdue(dueDate: String?): Boolean {
    if (dueDate == null) return false
    return try { LocalDate.parse(dueDate).isBefore(LocalDate.now()) } catch (e: Exception) { false }
}

private fun isDueToday(dueDate: String?): Boolean {
    if (dueDate == null) return false
    return try { LocalDate.parse(dueDate) == LocalDate.now() } catch (e: Exception) { false }
}

private fun dueDateLabel(dueDate: String?): String {
    if (dueDate == null) return ""
    return try {
        val d = LocalDate.parse(dueDate)
        val today = LocalDate.now()
        when {
            d == today -> "Vence hoje"
            d.isBefore(today) -> "Em atraso"
            else -> "Vence ${d.dayOfMonth}/${d.monthValue}"
        }
    } catch (e: Exception) { dueDate }
}

@Serializable
data class ActionStatusUpdate(val status: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(
    modifier: Modifier = Modifier,
    onOpenMeeting: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var actions by remember { mutableStateOf<List<ActionItemRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("pendentes") } // "pendentes" | "todas"

    val filtered = remember(actions, filter) {
        if (filter == "pendentes") actions.filter { it.status != "concluída" }
        else actions
    }

    // Group: overdue → today → upcoming → no date
    val grouped = remember(filtered) {
        val overdue = filtered.filter { isOverdue(it.dueDate) }.sortedBy { it.dueDate }
        val today = filtered.filter { isDueToday(it.dueDate) }
        val upcoming = filtered.filter { it.dueDate != null && !isOverdue(it.dueDate) && !isDueToday(it.dueDate) }.sortedBy { it.dueDate }
        val noDate = filtered.filter { it.dueDate == null }
        buildList {
            if (overdue.isNotEmpty()) add("EM ATRASO" to overdue)
            if (today.isNotEmpty()) add("HOJE" to today)
            if (upcoming.isNotEmpty()) add("PRÓXIMAS" to upcoming)
            if (noDate.isNotEmpty()) add("SEM DATA" to noDate)
        }
    }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            runCatching {
                actions = SupabaseClientProvider.client.postgrest.from("action_items")
                    .select()
                    .decodeList<ActionItemRow>()
                    .sortedWith(compareBy(nullsLast()) { it.dueDate })
            }.onFailure { error = it.message }
            loading = false
        }
    }

    fun toggleDone(action: ActionItemRow) {
        val newStatus = if (action.status == "concluída") "pendente" else "concluída"
        scope.launch {
            runCatching {
                SupabaseClientProvider.client.postgrest.from("action_items")
                    .update(ActionStatusUpdate(newStatus)) { filter { eq("id", action.id) } }
                actions = actions.map { if (it.id == action.id) it.copy(status = newStatus) else it }
            }.onFailure { error = it.message }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Ações", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    FilterChip(
                        selected = filter == "pendentes",
                        onClick = { filter = if (filter == "pendentes") "todas" else "pendentes" },
                        label = { Text(if (filter == "pendentes") "Pendentes" else "Todas") },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CmWave)
            error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((group, list) in grouped) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(group,
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = if (group == "EM ATRASO") MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(list, key = { it.id }) { action ->
                        ActionCard(action = action, onToggle = { toggleDone(action) },
                            onOpenMeeting = { action.meetingId?.let { onOpenMeeting(it) } })
                    }
                }

                if (!loading && filtered.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center) {
                            Text(if (filter == "pendentes") "Nenhuma ação pendente" else "Nenhuma ação registrada",
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

@Composable
private fun ActionCard(action: ActionItemRow, onToggle: () -> Unit, onOpenMeeting: () -> Unit) {
    val done = action.status == "concluída"
    val overdue = isOverdue(action.dueDate)
    val today = isDueToday(action.dueDate)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(24.dp).padding(0.dp)) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (done) "Marcar pendente" else "Marcar concluída",
                    tint = if (done) CmWave else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    action.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Chip de vencimento
                    if (action.dueDate != null) {
                        val chipColor = when {
                            done -> Color.White.copy(alpha = 0.06f)
                            overdue -> MaterialTheme.colorScheme.errorContainer
                            today -> MaterialTheme.colorScheme.primaryContainer
                            else -> Color.White.copy(alpha = 0.06f)
                        }
                        val textColor = when {
                            done -> MaterialTheme.colorScheme.onSurfaceVariant
                            overdue -> MaterialTheme.colorScheme.onErrorContainer
                            today -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(color = chipColor, shape = RoundedCornerShape(6.dp)) {
                            Text(dueDateLabel(action.dueDate),
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    // Fonte (reunião)
                    if (action.meetingTitle != null) {
                        Surface(
                            color = Color.White.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(6.dp),
                            onClick = onOpenMeeting,
                        ) {
                            Text(action.meetingTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                if (action.owner != null) {
                    Text("→ ${action.owner}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
