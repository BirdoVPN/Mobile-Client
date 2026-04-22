package app.birdo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.birdo.vpn.ui.theme.BirdoBrand

/**
 * Aurora-style ambient background. Soft radial blooms in brand colors painted
 * over a deep surface. Designed to sit behind PixelCanvas for added depth on
 * key screens (login, home).
 *
 * @param accent Optional override Brush for the bloom (defaults to a purple
 *  bloom). Pass `BirdoBrand.ConnectedGradient` etc. for state-driven moods.
 */
@Composable
fun BirdoAuroraBackground(
    modifier: Modifier = Modifier,
    accent: Brush? = null,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(BirdoBrand.Surface0),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Top-left purple bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(BirdoBrand.Purple.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(w * 0.15f, h * 0.10f),
                    radius = w * 0.85f,
                ),
                radius = w * 0.85f,
                center = Offset(w * 0.15f, h * 0.10f),
            )
            // Bottom-right pink bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(BirdoBrand.Pink.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(w * 0.95f, h * 0.95f),
                    radius = w * 0.7f,
                ),
                radius = w * 0.7f,
                center = Offset(w * 0.95f, h * 0.95f),
            )
            // Mid-left teal bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(BirdoBrand.Teal.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(w * -0.1f, h * 0.6f),
                    radius = w * 0.6f,
                ),
                radius = w * 0.6f,
                center = Offset(w * -0.1f, h * 0.6f),
            )
        }
        if (accent != null) {
            Box(Modifier.fillMaxSize().background(accent))
        }
    }
}
