package ai.crossmeeting.app.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmBlue
import ai.crossmeeting.app.ui.theme.CmWave
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Azure
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Logo + nome
        ai.crossmeeting.app.ui.theme.AppLogo(modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            buildAnnotatedString {
                append("CROSS")
                withStyle(SpanStyle(color = CmWave)) { append("MEETING") }
            },
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Grave, transcreva e organize suas reuniões.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(56.dp))

        // Google — solicita calendário no mesmo OAuth
        Button(
            onClick = {
                error = null; loading = true
                scope.launch {
                    runCatching {
                        SupabaseClientProvider.client.auth.signInWith(Google) {
                            // Solicita acesso ao calendário junto com o login.
                            // O Supabase devolverá providerRefreshToken na sessão.
                            scopes.add("https://www.googleapis.com/auth/calendar.readonly")
                            queryParams["access_type"] = "offline"
                            queryParams["prompt"] = "consent"
                        }
                    }.onFailure { error = it.message }
                    loading = false
                }
            },
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(containerColor = CmBlue, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Entrar com Google", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Microsoft — solicita calendário junto com o login
        OutlinedButton(
            onClick = {
                error = null; loading = true
                scope.launch {
                    runCatching {
                        SupabaseClientProvider.client.auth.signInWith(Azure) {
                            // Calendars.Read + offline_access para ter refresh token
                            scopes.add("Calendars.Read")
                            scopes.add("offline_access")
                        }
                    }.onFailure { error = it.message }
                    loading = false
                }
            },
            enabled = !loading,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Entrar com Microsoft", style = MaterialTheme.typography.labelLarge)
        }

        if (loading) {
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = CmWave)
        }
        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
