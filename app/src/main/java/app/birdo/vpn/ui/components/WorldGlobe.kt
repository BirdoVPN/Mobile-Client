package app.birdo.vpn.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
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
 * v1.3.2 — higher detail (3px sample step), latitude/longitude graticule for
 * a more cartographic feel, and Mullvad-style "zoom into the connection" on
 * connect: the camera longitude animates so the selected server is centered,
 * the globe scales up, and the connection arc draws in progressively.
 *
 * Pre-computes the visible disc's per-pixel (px, py, latDeg, lonRel) grid ONCE
 * per radius via [remember]. Per frame, just translates each lonRel by the
 * current rotation, looks it up in [WorldLandmask], and batches all land
 * pixels into a single [DrawScope.drawPoints] call.
 */
@Composable
fun WorldGlobe(
    servers: List<VpnServer>,
    selectedServerId: String?,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    autoRotate: Boolean = true,
    userLat: Double = 51.51,
    userLon: Double = -0.13,
) {
    val palette = BirdoColors.current
    val transition = rememberInfiniteTransition(label = "globe")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            // Slower (120s) — gentle drift while idle.
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

    // Pre-compute server coordinates once per server list change.
    val serverPoints = remember(servers) {
        servers.mapNotNull { s ->
            CountryCoords.forCountry(s.countryCode)?.let { ll -> Triple(s.id, ll.first, ll.second) }
        }
    }

    // ── Connect-zoom animation ────────────────────────────────────────────
    // When connected with a known server, animate the camera so the midpoint
    // between the user and the server sits on the centre meridian, AND scale
    // the whole globe up slightly (Mullvad-style focus).
    val selectedCoord = remember(selectedServerId, serverPoints) {
        serverPoints.firstOrNull { it.first == selectedServerId }?.let { it.second to it.third }
    }
    val zoomActive = isConnected && selectedCoord != null
    val targetLonDeg: Float = if (zoomActive) {
        val midLon = midpointLon(userLon.toFloat(), selectedCoord!!.second.toFloat())
        -midLon
    } else 0f
    // Convert idle rotation (radians) to degrees and unwrap towards target so
    // the transition is the short way around.
    val idleRotDeg = (rotation * 180f / PI.toFloat())
    val animatedFocusRotDeg by animateFloatAsState(
        targetValue = targetLonDeg,
        animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
        label = "focusRot",
    )
    val focusScale by animateFloatAsState(
        targetValue = if (zoomActive) 1.18f else 1f,
        animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
        label = "focusScale",
    )
    val arcProgress by animateFloatAsState(
        targetValue = if (zoomActive) 1f else 0f,
        animationSpec = tween(durationMillis = 1100, delayMillis = if (zoomActive) 600 else 0),
        label = "arcProgress",
    )

    val effectiveRotDeg = when {
        zoomActive -> animatedFocusRotDeg
        autoRotate -> idleRotDeg
        else -> 0f
    }
    val effectiveRotation = effectiveRotDeg * (PI.toFloat() / 180f)

    // Pre-compute disc grid per radius (rebuilt only when radius changes).
    var cachedRadius by remember { androidx.compose.runtime.mutableStateOf(0f) }
    var discGrid by remember { androidx.compose.runtime.mutableStateOf(FloatArray(0)) }

    Canvas(modifier = modifier.fillMaxSize().scale(focusScale)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (minOf(size.width, size.height) / 2f) * 0.92f

        if (kotlin.math.abs(cachedRadius - radius) > 0.5f || discGrid.isEmpty()) {
            // step 3f — ~2.7× more samples than v1.3.1 for crisper continents.
            // Still O(n²) over the disc, but drawPoints batches the result so
            // the per-frame cost is the lookup + alloc only.
            discGrid = buildDiscGrid(radius, step = 3f)
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

        // 2b. Graticule — latitude (parallels) every 30°, longitude (meridians)
        //     every 30°. Drawn before continents so land sits on top.
        val graticuleColor = palette.hairline.copy(alpha = if (palette.isLight) 0.18f else 0.14f)
        drawGraticule(cx, cy, radius, effectiveRotation, graticuleColor)

        // 3. Continent rasterisation — single batched drawPoints call.
        //    grid layout: [px, py, latDeg, lonRel, ...]
        val landFill = if (palette.isLight) Color(0xFF4C1D95).copy(alpha = 0.88f)
                       else Color(0xFFC4B5FD).copy(alpha = 0.80f)
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
                strokeWidth = 2.2f,
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

        // 6. Connection arc + endpoints — from user location to selected server,
        //    drawn progressively (Mullvad-style) once connected.
        if (zoomActive && selectedCoord != null) {
            val to = projectOrtho(selectedCoord.first, selectedCoord.second, effectiveRotation, radius)
            val from = projectOrtho(userLat, userLon, effectiveRotation, radius)
            if (to.visible && from.visible) {
                val a = Offset(cx + from.x, cy + from.y)
                val b = Offset(cx + to.x, cy + to.y)
                drawArcChord(a, b, palette.accent, progress = arcProgress)
                // User pin (smaller, white core)
                drawCircle(color = palette.accent.copy(alpha = 0.30f), radius = 9f, center = a)
                drawCircle(color = Color.White, radius = 3.2f, center = a)
                drawCircle(color = palette.accent, radius = 1.6f, center = a)
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
    progress: Float = 1f,
) {
    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val centre = Offset(size.width / 2f, size.height / 2f)
    val dir = Offset(mid.x - centre.x, mid.y - centre.y)
    val len = sqrt((dir.x * dir.x + dir.y * dir.y).toDouble()).toFloat().coerceAtLeast(1f)
    val chord = sqrt(((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).toDouble()).toFloat()
    val lift = (chord * 0.22f).coerceIn(18f, 80f)
    val ctrl = Offset(mid.x + dir.x / len * lift, mid.y + dir.y / len * lift)
    val full = Path().apply {
        moveTo(a.x, a.y)
        quadraticTo(ctrl.x, ctrl.y, b.x, b.y)
    }
    val p = progress.coerceIn(0f, 1f)
    val drawn = if (p >= 1f) full else Path().also { dst ->
        val measure = PathMeasure().apply { setPath(full, false) }
        measure.getSegment(0f, measure.length * p, dst, true)
    }
    // Outer glow (always full when any progress) and the crisp inner stroke.
    drawPath(path = full, color = color.copy(alpha = 0.18f * p), style = Stroke(width = 7f))
    drawPath(path = drawn, color = color.copy(alpha = 0.95f), style = Stroke(width = 2.0f, cap = StrokeCap.Round))
    if (p >= 0.98f) {
        // Server endpoint pulse — only after the line completes.
        drawCircle(color = color.copy(alpha = 0.30f), radius = 10f, center = b)
        drawCircle(color = Color.White, radius = 3.6f, center = b)
        drawCircle(color = color, radius = 1.8f, center = b)
    }
}

/** Returns the midpoint longitude (in degrees) on the shorter arc. */
private fun midpointLon(a: Float, b: Float): Float {
    var d = b - a
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    var mid = a + d / 2f
    while (mid > 180f) mid -= 360f
    while (mid < -180f) mid += 360f
    return mid
}

/** Latitude / longitude graticule lines (every 30°). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraticule(
    cx: Float,
    cy: Float,
    radius: Float,
    rotation: Float,
    color: Color,
) {
    val stroke = Stroke(width = 0.8f)
    // Meridians — lines of constant longitude, every 30°.
    var lon = -180
    while (lon < 180) {
        val path = Path()
        var first = true
        var lat = -90
        while (lat <= 90) {
            val proj = projectOrtho(lat.toDouble(), lon.toDouble(), rotation, radius)
            if (proj.visible) {
                val x = cx + proj.x; val y = cy + proj.y
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            } else {
                first = true
            }
            lat += 3
        }
        drawPath(path, color, style = stroke)
        lon += 30
    }
    // Parallels — lines of constant latitude, every 30° (skip poles).
    var plat = -60
    while (plat <= 60) {
        val path = Path()
        var first = true
        var plon = -180
        while (plon <= 180) {
            val proj = projectOrtho(plat.toDouble(), plon.toDouble(), rotation, radius)
            if (proj.visible) {
                val x = cx + proj.x; val y = cy + proj.y
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            } else {
                first = true
            }
            plon += 3
        }
        drawPath(path, color, style = stroke)
        plat += 30
    }
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
