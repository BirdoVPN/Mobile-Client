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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.utils.CountryCoords
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 2D world map (formerly WorldGlobe) — flat equirectangular projection.
 *
 * v1.3.9 — replaces the 3D orthographic globe with a lightweight 2D map.
 * The previous globe was visually striking but proved too expensive on
 * mid-range Android devices and the constant rotation made it hard to
 * read. This version draws the same Natural-Earth-derived 720×360 land
 * mask as filled tiles in equirectangular space, which is dramatically
 * cheaper (no per-frame trig), instantly readable, and frames the
 * connection between user and selected server without any rotation.
 *
 * Public composable name is kept as [WorldGlobe] so callers do not need
 * to change.
 */
@Composable
fun WorldGlobe(
    servers: List<VpnServer>,
    selectedServerId: String?,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") autoRotate: Boolean = true,
    userLat: Double = 51.51,
    userLon: Double = -0.13,
) {
    val palette = BirdoColors.current
    val transition = rememberInfiniteTransition(label = "map")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    val arcShimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "arcShimmer",
    )

    val serverPoints = remember(servers) {
        servers.mapNotNull { s ->
            CountryCoords.forCountry(s.countryCode)?.let { ll ->
                Triple(s.id, ll.first, ll.second)
            }
        }
    }
    val selectedCoord = remember(selectedServerId, serverPoints) {
        serverPoints.firstOrNull { it.first == selectedServerId }
            ?.let { it.second to it.third }
    }

    // Map crop: drop the polar caps so the world fills the frame nicely.
    val latMaxCrop = 72f
    val latMinCrop = -58f

    // When connected, fit the bbox of (user, selected) with padding.
    val zoomActive = isConnected && selectedCoord != null
    val targetLatC: Float
    val targetLonC: Float
    val targetSpan: Float
    if (zoomActive) {
        val sLat = selectedCoord!!.first.toFloat()
        val sLon = selectedCoord.second.toFloat()
        val uLat = userLat.toFloat()
        val uLon = userLon.toFloat()
        // Pick the shorter longitude path: optionally shift one endpoint by 360.
        val rawDLon = sLon - uLon
        val shiftedSLon = when {
            rawDLon > 180f -> sLon - 360f
            rawDLon < -180f -> sLon + 360f
            else -> sLon
        }
        targetLonC = (uLon + shiftedSLon) / 2f
        targetLatC = (uLat + sLat) / 2f
        val dLon = kotlin.math.abs(shiftedSLon - uLon)
        val dLat = kotlin.math.abs(sLat - uLat)
        // Aim to span the larger extent plus padding. dLat is doubled because
        // canvas tends to be portrait, so latitude needs more visual room.
        targetSpan = (max(dLon, dLat * 2f) + 35f).coerceIn(40f, 360f)
    } else {
        targetLatC = 8f          // slight northern bias so populated land sits centred
        targetLonC = 10f         // shift away from antimeridian
        targetSpan = 360f        // whole world
    }

    val animLatC by animateFloatAsState(
        targetValue = targetLatC,
        animationSpec = tween(1100, easing = FastOutSlowInEasing),
        label = "panLat",
    )
    val animLonC by animateFloatAsState(
        targetValue = targetLonC,
        animationSpec = tween(1100, easing = FastOutSlowInEasing),
        label = "panLon",
    )
    val animSpan by animateFloatAsState(
        targetValue = targetSpan,
        animationSpec = tween(1100, easing = FastOutSlowInEasing),
        label = "zoomSpan",
    )
    val arcProgress by animateFloatAsState(
        targetValue = if (zoomActive) 1f else 0f,
        animationSpec = tween(900, delayMillis = if (zoomActive) 400 else 0),
        label = "arcProgress",
    )

    val isLight = palette.isLight
    val landFill = if (isLight) Color(0xFF3B0764) else Color(0xFFB8A2EE)
    val landFillAlpha = 0.92f
    val oceanDeep = if (isLight) Color(0xFFCBD5E1) else Color(0xFF0B1226)
    val oceanShallow = if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E1B4B)
    val accent = palette.accent
    val gridColor = palette.hairline.copy(alpha = if (isLight) 0.18f else 0.10f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Ocean / map background.
        drawRect(
            brush = Brush.verticalGradient(listOf(oceanShallow, oceanDeep)),
            size = size,
        )

        // Compute the projection: figure out what (lat, lon) range maps to
        // the canvas given the current pan + zoom.
        val mapAspect = if (h > 0f) w / h else 1f
        val lonSpan = animSpan.coerceAtLeast(40f)
        // Latitude span follows canvas aspect (equirectangular: 1° lat = 1° lon at the equator).
        val latSpan = lonSpan / mapAspect
        val lonMin = animLonC - lonSpan / 2f
        val latTop = animLatC + latSpan / 2f

        fun project(lat: Float, lon: Float): Offset {
            var l = lon
            while (l - animLonC > 180f) l -= 360f
            while (l - animLonC < -180f) l += 360f
            val nx = (l - lonMin) / lonSpan
            val ny = (latTop - lat) / latSpan
            return Offset(nx * w, ny * h)
        }

        clipRect(0f, 0f, w, h) {
            // Subtle grid lines for spatial structure.
            run {
                var lat = -60
                while (lat <= 60) {
                    val y = ((latTop - lat) / latSpan) * h
                    if (y in 0f..h) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 0.7f,
                        )
                    }
                    lat += 30
                }
            }
            run {
                var lon = -150
                while (lon <= 150) {
                    var l = lon.toFloat()
                    while (l - animLonC > 180f) l -= 360f
                    while (l - animLonC < -180f) l += 360f
                    val x = ((l - lonMin) / lonSpan) * w
                    if (x in 0f..w) {
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 0.7f,
                        )
                    }
                    lon += 30
                }
            }

            // Land cells.
            val landPath = buildLandPath(
                w = w, h = h,
                lonMin = lonMin, lonSpan = lonSpan,
                latTop = latTop, latSpan = latSpan,
                latMaxCrop = latMaxCrop, latMinCrop = latMinCrop,
            )
            drawPath(landPath, color = landFill.copy(alpha = landFillAlpha))

            // Server dots.
            serverPoints.forEach { (id, lat, lon) ->
                val pos = project(lat.toFloat(), lon.toFloat())
                if (pos.x !in -8f..(w + 8f) || pos.y !in -8f..(h + 8f)) return@forEach
                val isSelected = id == selectedServerId
                val baseColor = if (isSelected) accent else palette.mapDot
                if (isSelected) {
                    val ringRadius = 6f + pulse * 22f
                    drawCircle(
                        color = baseColor.copy(alpha = (1f - pulse) * 0.50f),
                        radius = ringRadius,
                        center = pos,
                    )
                }
                drawCircle(
                    color = baseColor.copy(alpha = if (isSelected) 1f else 0.85f),
                    radius = if (isSelected) 5.5f else 3.0f,
                    center = pos,
                )
                if (isSelected) {
                    drawCircle(color = Color.White, radius = 2.4f, center = pos)
                }
            }

            // Connection arc + endpoints.
            if (zoomActive && selectedCoord != null) {
                val from = project(userLat.toFloat(), userLon.toFloat())
                val to = project(selectedCoord.first.toFloat(), selectedCoord.second.toFloat())
                drawConnectionArc(from, to, accent, arcProgress, arcShimmer)
                drawCircle(color = accent.copy(alpha = 0.30f), radius = 11f, center = from)
                drawCircle(color = Color.White, radius = 4.0f, center = from)
                drawCircle(color = accent, radius = 2.0f, center = from)
            }
        }

        // Outer hairline border for definition.
        drawRect(
            color = accent.copy(alpha = if (isLight) 0.30f else 0.35f),
            size = Size(w, h),
            style = Stroke(width = 1.0f),
        )
    }
}

/** Build a Path covering all visible land cells as small rectangles. */
private fun buildLandPath(
    w: Float,
    h: Float,
    lonMin: Float,
    lonSpan: Float,
    latTop: Float,
    latSpan: Float,
    latMaxCrop: Float,
    latMinCrop: Float,
): Path {
    val path = Path()
    val rows = WorldLandmask.rowCount()
    val cols = WorldLandmask.colCount()
    val cellLat = 180.0 / rows
    val cellLon = 360.0 / cols

    val viewLatMax = min(latTop, latMaxCrop)
    val viewLatMin = max(latTop - latSpan, latMinCrop)
    val rTop = (((90.0 - viewLatMax) / cellLat).toInt() - 1).coerceAtLeast(0)
    val rBot = (((90.0 - viewLatMin) / cellLat).toInt() + 1).coerceAtMost(rows - 1)

    val pixPerLon = w / lonSpan
    val pixPerLat = h / latSpan
    // Add 0.6px overlap so adjacent cells fuse into solid landmasses with no seams.
    val cellW = (cellLon * pixPerLon).toFloat() + 0.6f
    val cellH = (cellLat * pixPerLat).toFloat() + 0.6f
    val centre = lonMin + lonSpan / 2f

    for (r in rTop..rBot) {
        val cellLatTop = (90.0 - r * cellLat).toFloat()
        val y = ((latTop - cellLatTop) * pixPerLat)
        if (y > h + cellH || y + cellH < 0f) continue
        for (c in 0 until cols) {
            if (!WorldLandmask.isLandCell(r, c)) continue
            var lon = (-180.0 + c * cellLon).toFloat()
            while (lon - centre > 180f) lon -= 360f
            while (lon - centre < -180f) lon += 360f
            val x = ((lon - lonMin) * pixPerLon)
            if (x > w + cellW || x + cellW < 0f) continue
            path.addRect(Rect(x, y, x + cellW, y + cellH))
        }
    }
    return path
}

/**
 * User → server connection arc with a wide soft glow, a crisp gradient
 * stroke that draws in along [progress], and a moving white shimmer dot
 * once fully drawn.
 */
private fun DrawScope.drawConnectionArc(
    a: Offset,
    b: Offset,
    color: Color,
    progress: Float = 1f,
    shimmer: Float = 0f,
) {
    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val chord = sqrt(((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).toDouble()).toFloat()
    val lift = (chord * 0.22f).coerceIn(20f, 80f)
    val ctrl = Offset(mid.x, mid.y - lift)
    val full = Path().apply {
        moveTo(a.x, a.y)
        quadraticTo(ctrl.x, ctrl.y, b.x, b.y)
    }
    val p = progress.coerceIn(0f, 1f)
    val drawn = if (p >= 1f) full else Path().also { dst ->
        val measure = PathMeasure().apply { setPath(full, false) }
        measure.getSegment(0f, measure.length * p, dst, true)
    }
    drawPath(full, color = color.copy(alpha = 0.10f * p), style = Stroke(width = 12f, cap = StrokeCap.Round))
    drawPath(full, color = color.copy(alpha = 0.20f * p), style = Stroke(width = 6f, cap = StrokeCap.Round))
    val brush = Brush.linearGradient(
        colors = listOf(
            color.copy(alpha = 0.7f),
            Color.White.copy(alpha = 0.95f),
            color.copy(alpha = 0.9f),
        ),
        start = a,
        end = b,
    )
    drawPath(drawn, brush = brush, style = Stroke(width = 2.4f, cap = StrokeCap.Round))

    if (p >= 0.98f) {
        val t = shimmer.coerceIn(0f, 1f)
        val u = 1f - t
        val sx = u * u * a.x + 2f * u * t * ctrl.x + t * t * b.x
        val sy = u * u * a.y + 2f * u * t * ctrl.y + t * t * b.y
        drawCircle(color = color.copy(alpha = 0.45f), radius = 6f, center = Offset(sx, sy))
        drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 2.6f, center = Offset(sx, sy))
        drawCircle(color = color.copy(alpha = 0.30f), radius = 12f, center = b)
        drawCircle(color = Color.White, radius = 4.2f, center = b)
        drawCircle(color = color, radius = 2.2f, center = b)
    }
}
