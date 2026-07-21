package ai.crossmeeting.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * Réplica exata do logo do desktop (src/renderer/components/Sidebar.tsx, viewBox 18x20):
 * 5 barras arredondadas apoiadas na base, formando um arco (sobe e desce, tipo onda
 * sonora) — NÃO é uma sequência só crescente. Todas na mesma cor [CmWave], a última mais
 * fina e com opacidade reduzida (efeito de "esmaecer no fim" do desenho original).
 */
@Composable
fun AppLogo(modifier: Modifier = Modifier.size(18.dp, 20.dp)) {
    Canvas(modifier = modifier) {
        val scaleX = size.width / 18f
        val scaleY = size.height / 20f

        // (x, y, largura, altura) no espaço do viewBox original 18x20
        val bars = listOf(
            BarSpec(0f, 10f, 3f, 10f),
            BarSpec(4f, 4f, 3f, 16f),
            BarSpec(8f, 0f, 3f, 20f),
            BarSpec(12f, 6f, 3f, 14f),
        )
        val fadedBar = BarSpec(16f, 12f, 2f, 8f)

        bars.forEach { bar -> drawBar(bar, scaleX, scaleY, CmWave) }
        drawBar(fadedBar, scaleX, scaleY, CmWave.copy(alpha = 0.6f))
    }
}

private data class BarSpec(val x: Float, val y: Float, val width: Float, val height: Float)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
    bar: BarSpec,
    scaleX: Float,
    scaleY: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    val width = bar.width * scaleX
    val cornerRadius = (bar.width / 2f) * scaleX
    drawRoundRect(
        color = color,
        topLeft = Offset(bar.x * scaleX, bar.y * scaleY),
        size = Size(width, bar.height * scaleY),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
    )
}
