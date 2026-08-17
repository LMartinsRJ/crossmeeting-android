package ai.crossmeeting.app.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import ai.crossmeeting.app.ProfileRow
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate

internal fun isOverdue(dueDate: String?): Boolean {
    if (dueDate == null) return false
    return try { LocalDate.parse(dueDate).isBefore(LocalDate.now()) } catch (e: Exception) { false }
}

internal fun isDueToday(dueDate: String?): Boolean {
    if (dueDate == null) return false
    return try { LocalDate.parse(dueDate) == LocalDate.now() } catch (e: Exception) { false }
}

internal fun dueDateLabel(dueDate: String?): String {
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

/** Edição do texto e do responsável de uma ação já existente. */
@Serializable
data class ActionEditUpdate(val text: String, val owner: String? = null)

/**
 * Ação criada à mão, sem vínculo com reunião.
 * status/tipo/prioridade são normalizados pelo trigger do banco de qualquer forma.
 */
@Serializable
data class NewActionItem(
    @kotlinx.serialization.SerialName("user_id") val userId: String,
    val text: String,
    val owner: String? = null,
    @kotlinx.serialization.SerialName("due_date") val dueDate: String? = null,
    val status: String = "pendente",
    val tipo: String = "acao",
    val prioridade: String = "media",
)

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
    var showCreate by remember { mutableStateOf(false) }

    val filtered = remember(actions, filter) {
        if (filter == "pendentes") actions.filter { !isFinished(it.status) }
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

    fun updateStatus(action: ActionItemRow, newStatus: String) {
        scope.launch {
            runCatching {
                SupabaseClientProvider.client.postgrest.from("action_items")
                    .update(ActionStatusUpdate(newStatus)) { filter { eq("id", action.id) } }
                actions = actions.map { if (it.id == action.id) it.copy(status = newStatus) else it }
            }.onFailure { error = it.message }
        }
    }

    fun updateTextAndOwner(action: ActionItemRow, newText: String, newOwner: String?) {
        val text = newText.trim()
        if (text.isEmpty()) return
        scope.launch {
            runCatching {
                SupabaseClientProvider.client.postgrest.from("action_items")
                    .update(ActionEditUpdate(text, newOwner)) { filter { eq("id", action.id) } }
                actions = actions.map {
                    if (it.id == action.id) it.copy(text = text, owner = newOwner) else it
                }
            }.onFailure { error = it.message }
        }
    }

    fun createAction(text: String, owner: String?, dueDate: String?) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            runCatching {
                // profiles.id é o UUID que action_items.user_id referencia — mesmo
                // padrão usado no insert de reuniões em RecordingScreen.
                val userId = SupabaseClientProvider.client.postgrest.from("profiles")
                    .select().decodeSingle<ProfileRow>().id
                SupabaseClientProvider.client.postgrest.from("action_items")
                    .insert(NewActionItem(
                        userId = userId,
                        text = clean,
                        owner = owner?.trim()?.ifBlank { null },
                        dueDate = dueDate?.ifBlank { null },
                    ))
            }.onFailure { error = it.message }
            // Relê do servidor em vez de decodificar o retorno do insert:
            // garante que o id e os defaults do trigger venham corretos.
            refresh()
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                containerColor = CmWave,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Nova ação")
            }
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
                        ActionCard(
                            action = action,
                            onStatusChange = { newStatus -> updateStatus(action, newStatus) },
                            onEdit = { newText, newOwner -> updateTextAndOwner(action, newText, newOwner) },
                            onOpenMeeting = { action.meetingId?.let { onOpenMeeting(it) } },
                        )
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

    if (showCreate) {
        NewActionDialog(
            onDismiss = { showCreate = false },
            onCreate = { text, owner, dueDate ->
                createAction(text, owner, dueDate)
                showCreate = false
            },
        )
    }
}

/** Diálogo de criação manual de ação. */
@Composable
internal fun NewActionDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String?, String?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova ação") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("O que precisa ser feito?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    label = { Text("Responsável (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = { Text("Prazo — AAAA-MM-DD (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(text, owner.ifBlank { null }, dueDate.ifBlank { null }) },
                enabled = text.isNotBlank(),
            ) { Text("Criar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

// Vocabulário canônico de status — igual ao desktop, à web e ao CHECK do banco.
// Antes o Android gravava "em andamento", "concluída" e "cancelada", variantes
// que as outras plataformas não reconheciam nos filtros e nos mapas de cor.
const val STATUS_PENDENTE = "pendente"
const val STATUS_EM_ANDAMENTO = "em_andamento"
const val STATUS_CONCLUIDO = "concluido"
const val STATUS_CANCELADO = "cancelado"

val statusOptions = listOf(STATUS_PENDENTE, STATUS_EM_ANDAMENTO, STATUS_CONCLUIDO, STATUS_CANCELADO)

/** Aceita as variantes antigas na leitura — registros gravados antes da normalização. */
internal fun normalizeStatus(status: String) = when (status.trim().lowercase()) {
    "pendente" -> STATUS_PENDENTE
    "em_andamento", "em andamento" -> STATUS_EM_ANDAMENTO
    "concluido", "concluída", "concluida", "concluído" -> STATUS_CONCLUIDO
    "cancelado", "cancelada" -> STATUS_CANCELADO
    else -> STATUS_PENDENTE
}

internal fun isFinished(status: String) =
    normalizeStatus(status).let { it == STATUS_CONCLUIDO || it == STATUS_CANCELADO }

internal fun statusLabel(status: String) = when (normalizeStatus(status)) {
    STATUS_PENDENTE      -> "Pendente"
    STATUS_EM_ANDAMENTO  -> "Em andamento"
    STATUS_CONCLUIDO     -> "Concluído"
    STATUS_CANCELADO     -> "Cancelado"
    else                 -> status.replaceFirstChar { it.uppercaseChar() }
}

internal fun statusColor(status: String, colorScheme: ColorScheme) = when (normalizeStatus(status)) {
    STATUS_CONCLUIDO     -> Color(0xFF26A69A)
    STATUS_EM_ANDAMENTO  -> Color(0xFF6C8EFF)
    STATUS_CANCELADO     -> colorScheme.onSurfaceVariant
    else                 -> colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionCard(
    action: ActionItemRow,
    onStatusChange: (String) -> Unit,
    onOpenMeeting: () -> Unit,
    // Opcional: telas que só exibem a ação (ex.: detalhe da reunião) não passam,
    // e aí o botão "Editar" nem aparece.
    onEdit: ((String, String?) -> Unit)? = null,
) {
    val done = isFinished(action.status)
    val overdue = isOverdue(action.dueDate)
    val today = isDueToday(action.dueDate)
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Rascunhos de edição — reiniciam sempre que a ação muda por fora
    var editing by remember(action.id) { mutableStateOf(false) }
    var draftText by remember(action.id, action.text) { mutableStateOf(action.text) }
    var draftOwner by remember(action.id, action.owner) { mutableStateOf(action.owner ?: "") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { showSheet = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Ícone de status
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = statusColor(action.status, MaterialTheme.colorScheme),
                modifier = Modifier.size(20.dp).padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    action.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Chip de status
                    Surface(
                        color = statusColor(action.status, MaterialTheme.colorScheme).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            statusLabel(action.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(action.status, MaterialTheme.colorScheme),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    // Chip de vencimento
                    if (action.dueDate != null) {
                        val chipColor = when {
                            done    -> Color.White.copy(alpha = 0.06f)
                            overdue -> MaterialTheme.colorScheme.errorContainer
                            today   -> MaterialTheme.colorScheme.primaryContainer
                            else    -> Color.White.copy(alpha = 0.06f)
                        }
                        val textColor = when {
                            done    -> MaterialTheme.colorScheme.onSurfaceVariant
                            overdue -> MaterialTheme.colorScheme.onErrorContainer
                            today   -> MaterialTheme.colorScheme.onPrimaryContainer
                            else    -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(color = chipColor, shape = RoundedCornerShape(6.dp)) {
                            Text(dueDateLabel(action.dueDate),
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (action.meetingTitle != null) {
                        Surface(color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(6.dp), onClick = onOpenMeeting) {
                            Text(action.meetingTitle, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                if (action.owner != null) {
                    Text("→ ${action.owner}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                if (editing) {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        label = { Text("Ação") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draftOwner,
                        onValueChange = { draftOwner = it },
                        label = { Text("Responsável") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            draftText = action.text
                            draftOwner = action.owner ?: ""
                            editing = false
                        }) { Text("Cancelar") }
                        TextButton(
                            onClick = {
                                onEdit?.invoke(draftText, draftOwner.ifBlank { null })
                                editing = false
                            },
                            enabled = draftText.isNotBlank(),
                        ) { Text("Salvar") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            action.text,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (onEdit != null) {
                            TextButton(onClick = { editing = true }) { Text("Editar") }
                        }
                    }
                }
                Text("STATUS", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp))
                statusOptions.forEach { status ->
                    val selected = action.status == status
                    Surface(
                        color = if (selected) statusColor(status, MaterialTheme.colorScheme).copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clickable {
                                onStatusChange(status)
                                showSheet = false
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) statusColor(status, MaterialTheme.colorScheme)
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(statusLabel(status),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) statusColor(status, MaterialTheme.colorScheme)
                                        else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}
