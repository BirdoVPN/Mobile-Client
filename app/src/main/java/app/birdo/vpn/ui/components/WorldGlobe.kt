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
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.utils.CountryCoords
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D rotating globe — Mullvad-style.
 *
 * v1.3.12 — back to a 3D orthographic globe (after the v1.3.9–v1.3.11 flat-map
 * iterations). The globe smoothly rotates so the focal point (user when
 * disconnected, selected server when connected) sits centred and front-facing.
 * When idle/disconnected with no selection, a very slow eastward rotation
 * gives the world a living feel.
 *
 * Renders the 720×360 Natural-Earth land mask as small filled quads on the
 * visible hemisphere, with limb darkening, atmosphere glow, a connection
 * great-circle arc (clipped to the front hemisphere) and Mullvad-style pins.
 *
 * All rendering is in a top-level [DrawScope] extension because the Compose
 * compiler plugin can blow the JVM stack on CI runners when too much DSL
 * nesting lives inside a `@Composable` body — see /memories/tooling-bugs.md.
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

    val infinite = rememberInfiniteTransition(label = "globe")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    val arcShimmer by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "arcShimmer",
    )
    // Slow eastward auto-rotate (degrees) used only when idle.
    val idleSpin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "idleSpin",
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

    // Where the globe should "look at" (its centre point).
    val focusLat: Float
    val focusLon: Float
    val zoom: Float
    if (selectedCoord != null) {
        // Centre on the midpoint of (user, server) so the connection arc sits
        // across the front of the globe.
        val sLat = selectedCoord.first.toFloat()
        val sLon = selectedCoord.second.toFloat()
        val uLat = userLat.toFloat()
        val uLon = userLon.toFloat()
        val rawDLon = sLon - uLon
        val shiftedSLon = when {
            rawDLon > 180f -> sLon - 360f
            rawDLon < -180f -> sLon + 360f
            else -> sLon
        }
        focusLat = (uLat + sLat) / 2f
        focusLon = (uLon + shiftedSLon) / 2f
        zoom = if (isConnected) 1.20f else 1.05f
    } else {
        // Idle: slow eastward spin around the equator-ish.
        focusLat = 12f
        focusLon = if (autoRotate) (idleSpin + userLon.toFloat()) else userLon.toFloat()
        zoom = 1.0f
    }

    val animLat by animateFloatAsState(
        targetValue = focusLat,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "lat",
    )
    val animLon by animateFloatAsState(
        targetValue = focusLon,
        // While idle-spinning we want the value to track the spinner directly
        // so we use a near-zero tween for the idle path; the spinner already
        // animates smoothly. When we have a real focus, we ease into it.
        animationSpec = if (selectedCoord != null) {
            tween(1400, easing = FastOutSlowInEasing)
        } else {
            tween(50, easing = LinearEasing)
        },
        label = "lon",
    )
    val animZoom by animateFloatAsState(
        targetValue = zoom,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "zoom",
    )
    val arcProgress by animateFloatAsState(
        targetValue = if (isConnected && selectedCoord != null) 1f else 0f,
        animationSpec = tween(900, delayMillis = if (isConnected) 400 else 0),
        label = "arcProgress",
    )

    val isLight = palette.isLight
    val space = if (isLight) Color(0xFFF1F4FA) else Color(0xFF050913)
    val oceanCore = if (isLight) Color(0xFFD6DDEB) else Color(0xFF152744)
    val oceanEdge = if (isLight) Color(0xFFB8C2D6) else Color(0xFF0A1A2E)
    val land = if (isLight) Color(0xFF7E8AA3) else Color(0xFF3A638F)
    val landHighlight = if (isLight) Color(0xFF95A1B8) else Color(0xFF4F7FB0)
    val atmosphere = if (isLight) Color(0xFF7F9CC5) else Color(0xFF3D6FA8)
    val accent = palette.accent
    val connectedColor = if (isLight) Color(0xFF1F8F4E) else Color(0xFF44D17E)

    Canvas(modifier = modifier.fillMaxSize()) {
        renderGlobe(
            space = space,
            oceanCore = oceanCore,
            oceanEdge = oceanEdge,
            land = land,
            landHighlight = landHighlight,
            atmosphere = atmosphere,
            accent = accent,
            connectedColor = connectedColor,
            isConnected = isConnected,
            isLight = isLight,
            focusLat = animLat,
            focusLon = animLon,
            zoom = animZoom,
            serverPoints = serverPoints,
            selectedServerId = selectedServerId,
            selectedCoord = selectedCoord,
            userLat = userLat,
            userLon = userLon,
            arcProgress = arcProgress,
            arcShimmer = arcShimmer,
            pulse = pulse,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rendering
// ─────────────────────────────────────────────────────────────────────────────

private fun DrawScope.renderGlobe(
    space: Color,
    oceanCore: Color,
    oceanEdge: Color,
    land: Color,
    landHighlight: Color,
    atmosphere: Color,
    accent: Color,
    connectedColor: Color,
    isConnected: Boolean,
    isLight: Boolean,
    focusLat: Float,
    focusLon: Float,
    zoom: Float,
    serverPoints: List<Triple<String, Double, Double>>,
    selectedServerId: String?,
    selectedCoord: Pair<Double, Double>?,
    userLat: Double,
    userLon: Double,
    arcProgress: Float,
    arcShimmer: Float,
    pulse: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // Background "space" colour.
    drawRect(color = space, size = size)

    val cx = w * 0.5f
    val cy = h * 0.5f
    val baseRadius = min(w, h) * 0.46f
    val radius = baseRadius * zoom

    // Atmosphere glow (radial outside the disc).
    val atmRadius = radius * 1.18f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                atmosphere.copy(alpha = 0.0f),
                atmosphere.copy(alpha = 0.28f),
                atmosphere.copy(alpha = 0.0f),
            ),
            center = Offset(cx, cy),
            radius = atmRadius,
        ),
        radius = atmRadius,
        center = Offset(cx, cy),
    )

    // Ocean disc with subtle radial shading (lighter centre, deeper rim).
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(oceanCore, oceanEdge),
            center = Offset(cx - radius * 0.18f, cy - radius * 0.22f),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, cy),
    )

    // Precompute orientation trig.
    val latRad = (focusLat * PI / 180.0).toFloat()
    val lonRad = (focusLon * PI / 180.0).toFloat()
    val cosLat = cos(latRad)
    val sinLat = sin(latRad)

    // Project (lat, lon) onto the visible hemisphere using orthographic
    // projection. Returns null if the point is on the back side.
    fun project(lat: Double, lon: Double): Offset? {
        val phi = (lat * PI / 180.0).toFloat()
        val lam = (lon * PI / 180.0).toFloat() - lonRad
        val cosPhi = cos(phi)
        // Sphere-space (x,y,z) where +z is towards the camera.
        val sx = cosPhi * sin(lam)
        val sy = sin(phi)
        val sz = cosPhi * cos(lam)
        // Tilt about the x-axis by latRad so focusLat sits at the centre.
        val ty = sy * cosLat - sz * sinLat
        val tz = sy * sinLat + sz * cosLat
        if (tz < 0f) return null // back hemisphere
        return Offset(cx + sx * radius, cy - ty * radius)
    }

    // Land rendering: walk every land cell of the mask. For each land cell,
    // project its centre — if visible, drop a small filled quad. Quads near
    // the limb are smaller (foreshortened) so we use the cosine of the great-
    // circle distance from the focus to attenuate alpha & size slightly.
    val rows = WorldLandmask.rowCount()
    val cols = WorldLandmask.colCount()
    val cellLat = 180.0 / rows
    val cellLon = 360.0 / cols
    // Cell base size in pixels at the centre of the disc.
    val cellPxBase = (radius * (PI / rows.toDouble())).toFloat() * 2.4f

    val landPath = Path()
    val highlightPath = Path()
    var r = 0
    while (r < rows) {
        val lat = 90.0 - (r + 0.5) * cellLat
        var c = 0
        while (c < cols) {
            if (WorldLandmask.isLandCell(r, c)) {
                val lon = -180.0 + (c + 0.5) * cellLon
                val pos = project(lat, lon)
                if (pos != null) {
                    // Distance from the focus (cosine on sphere, used to size cells).
                    val dx = (pos.x - cx) / radius
                    val dy = (pos.y - cy) / radius
                    val r2 = dx * dx + dy * dy
                    // Foreshorten cells near the limb.
                    val foreshorten = sqrt((1f - r2.coerceIn(0f, 1f)).toDouble()).toFloat()
                    val sz = cellPxBase * (0.55f + 0.45f * foreshorten)
                    val half = sz * 0.5f
                    landPath.addRect(
                        Rect(pos.x - half, pos.y - half, pos.x + half, pos.y + half),
                    )
                    // Subtle upper-left highlight on land near the focus to
                    // sell the spherical look.
                    if (foreshorten > 0.85f && (-dx - dy) > 0.2f) {
                        highlightPath.addRect(
                            Rect(
                                pos.x - half * 0.8f,
                                pos.y - half * 0.8f,
                                pos.x + half * 0.8f,
                                pos.y + half * 0.8f,
                            ),
                        )
                    }
                }
            }
            c++
        }
        r++
    }
    drawPath(landPath, color = land)
    drawPath(highlightPath, color = landHighlight.copy(alpha = 0.55f))

    // Limb darkening: a soft inner-shadow ring near the edge of the disc to
    // reinforce the spherical feel.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = if (isLight) 0.10f else 0.32f),
            ),
            center = Offset(cx, cy),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, cy),
    )

    // Specular highlight (upper-left).
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isLight) 0.18f else 0.10f),
                Color.Transparent,
            ),
            center = Offset(cx - radius * 0.45f, cy - radius * 0.55f),
            radius = radius * 0.55f,
        ),
        radius = radius * 0.55f,
        center = Offset(cx - radius * 0.45f, cy - radius * 0.55f),
    )

    // Other server dots (faint).
    for (sp in serverPoints) {
        if (sp.first == selectedServerId) continue
        val pos = project(sp.second, sp.third) ?: continue
        drawCircle(
            color = accent.copy(alpha = 0.30f),
            radius = 1.6f,
            center = pos,
        )
    }

    // Connection arc + endpoints + pins.
    if (selectedCoord != null) {
        val userPos = project(userLat, userLon)
        val srvPos = project(selectedCoord.first, selectedCoord.second)

        if (userPos != null && srvPos != null) {
            val arcColor = if (isConnected) connectedColor else accent
            drawConnectionArc(userPos, srvPos, arcColor, arcProgress, arcShimmer)
        }
        // Always draw pins if visible (they pop in/out as the globe rotates).
        if (userPos != null) {
            val userColor = if (isConnected) connectedColor else accent
            drawGlobePin(userPos, userColor, pulse, small = true)
        }
        if (srvPos != null) {
            drawGlobePin(srvPos, accent, pulse)
        }
    } else {
        // Disconnected, no selection: just mark the user.
        val userPos = project(userLat, userLon)
        if (userPos != null) {
            drawGlobePin(userPos, accent.copy(alpha = 0.9f), pulse, small = true)
        }
    }
}

/** Mullvad-style pin: outer translucent halo + filled disc + inner white dot. */
private fun DrawScope.drawGlobePin(
    center: Offset,
    color: Color,
    pulse: Float,
    small: Boolean = false,
) {
    val haloRadius = (if (small) 12f else 18f) + pulse * 4f
    val discRadius = if (small) 5f else 7f
    val innerRadius = if (small) 2f else 2.8f
    drawCircle(color = color.copy(alpha = 0.18f), radius = haloRadius, center = center)
    drawCircle(color = color.copy(alpha = 0.34f), radius = haloRadius * 0.62f, center = center)
    drawCircle(color = color, radius = discRadius, center = center)
    drawCircle(color = Color.White, radius = innerRadius, center = center)
}

/**
 * Connection arc lifted up off the globe surface as a quadratic curve, with a
 * soft glow + crisp gradient stroke + travelling shimmer dot once fully drawn.
 * Both endpoints must be on the visible hemisphere.
 */
private fun DrawScope.drawConnectionArc(
    a: Offset,
    b: Offset,
    color: Color,
    progress: Float = 1f,
    shimmer: Float = 0f,
) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val chord = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    if (chord < 1f) return
    val mid = Offset((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)
    // Lift the control point perpendicular to the chord (rotated 90° CCW).
    val nx = -dy / chord
    val ny = dx / chord
    val lift = (chord * 0.28f).coerceIn(18f, 90f)
    val ctrl = Offset(mid.x + nx * lift, mid.y + ny * lift)

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
    }
}

@Suppress("unused")
private fun greatCircleDistance(
    lat1: Double, lon1: Double, lat2: Double, lon2: Double,
): Double {
    val p1 = lat1 * PI / 180.0
    val p2 = lat2 * PI / 180.0
    val dl = (lon2 - lon1) * PI / 180.0
    return acos((sin(p1) * sin(p2) + cos(p1) * cos(p2) * cos(dl)).coerceIn(-1.0, 1.0))
}

@Suppress("unused")
private fun greatCircleBearing(
    lat1: Double, lon1: Double, lat2: Double, lon2: Double,
): Double {
    val p1 = lat1 * PI / 180.0
    val p2 = lat2 * PI / 180.0
    val dl = (lon2 - lon1) * PI / 180.0
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return atan2(y, x)
}

@Suppress("unused")
private fun normalizeLon(lon: Double): Double {
    var v = lon
    while (v > 180.0) v -= 360.0
    while (v < -180.0) v += 360.0
    return v
}

@Suppress("unused")
private fun asinSafe(x: Double): Double = asin(x.coerceIn(-1.0, 1.0))

@Suppress("unused")
private fun sizeFix(s: Size): Size = s
