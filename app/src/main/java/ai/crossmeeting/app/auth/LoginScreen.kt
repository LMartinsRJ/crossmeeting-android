package ai.crossmeeting.app.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.crossmeeting.app.SupabaseClientProvider
import ai.crossmeeting.app.ui.theme.CmBlue
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Azure
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.launch

/**
 * Login via Supabase Auth (mesmo provider já configurado e usado pelo crossmeeting-web),
 * abrindo um Custom Tab e retornando pelo deep link `crossmeeting://login-callback`.
 * Evita precisar de um client OAuth Android nativo separado com SHA-1 no Google Cloud Console.
 */
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
        Text(
            "Crossmeeting",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                error = null
                loading = true
                scope.launch {
                    runCatching {
                        SupabaseClientProvider.client.auth.signInWith(Google)
                    }.onFailure { error = it.message }
                    loading = false
                }
            },
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(containerColor = CmBlue, contentColor = Color.White),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("Entrar com Google")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                error = null
                loading = true
                scope.launch {
                    runCatching {
                        SupabaseClientProvider.client.auth.signInWith(Azure)
                    }.onFailure { error = it.message }
                    loading = false
                }
            },
            enabled = !loading,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("Entrar com Microsoft")
        }

        if (loading) {
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = CmBlue)
        }
        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
