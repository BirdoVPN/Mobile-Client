package app.birdo.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.utils.CountryCoords
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A stylised orthographic-projection world "globe" rendered with Compose Canvas.
 * Servers are plotted as glowing dots over a grid of meridians/parallels and
 * the currently selected server pulses softly. The whole sphere rotates slowly
 * to give a sense of depth — a compromise between the WebGL globe in the web
 * landing page (cobe.js) and what is achievable in pure Compose without GPU
 * shaders or external dependencies.
 */
@Composable
fun WorldGlobe(
    servers: List<VpnServer>,
    selectedServerId: String?,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    autoRotate: Boolean = true,
) {
    val palette = BirdoColors.current
    val transition = rememberInfiniteTransition(label = "globe")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    val effectiveRotation = if (autoRotate) rotation else 0f

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (minOf(size.width, size.height) / 2f) * 0.92f

        // 1. Soft halo behind globe
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.accent.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius * 1.4f,
            ),
            radius = radius * 1.4f,
            center = Offset(cx, cy),
        )

        // 2. Sphere body (water)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.mapWater,
                    palette.mapWater.copy(alpha = if (palette.isLight) 0.7f else 0.85f),
                ),
                center = Offset(cx - radius * 0.25f, cy - radius * 0.25f),
                radius = radius * 1.25f,
            ),
            radius = radius,
            center = Offset(cx, cy),
        )

        // 3. Meridians & parallels (project a sparse grid of lat/lng dots)
        drawGraticule(
            cx = cx,
            cy = cy,
            radius = radius,
            rotation = effectiveRotation,
            color = palette.mapLand,
        )

        // 4. Subtle rim highlight
        drawCircle(
            color = palette.hairline,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.2f),
        )

        // 5. Server dots
        servers.forEach { server ->
            val ll = CountryCoords.forCountry(server.countryCode) ?: return@forEach
            val proj = projectOrtho(ll.first, ll.second, effectiveRotation, radius)
            if (!proj.visible) return@forEach
            val pos = Offset(cx + proj.x, cy + proj.y)
            val isSelected = server.id == selectedServerId
            val baseColor = if (isSelected) palette.accent else palette.mapDot

            if (isSelected) {
                // Pulsing ring
                val ringRadius = 6f + pulse * 18f
                drawCircle(
                    color = baseColor.copy(alpha = (1f - pulse) * 0.45f),
                    radius = ringRadius,
                    center = pos,
                )
            }
            drawCircle(
                color = baseColor.copy(alpha = if (isSelected) 1f else 0.85f),
                radius = if (isSelected) 4.5f else 2.8f,
                center = pos,
            )
        }

        // 6. Connection arc — from globe centre projection of "device" (~UK)
        //    to the selected server, drawn as a glowing chord.
        if (isConnected && selectedServerId != null) {
            val sel = servers.firstOrNull { it.id == selectedServerId } ?: return@Canvas
            val ll = CountryCoords.forCountry(sel.countryCode) ?: return@Canvas
            val to = projectOrtho(ll.first, ll.second, effectiveRotation, radius)
            val from = projectOrtho(51.51, -0.13, effectiveRotation, radius) // London-ish "you"
            if (to.visible && from.visible) {
                val a = Offset(cx + from.x, cy + from.y)
                val b = Offset(cx + to.x, cy + to.y)
                drawArcChord(a, b, palette.accent)
            }
        }
    }
}

/** Result of an orthographic projection of a (lat, lon) onto a unit sphere. */
private data class Projection(val x: Float, val y: Float, val visible: Boolean)

private fun projectOrtho(
    latDeg: Double,
    lonDeg: Double,
    rotationRadians: Float,
    radius: Float,
): Projection {
    val lat = latDeg * PI / 180.0
    val lon = lonDeg * PI / 180.0 + rotationRadians
    val x = (cos(lat) * sin(lon)) * radius
    val y = (-sin(lat)) * radius
    val z = cos(lat) * cos(lon)
    return Projection(x.toFloat(), y.toFloat(), z >= 0)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraticule(
    cx: Float,
    cy: Float,
    radius: Float,
    rotation: Float,
    color: Color,
) {
    // Parallels (lat lines) — every 20°
    for (lat in -80..80 step 20) {
        var lon = -180.0
        while (lon < 180.0) {
            val p = projectOrtho(lat.toDouble(), lon, rotation, radius)
            if (p.visible) {
                drawCircle(
                    color = color,
                    radius = 1.0f,
                    center = Offset(cx + p.x, cy + p.y),
                )
            }
            lon += 6.0
        }
    }
    // Meridians (lon lines) — every 30°
    for (lonInt in -180..180 step 30) {
        var lat = -85.0
        while (lat < 85.0) {
            val p = projectOrtho(lat, lonInt.toDouble(), rotation, radius)
            if (p.visible) {
                drawCircle(
                    color = color,
                    radius = 1.0f,
                    center = Offset(cx + p.x, cy + p.y),
                )
            }
            lat += 4.0
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArcChord(
    a: Offset,
    b: Offset,
    color: Color,
) {
    // Quadratic curve bulging away from globe centre to suggest a great circle.
    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val centre = Offset(size.width / 2f, size.height / 2f)
    val dir = Offset(mid.x - centre.x, mid.y - centre.y)
    val len = kotlin.math.sqrt((dir.x * dir.x + dir.y * dir.y).toDouble()).toFloat().coerceAtLeast(1f)
    val lift = 28f
    val ctrl = Offset(mid.x + dir.x / len * lift, mid.y + dir.y / len * lift)

    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(a.x, a.y)
        quadraticTo(ctrl.x, ctrl.y, b.x, b.y)
    }
    // Glow underlay
    drawPath(
        path = path,
        color = color.copy(alpha = 0.22f),
        style = Stroke(width = 6f),
    )
    drawPath(
        path = path,
        color = color.copy(alpha = 0.95f),
        style = Stroke(width = 1.6f),
    )
    drawCircle(color = color, radius = 3.5f, center = b)
}

/** Returns just the size of a globe given a measured surface — handy for layout. */
fun globeRadius(size: Size): Float =
    (minOf(size.width, size.height) / 2f) * 0.92f
