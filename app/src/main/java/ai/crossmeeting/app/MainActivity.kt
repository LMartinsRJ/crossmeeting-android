package ai.crossmeeting.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import ai.crossmeeting.app.auth.LoginScreen
import ai.crossmeeting.app.chat.ChatScreen
import ai.crossmeeting.app.home.*
import ai.crossmeeting.app.recording.RecordingScreen
import ai.crossmeeting.app.ui.theme.CrossmeetingTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus

enum class AppTab { HOME, MEETINGS, ACTIONS, BRIEFING }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        SupabaseClientProvider.client.handleDeeplinks(intent)

        setContent {
            CrossmeetingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val sessionStatus by SupabaseClientProvider.client.auth.sessionStatus.collectAsState()
                    var recording by remember { mutableStateOf(false) }
                    var chatting by remember { mutableStateOf(false) }
                    var openMeetingId by remember { mutableStateOf<Long?>(null) }
                    var openSpaceId by remember { mutableStateOf<Long?>(null) }
                    var currentTab by remember { mutableStateOf(AppTab.HOME) }

                    when {
                        sessionStatus !is SessionStatus.Authenticated -> LoginScreen()

                        recording -> RecordingScreen(
                            onSaved = { meetingId ->
                                recording = false
                                openMeetingId = meetingId
                            },
                            onDiscarded = { recording = false },
                        )

                        chatting -> ChatScreen(onBack = { chatting = false })

                        openMeetingId != null -> MeetingDetailScreen(
                            meetingId = openMeetingId!!,
                            onBack = { openMeetingId = null },
                        )

                        openSpaceId != null -> SpaceDetailScreen(
                            spaceId = openSpaceId!!,
                            onBack = { openSpaceId = null },
                            onOpenMeeting = { openMeetingId = it },
                        )

                        else -> Scaffold(
                            bottomBar = {
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    NavigationBarItem(
                                        selected = currentTab == AppTab.HOME,
                                        onClick = { currentTab = AppTab.HOME },
                                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                        label = { Text("Início") },
                                    )
                                    NavigationBarItem(
                                        selected = currentTab == AppTab.MEETINGS,
                                        onClick = { currentTab = AppTab.MEETINGS },
                                        icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
                                        label = { Text("Reuniões") },
                                    )
                                    NavigationBarItem(
                                        selected = currentTab == AppTab.ACTIONS,
                                        onClick = { currentTab = AppTab.ACTIONS },
                                        icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                                        label = { Text("Ações") },
                                    )
                                    NavigationBarItem(
                                        selected = currentTab == AppTab.BRIEFING,
                                        onClick = { currentTab = AppTab.BRIEFING },
                                        icon = { Icon(Icons.Filled.CalendarToday, contentDescription = null) },
                                        label = { Text("Briefing") },
                                    )
                                }
                            },
                        ) { innerPadding ->
                            when (currentTab) {
                                AppTab.HOME -> HomeScreen(
                                    modifier = Modifier.padding(innerPadding),
                                    onStartRecording = { recording = true },
                                    onOpenChat = { chatting = true },
                                    onOpenMeeting = { openMeetingId = it },
                                    onOpenSpace = { openSpaceId = it },
                                )
                                AppTab.MEETINGS -> AllMeetingsScreen(
                                    modifier = Modifier.padding(innerPadding),
                                    onOpenMeeting = { openMeetingId = it },
                                    onOpenSpace = { openSpaceId = it },
                                )
                                AppTab.ACTIONS -> ActionsScreen(
                                    modifier = Modifier.padding(innerPadding),
                                    onOpenMeeting = { openMeetingId = it },
                                )
                                AppTab.BRIEFING -> BriefingScreen(
                                    modifier = Modifier.padding(innerPadding),
                                    onOpenMeeting = { openMeetingId = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        SupabaseClientProvider.client.handleDeeplinks(intent)
    }
}
