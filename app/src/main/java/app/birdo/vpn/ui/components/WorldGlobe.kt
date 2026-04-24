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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.utils.CountryCoords
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stylised orthographic-projection world globe with REAL continent silhouettes.
 *
 * Pre-computes the visible disc's per-pixel (px, py, latDeg, lonRel) grid ONCE
 * per radius via [remember]. Per frame, just translates each lonRel by the
 * current rotation, looks it up in [WorldLandmask], and batches all land
 * pixels into a single [DrawScope.drawPoints] call. Dramatically faster than
 * the old per-pixel `drawCircle` loop — holds 60 fps on mid-range phones.
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
            // Slower (120s) — the user said it felt "too much"; gentle drift.
            animation = tween(durationMillis = 120_000, easing = LinearEasing),
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

    // Pre-compute server coordinates once per server list change.
    val serverPoints = remember(servers) {
        servers.mapNotNull { s ->
            CountryCoords.forCountry(s.countryCode)?.let { ll -> Triple(s.id, ll.first, ll.second) }
        }
    }

    // Pre-compute disc grid per radius (rebuilt only when radius changes).
    var cachedRadius by remember { androidx.compose.runtime.mutableStateOf(0f) }
    var discGrid by remember { androidx.compose.runtime.mutableStateOf(FloatArray(0)) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (minOf(size.width, size.height) / 2f) * 0.92f

        if (kotlin.math.abs(cachedRadius - radius) > 0.5f || discGrid.isEmpty()) {
            discGrid = buildDiscGrid(radius, step = 5f)
            cachedRadius = radius
        }
        val grid = discGrid

        // 1. Soft halo behind globe
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.accent.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius * 1.5f,
            ),
            radius = radius * 1.5f,
            center = Offset(cx, cy),
        )

        // 2. Sphere body (water) with depth gradient
        val waterDark = palette.mapWater
        val waterLight = if (palette.isLight) palette.mapWater.copy(alpha = 0.55f)
                         else palette.mapWater.copy(alpha = 0.85f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(waterLight, waterDark),
                center = Offset(cx - radius * 0.30f, cy - radius * 0.30f),
                radius = radius * 1.30f,
            ),
            radius = radius,
            center = Offset(cx, cy),
        )

        // 3. Continent rasterisation — single batched drawPoints call.
        //    grid layout: [px, py, latDeg, lonRel, ...]
        val landFill = if (palette.isLight) Color(0xFF4C1D95).copy(alpha = 0.85f)
                       else Color(0xFFC4B5FD).copy(alpha = 0.75f)
        val rotDeg = (effectiveRotation * 180f / PI.toFloat())
        val landPoints = ArrayList<Offset>(grid.size / 4)
        var i = 0
        while (i < grid.size) {
            val px = grid[i]
            val py = grid[i + 1]
            val lat = grid[i + 2]
            val lonRel = grid[i + 3]
            var lon = lonRel - rotDeg
            lon = ((lon % 360f) + 540f) % 360f - 180f
            if (WorldLandmask.isLand(lat.toDouble(), lon.toDouble())) {
                landPoints.add(Offset(cx + px, cy + py))
            }
            i += 4
        }
        if (landPoints.isNotEmpty()) {
            drawPoints(
                points = landPoints,
                pointMode = PointMode.Points,
                color = landFill,
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }

        // 3b. Single radial vignette overlay for limb darkening.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Transparent,
                    Color.Black.copy(alpha = if (palette.isLight) 0.18f else 0.30f),
                ),
                center = Offset(cx, cy),
                radius = radius,
            ),
            radius = radius,
            center = Offset(cx, cy),
        )

        // 4. Subtle rim highlight
        drawCircle(
            color = palette.hairline,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.2f),
        )

        // 5. Server dots
        serverPoints.forEach { (id, lat, lon) ->
            val proj = projectOrtho(lat, lon, effectiveRotation, radius)
            if (!proj.visible) return@forEach
            val pos = Offset(cx + proj.x, cy + proj.y)
            val isSelected = id == selectedServerId
            val baseColor = if (isSelected) palette.accent else palette.mapDot

            if (isSelected) {
                val ringRadius = 6f + pulse * 18f
                drawCircle(
                    color = baseColor.copy(alpha = (1f - pulse) * 0.45f),
                    radius = ringRadius,
                    center = pos,
                )
            }
            drawCircle(
                color = baseColor.copy(alpha = if (isSelected) 1f else 0.85f),
                radius = if (isSelected) 4.5f else 2.6f,
                center = pos,
            )
        }

        // 6. Connection arc — from device (~UK) to selected server
        if (isConnected && selectedServerId != null) {
            val sel = serverPoints.firstOrNull { it.first == selectedServerId } ?: return@Canvas
            val to = projectOrtho(sel.second, sel.third, effectiveRotation, radius)
            val from = projectOrtho(51.51, -0.13, effectiveRotation, radius)
            if (to.visible && from.visible) {
                val a = Offset(cx + from.x, cy + from.y)
                val b = Offset(cx + to.x, cy + to.y)
                drawArcChord(a, b, palette.accent)
            }
        }

        // 7. Faint specular highlight upper-left
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (palette.isLight) 0.10f else 0.06f),
                    Color.Transparent,
                ),
                center = Offset(cx - radius * 0.45f, cy - radius * 0.55f),
                radius = radius * 0.7f,
            ),
            radius = radius * 0.7f,
            center = Offset(cx - radius * 0.45f, cy - radius * 0.55f),
        )
    }
}

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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArcChord(
    a: Offset,
    b: Offset,
    color: Color,
) {
    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val centre = Offset(size.width / 2f, size.height / 2f)
    val dir = Offset(mid.x - centre.x, mid.y - centre.y)
    val len = sqrt((dir.x * dir.x + dir.y * dir.y).toDouble()).toFloat().coerceAtLeast(1f)
    val lift = 28f
    val ctrl = Offset(mid.x + dir.x / len * lift, mid.y + dir.y / len * lift)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(a.x, a.y)
        quadraticTo(ctrl.x, ctrl.y, b.x, b.y)
    }
    drawPath(path = path, color = color.copy(alpha = 0.22f), style = Stroke(width = 6f))
    drawPath(path = path, color = color.copy(alpha = 0.95f), style = Stroke(width = 1.6f))
    drawCircle(color = color, radius = 3.5f, center = b)
}

fun globeRadius(size: Size): Float =
    (minOf(size.width, size.height) / 2f) * 0.92f

/**
 * Pre-computes the disc grid: for every sample point inside the visible
 * hemisphere, packs (px, py, latDeg, lonRel) into a single FloatArray.
 */
private fun buildDiscGrid(radius: Float, step: Float): FloatArray {
    if (radius <= 0f) return FloatArray(0)
    val r2 = radius * radius
    val out = ArrayList<Float>()
    var py = -radius
    while (py <= radius) {
        var px = -radius
        while (px <= radius) {
            val d2 = px * px + py * py
            if (d2 <= r2) {
                val nx = (px / radius)
                val ny = (py / radius)
                val nz = sqrt((1f - nx * nx - ny * ny).coerceAtLeast(0f))
                val lat = (-asin(ny.toDouble()) * 180.0 / PI).toFloat()
                val lonRel = (atan2(nx.toDouble(), nz.toDouble()) * 180.0 / PI).toFloat()
                out.add(px); out.add(py); out.add(lat); out.add(lonRel)
            }
            px += step
        }
        py += step
    }
    return out.toFloatArray()
}
