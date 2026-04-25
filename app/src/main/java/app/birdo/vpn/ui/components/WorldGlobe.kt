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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.utils.CountryCoords
import kotlin.math.max
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
    // Mullvad-style palette: muted navy ocean, slate-blue continents, lighter coastline.
    val ocean = if (isLight) Color(0xFFE6EAF2) else Color(0xFF192E45)
    val landFill = if (isLight) Color(0xFFB7C2D6) else Color(0xFF294B6E)
    val coastStroke = if (isLight) Color(0xFF8896B0) else Color(0xFF4A6E94)
    val accent = palette.accent
    val connectedColor = if (isLight) Color(0xFF1F8F4E) else Color(0xFF44D17E)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Solid ocean background — no gradient, no grid, no border (Mullvad look).
        drawRect(color = ocean, size = size)

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
            // Smooth landmass: marching-squares Path in normalised [0,1] space,
            // scaled and panned to match the current projection. Wraps around
            // the antimeridian by drawing three copies (-1, 0, +1) of the unit
            // tile horizontally so the world never has a seam.
            val pixPerLon = w / lonSpan
            val pixPerLat = h / latSpan
            val tileW = 360f * pixPerLon
            val tileH = 180f * pixPerLat
            // Where the world's left edge (lon = -180, the u=0 column) lands.
            val baseX = (-180f - lonMin) * pixPerLon
            // Where the world's top edge (lat = 90, the v=0 row) lands.
            val baseY = (latTop - 90f) * pixPerLat

            val landPath = WorldLandmask.landPathNormalised()

            for (k in -1..1) {
                val ox = baseX + k * tileW
                if (ox + tileW < -2f || ox > w + 2f) continue
                translate(left = ox, top = baseY) {
                    scale(scaleX = tileW, scaleY = tileH, pivot = Offset.Zero) {
                        drawPath(
                            landPath,
                            color = landFill,
                        )
                        // Path is in unit space; convert ~0.7px coast stroke
                        // into normalised units via the average tile scale.
                        val avgTile = (tileW + tileH) * 0.5f
                        if (avgTile > 0f) {
                            drawPath(
                                landPath,
                                color = coastStroke.copy(alpha = if (isLight) 0.55f else 0.45f),
                                style = Stroke(width = 0.9f / avgTile),
                            )
                        }
                    }
                }
            }

            // Server dots — small, faint, Mullvad-style.
            serverPoints.forEach { (id, lat, lon) ->
                val pos = project(lat.toFloat(), lon.toFloat())
                if (pos.x !in -8f..(w + 8f) || pos.y !in -8f..(h + 8f)) return@forEach
                val isSelected = id == selectedServerId
                if (isSelected) return@forEach // drawn separately below
                drawCircle(
                    color = palette.mapDotMuted.copy(alpha = if (isLight) 0.55f else 0.45f),
                    radius = 2.2f,
                    center = pos,
                )
            }

            // Connection arc.
            if (zoomActive && selectedCoord != null) {
                val from = project(userLat.toFloat(), userLon.toFloat())
                val to = project(selectedCoord.first.toFloat(), selectedCoord.second.toFloat())
                val arcColor = if (isConnected) connectedColor else accent
                drawConnectionArc(from, to, arcColor, arcProgress, arcShimmer)
            }

            // Mullvad-style location pins: large translucent halo, filled
            // disc, white inner dot. User pin = green when connected, accent
            // otherwise. Selected server pin always uses accent.
            if (zoomActive && selectedCoord != null) {
                val from = project(userLat.toFloat(), userLon.toFloat())
                val to = project(selectedCoord.first.toFloat(), selectedCoord.second.toFloat())
                val userColor = if (isConnected) connectedColor else accent
                drawMullvadPin(from, userColor, pulse)
                drawMullvadPin(to, accent, pulse)
            } else {
                // Disconnected: highlight the selected server pin if any.
                val sel = serverPoints.firstOrNull { it.first == selectedServerId }
                if (sel != null) {
                    val pos = project(sel.second.toFloat(), sel.third.toFloat())
                    if (pos.x in -8f..(w + 8f) && pos.y in -8f..(h + 8f)) {
                        drawMullvadPin(pos, accent, pulse)
                    }
                }
                // Always show the user pin so people can see "you are here".
                val userPos = project(userLat.toFloat(), userLon.toFloat())
                if (userPos.x in -8f..(w + 8f) && userPos.y in -8f..(h + 8f)) {
                    drawMullvadPin(userPos, accent.copy(alpha = 0.9f), pulse, small = true)
                }
            }
        }
    }
}

/** Mullvad-style pin: outer translucent halo + filled disc + inner white dot. */
private fun DrawScope.drawMullvadPin(
    center: Offset,
    color: Color,
    pulse: Float,
    small: Boolean = false,
) {
    val haloRadius = (if (small) 14f else 22f) + pulse * 4f
    val discRadius = if (small) 5.5f else 7.5f
    val innerRadius = if (small) 2.2f else 3.0f
    drawCircle(color = color.copy(alpha = 0.18f), radius = haloRadius, center = center)
    drawCircle(color = color.copy(alpha = 0.32f), radius = haloRadius * 0.66f, center = center)
    drawCircle(color = color, radius = discRadius, center = center)
    drawCircle(color = Color.White, radius = innerRadius, center = center)
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
