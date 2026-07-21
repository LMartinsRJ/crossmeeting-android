package ai.crossmeeting.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ai.crossmeeting.app.MainActivity
import ai.crossmeeting.app.MeetingRow
import ai.crossmeeting.app.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val KEY_TITLE = stringPreferencesKey("last_meeting_title")
private val KEY_DATE  = stringPreferencesKey("last_meeting_date")

private val BG_COLOR   = androidx.compose.ui.graphics.Color(0xFF1A1D23)
private val ACCENT     = androidx.compose.ui.graphics.Color(0xFF6C8EFF)
private val TEXT_MAIN  = androidx.compose.ui.graphics.Color.White
private val TEXT_SUB   = androidx.compose.ui.graphics.Color(0xFF6B7280)

class MeetingWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val title = prefs[KEY_TITLE] ?: "Nenhuma reunião"
            val date  = prefs[KEY_DATE] ?: ""
            WidgetContent(title, date)
        }
    }

    @Composable
    private fun WidgetContent(title: String, date: String) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(BG_COLOR))
                .clickable(actionStartActivity<MainActivity>())
                .padding(14.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            Column {
                Text(
                    text = "CROSSMEETING",
                    style = TextStyle(
                        color = ColorProvider(ACCENT),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    text = title,
                    style = TextStyle(
                        color = ColorProvider(TEXT_MAIN),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 2,
                )
                if (date.isNotEmpty()) {
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = date,
                        style = TextStyle(
                            color = ColorProvider(TEXT_SUB),
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
        }
    }
}

class MeetingWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget = MeetingWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            refreshWidgetState(context)
        }
    }
}

suspend fun refreshWidgetState(context: Context) {
    val latest = runCatching {
        SupabaseClientProvider.client.postgrest
            .from("meetings")
            .select()
            .decodeList<MeetingRow>()
            .filter { it.deletedAt == null }
            .maxByOrNull { it.createdAt }
    }.getOrNull()

    val title = latest?.title ?: "Nenhuma reunião"
    val date  = latest?.createdAt?.let { formatWidgetDate(it) } ?: ""

    val manager = GlanceAppWidgetManager(context)
    val ids = manager.getGlanceIds(MeetingWidget::class.java)
    val widget = MeetingWidget()
    for (id in ids) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            prefs.toMutablePreferences().apply {
                this[KEY_TITLE] = title
                this[KEY_DATE]  = date
            }
        }
        widget.update(context, id)
    }
}

private fun formatWidgetDate(iso: String): String = runCatching {
    val instant = Instant.parse(iso)
    val zdt = instant.atZone(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale("pt", "BR")).format(zdt)
}.getOrDefault("")
