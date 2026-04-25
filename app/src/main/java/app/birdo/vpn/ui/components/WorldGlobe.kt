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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-fidelity orthographic globe with FILLED continent silhouettes,
 * atmosphere halo, ocean gradient, lat/lon graticule, and a Mullvad-style
 * connection arc that animates from the user pin to the selected server.
 *
 * v1.3.4 — replaces the v1.3.2/3 point-rasterised land with **filled
 * continent polygons** built from the 144×72 land mask. For every land
 * cell we project the four corners onto the sphere and add a tiny quad
 * to a single accumulator [Path] which is drawn in one filled call per
 * frame, giving smooth solid landmasses at minimal cost.
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
            // Slow 100s drift while idle.
            animation = tween(durationMillis = 100_000, easing = LinearEasing),
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
    val zoomActive = isConnected && selectedCoord != null
    val targetLonDeg: Float = if (zoomActive) {
        val midLon = midpointLon(userLon.toFloat(), selectedCoord!!.second.toFloat())
        -midLon
    } else 0f
    val targetTiltDeg: Float = if (zoomActive) {
        // Tilt the sphere so the latitude midpoint of the connection sits on
        // the equator of the projection, centring the pair vertically.
        val midLat = (userLat + selectedCoord!!.first).toFloat() / 2f
        midLat
    } else 0f

    // Distance-based zoom: short hops zoom in tight, long hops pull back so
    // both endpoints stay on the visible hemisphere. We use the great-circle
    // angular distance between user and selected server.
    val angularDistDeg: Float = if (zoomActive) {
        angularDistanceDeg(userLat, userLon, selectedCoord!!.first, selectedCoord.second).toFloat()
    } else 0f
    val targetZoom: Float = if (zoomActive) {
        // Aim to fill the visible disk with the pair: chord across the sphere
        // ≈ 2·sin(angDist/2). We want that chord to span ~70 % of the radius,
        // so zoom ≈ 0.70 / sin(angDist/2). Clamped to a useful range.
        val half = (angularDistDeg / 2f) * (PI.toFloat() / 180f)
        val s = sin(half.toDouble()).toFloat().coerceAtLeast(0.05f)
        (0.70f / s).coerceIn(1.10f, 2.20f)
    } else 1f

    val idleRotDeg = (rotation * 180f / PI.toFloat())
    val animatedFocusRotDeg by animateFloatAsState(
        targetValue = targetLonDeg,
        animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
        label = "focusRot",
    )
    val animatedTiltDeg by animateFloatAsState(
        targetValue = targetTiltDeg,
        animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
        label = "focusTilt",
    )
    val focusScale by animateFloatAsState(
        targetValue = targetZoom,
        animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
        label = "focusScale",
    )
    val arcProgress by animateFloatAsState(
        targetValue = if (zoomActive) 1f else 0f,
        animationSpec = tween(
            durationMillis = 1100,
            delayMillis = if (zoomActive) 500 else 0,
        ),
        label = "arcProgress",
    )

    val effectiveRotDeg = when {
        zoomActive -> animatedFocusRotDeg
        autoRotate -> idleRotDeg
        else -> 0f
    }
    val effectiveRotation = effectiveRotDeg * (PI.toFloat() / 180f)
    val effectiveTilt = animatedTiltDeg * (PI.toFloat() / 180f)

    val isLight = palette.isLight
    val landFill = if (isLight) Color(0xFF3B0764) else Color(0xFFB8A2EE)
    val landFillAlpha = if (isLight) 0.92f else 0.90f
    val landRim = if (isLight) Color(0xFF1E1B4B).copy(alpha = 0.55f)
                  else Color.White.copy(alpha = 0.10f)
    val oceanDeep = if (isLight) Color(0xFFCBD5E1) else Color(0xFF0B1226)
    val oceanShallow = if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E1B4B)
    val atmosphere = palette.accent
    val graticuleColor = palette.hairline.copy(alpha = if (isLight) 0.16f else 0.10f)

    Canvas(modifier = modifier.fillMaxSize().scale(focusScale)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (minOf(size.width, size.height) / 2f) * 0.96f

        // 1. Outer atmosphere glow — soft halo extending past the sphere.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    atmosphere.copy(alpha = 0.06f),
                    atmosphere.copy(alpha = 0.20f),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = radius * 1.35f,
            ),
            radius = radius * 1.35f,
            center = Offset(cx, cy),
        )

        // 2. Sphere base — deep ocean with off-centre highlight.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(oceanShallow, oceanDeep),
                center = Offset(cx - radius * 0.32f, cy - radius * 0.34f),
                radius = radius * 1.28f,
            ),
            radius = radius,
            center = Offset(cx, cy),
        )

        // 3. Graticule — meridians + parallels every 30°.
        drawGraticule(cx, cy, radius, effectiveRotation, effectiveTilt, graticuleColor)

        // 4. Filled continents — single accumulator path.
        val land = buildLandPath(cx, cy, radius, effectiveRotation, effectiveTilt)
        drawPath(land, color = landFill.copy(alpha = landFillAlpha))
        drawPath(land, color = landRim, style = Stroke(width = 0.8f))

        // 5. Limb darkening.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Transparent,
                    Color.Black.copy(alpha = if (isLight) 0.16f else 0.36f),
                ),
                center = Offset(cx, cy),
                radius = radius,
            ),
            radius = radius,
            center = Offset(cx, cy),
        )

        // 6. Inner rim highlight where atmosphere meets globe.
        drawCircle(
            color = atmosphere.copy(alpha = if (isLight) 0.35f else 0.45f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.4f),
        )

        // 7. Server dots.
        serverPoints.forEach { (id, lat, lon) ->
            val proj = projectOrtho(lat, lon, effectiveRotation, effectiveTilt, radius)
            if (!proj.visible) return@forEach
            val pos = Offset(cx + proj.x, cy + proj.y)
            val isSelected = id == selectedServerId
            val baseColor = if (isSelected) atmosphere else palette.mapDot
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

        // 8. Connection arc + endpoints.
        if (zoomActive && selectedCoord != null) {
            val to = projectOrtho(selectedCoord.first, selectedCoord.second, effectiveRotation, effectiveTilt, radius)
            val from = projectOrtho(userLat, userLon, effectiveRotation, effectiveTilt, radius)
            if (to.visible && from.visible) {
                val a = Offset(cx + from.x, cy + from.y)
                val b = Offset(cx + to.x, cy + to.y)
                drawConnectionArc(a, b, atmosphere, progress = arcProgress, shimmer = arcShimmer)
                // User pin.
                drawCircle(color = atmosphere.copy(alpha = 0.30f), radius = 11f, center = a)
                drawCircle(color = Color.White, radius = 4.0f, center = a)
                drawCircle(color = atmosphere, radius = 2.0f, center = a)
            }
        }

        // 9. Specular highlight upper-left.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isLight) 0.10f else 0.08f),
                    Color.Transparent,
                ),
                center = Offset(cx - radius * 0.42f, cy - radius * 0.55f),
                radius = radius * 0.7f,
            ),
            radius = radius * 0.7f,
            center = Offset(cx - radius * 0.42f, cy - radius * 0.55f),
        )
    }
}

private data class Projection(val x: Float, val y: Float, val visible: Boolean)

private fun projectOrtho(
    latDeg: Double,
    lonDeg: Double,
    rotationRadians: Float,
    tiltRadians: Float,
    radius: Float,
): Projection {
    val lat = latDeg * PI / 180.0
    val lon = lonDeg * PI / 180.0 + rotationRadians
    // Sphere coordinates after longitude rotation.
    val x0 = cos(lat) * sin(lon)
    val y0 = -sin(lat)
    val z0 = cos(lat) * cos(lon)
    // Apply latitude tilt as a rotation around the X axis: rotates (y, z).
    val ct = cos(tiltRadians.toDouble())
    val st = sin(tiltRadians.toDouble())
    val y1 = y0 * ct - z0 * st
    val z1 = y0 * st + z0 * ct
    val x = x0 * radius
    val y = y1 * radius
    return Projection(x.toFloat(), y.toFloat(), z1 >= 0)
}

/**
 * Builds a single [Path] containing one tiny quadrilateral per LAND sub-cell
 * on the visible hemisphere, at 2× mask resolution with neighbour smoothing
 * for cleaner continent edges. The four corners of each sub-cell are
 * projected onto the sphere so adjacent quads merge into solid silhouettes
 * when filled.
 *
 * Trig values for each row (lat) and each column (lon+rotation) are
 * pre-computed once per build so the inner loop is just multiplies, even
 * though we now process 4× the cell count.
 */
private fun buildLandPath(
    cx: Float,
    cy: Float,
    radius: Float,
    rotation: Float,
    tilt: Float,
): Path {
    val path = Path()
    val rows = WorldLandmask.rowCount()
    val cols = WorldLandmask.colCount()
    val latStep = 180.0 / rows
    val lonStep = 360.0 / cols

    // Pre-compute per-row lat factors and per-col lon+rotation factors.
    val rowEdges = rows + 1
    val sinLat = DoubleArray(rowEdges)
    val cosLat = DoubleArray(rowEdges)
    for (r in 0..rows) {
        val lat = (90.0 - r * latStep) * PI / 180.0
        sinLat[r] = sin(lat)
        cosLat[r] = cos(lat)
    }
    val colEdges = cols + 1
    val sinLon = DoubleArray(colEdges)
    val cosLon = DoubleArray(colEdges)
    for (c in 0..cols) {
        val lon = (-180.0 + c * lonStep) * PI / 180.0 + rotation
        sinLon[c] = sin(lon)
        cosLon[c] = cos(lon)
    }
    val ct = cos(tilt.toDouble())
    val st = sin(tilt.toDouble())

    // Project a sphere unit-vector through the latitude tilt and into screen
    // pixel space relative to (cx, cy). Returns z so visibility can be tested.
    fun proj(sLat: Double, cLat: Double, sLon: Double, cLon: Double): DoubleArray {
        val x0 = cLat * sLon
        val y0 = -sLat
        val z0 = cLat * cLon
        val y1 = y0 * ct - z0 * st
        val z1 = y0 * st + z0 * ct
        return doubleArrayOf(x0 * radius, y1 * radius, z1)
    }

    for (r in 0 until rows) {
        val sLat0 = sinLat[r]; val cLat0 = cosLat[r]
        val sLat1 = sinLat[r + 1]; val cLat1 = cosLat[r + 1]
        for (c in 0 until cols) {
            if (!WorldLandmask.isLandCell(r, c)) continue
            val sLon0 = sinLon[c]; val cLon0 = cosLon[c]
            val sLon1 = sinLon[c + 1]; val cLon1 = cosLon[c + 1]

            val a = proj(sLat0, cLat0, sLon0, cLon0)
            val b = proj(sLat0, cLat0, sLon1, cLon1)
            val cc = proj(sLat1, cLat1, sLon1, cLon1)
            val d = proj(sLat1, cLat1, sLon0, cLon0)

            // Skip cells fully on the far side.
            if (a[2] < -0.02 && b[2] < -0.02 && cc[2] < -0.02 && d[2] < -0.02) continue
            // Skip cells with any corner clearly on the far side — avoids the
            // "wrap" artifacts where a quad's corners straddle the limb.
            if (a[2] < 0.0 || b[2] < 0.0 || cc[2] < 0.0 || d[2] < 0.0) continue

            path.moveTo(cx + a[0].toFloat(), cy + a[1].toFloat())
            path.lineTo(cx + b[0].toFloat(), cy + b[1].toFloat())
            path.lineTo(cx + cc[0].toFloat(), cy + cc[1].toFloat())
            path.lineTo(cx + d[0].toFloat(), cy + d[1].toFloat())
            path.close()
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
    val centre = Offset(size.width / 2f, size.height / 2f)
    val dir = Offset(mid.x - centre.x, mid.y - centre.y)
    val len = sqrt((dir.x * dir.x + dir.y * dir.y).toDouble())
        .toFloat().coerceAtLeast(1f)
    val chord = sqrt(((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).toDouble()).toFloat()
    val lift = (chord * 0.24f).coerceIn(20f, 90f)
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
        // Quadratic Bezier point at t = shimmer.
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

/** Returns the midpoint longitude (degrees) on the shorter arc. */
private fun midpointLon(a: Float, b: Float): Float {
    var d = b - a
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    var mid = a + d / 2f
    while (mid > 180f) mid -= 360f
    while (mid < -180f) mid += 360f
    return mid
}

/** Great-circle angular distance between two lat/lon points, in degrees. */
private fun angularDistanceDeg(
    lat1: Double, lon1: Double, lat2: Double, lon2: Double,
): Double {
    val p1 = lat1 * PI / 180.0
    val p2 = lat2 * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val cosD = sin(p1) * sin(p2) + cos(p1) * cos(p2) * cos(dLon)
    return kotlin.math.acos(cosD.coerceIn(-1.0, 1.0)) * 180.0 / PI
}

/** Latitude / longitude graticule lines (every 30°). */
private fun DrawScope.drawGraticule(
    cx: Float,
    cy: Float,
    radius: Float,
    rotation: Float,
    tilt: Float,
    color: Color,
) {
    val stroke = Stroke(width = 0.7f)
    var lon = -180
    while (lon < 180) {
        val path = Path()
        var first = true
        var lat = -90
        while (lat <= 90) {
            val proj = projectOrtho(lat.toDouble(), lon.toDouble(), rotation, tilt, radius)
            if (proj.visible) {
                val x = cx + proj.x; val y = cy + proj.y
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            } else first = true
            lat += 3
        }
        drawPath(path, color, style = stroke)
        lon += 30
    }
    var plat = -60
    while (plat <= 60) {
        val path = Path()
        var first = true
        var plon = -180
        while (plon <= 180) {
            val proj = projectOrtho(plat.toDouble(), plon.toDouble(), rotation, tilt, radius)
            if (proj.visible) {
                val x = cx + proj.x; val y = cy + proj.y
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            } else first = true
            plon += 3
        }
        drawPath(path, color, style = stroke)
        plat += 30
    }
}
