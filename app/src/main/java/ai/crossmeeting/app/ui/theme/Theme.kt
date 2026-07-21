package ai.crossmeeting.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CrossmeetingDarkScheme = darkColorScheme(
    primary = CmWave,
    onPrimary = Color.White,
    secondary = CmBlue,
    background = CmSurface,
    onBackground = CmCream,
    surface = CmSurface2,
    onSurface = CmCream,
    surfaceVariant = CmSurface2,
    onSurfaceVariant = CmCream.copy(alpha = 0.7f),
    error = Color(0xFFFF6B6B),
)

@Composable
fun CrossmeetingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Mesmo padrão visual do desktop/web: sempre escuro, independente do tema do sistema
    MaterialTheme(
        colorScheme = CrossmeetingDarkScheme,
        typography = CrossmeetingTypography,
        content = content,
    )
}
