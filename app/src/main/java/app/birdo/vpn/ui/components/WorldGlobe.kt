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
        drawGraticule(cx, cy, radius, effectiveRotation, graticuleColor)

        // 4. Filled continents — single accumulator path.
        val land = buildLandPath(cx, cy, radius, effectiveRotation)
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
            val proj = projectOrtho(lat, lon, effectiveRotation, radius)
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
            val to = projectOrtho(selectedCoord.first, selectedCoord.second, effectiveRotation, radius)
            val from = projectOrtho(userLat, userLon, effectiveRotation, radius)
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
    radius: Float,
): Projection {
    val lat = latDeg * PI / 180.0
    val lon = lonDeg * PI / 180.0 + rotationRadians
    val x = (cos(lat) * sin(lon)) * radius
    val y = (-sin(lat)) * radius
    val z = cos(lat) * cos(lon)
    return Projection(x.toFloat(), y.toFloat(), z >= 0)
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
): Path {
    val path = Path()
    val rows = WorldLandmask.rowCount()
    val cols = WorldLandmask.colCount()
    // 2× sub-sampling: each mask cell becomes a 2×2 grid of sub-cells, with
    // each sub-cell's land bit derived from its four neighbouring mask cells
    // so the silhouette has smoother diagonals.
    val sub = 2
    val subRows = rows * sub
    val subCols = cols * sub
    val latStep = 180.0 / subRows
    val lonStep = 360.0 / subCols

    // Pre-compute per-row lat factors and per-col lon+rotation factors.
    val rowEdges = subRows + 1
    val sinLat = DoubleArray(rowEdges)
    val cosLat = DoubleArray(rowEdges)
    for (r in 0..subRows) {
        val lat = (90.0 - r * latStep) * PI / 180.0
        sinLat[r] = sin(lat)
        cosLat[r] = cos(lat)
    }
    val colEdges = subCols + 1
    val sinLon = DoubleArray(colEdges)
    val cosLon = DoubleArray(colEdges)
    for (c in 0..subCols) {
        val lon = (-180.0 + c * lonStep) * PI / 180.0 + rotation
        sinLon[c] = sin(lon)
        cosLon[c] = cos(lon)
    }

    // Sub-cell land test with neighbour smoothing.
    fun isSubLand(sr: Int, sc: Int): Boolean {
        // Map sub-cell to mask coords and look at the 2×2 mask neighbourhood
        // (top-left + neighbours one cell over). A sub-cell is "land" if at
        // least 2 of its four parent cells are land — this rounds off
        // outside corners and fills inside corners, giving cleaner edges.
        val mr = sr / sub
        val mc = sc / sub
        val rOff = if (sr % sub == 0) -1 else 0
        val cOff = if (sc % sub == 0) -1 else 0
        var count = 0
        for (dr in 0..1) for (dc in 0..1) {
            val rr = mr + rOff + dr
            val cc = ((mc + cOff + dc) + cols) % cols
            if (rr in 0 until rows && WorldLandmask.isLandCell(rr, cc)) count++
        }
        return count >= 2
    }

    for (r in 0 until subRows) {
        // Row visibility bounds skip: at very high latitudes near the poles
        // most cells project to a tiny arc — the cull below handles this.
        val sLat0 = sinLat[r]; val cLat0 = cosLat[r]
        val sLat1 = sinLat[r + 1]; val cLat1 = cosLat[r + 1]
        for (c in 0 until subCols) {
            if (!isSubLand(r, c)) continue
            val sLon0 = sinLon[c]; val cLon0 = cosLon[c]
            val sLon1 = sinLon[c + 1]; val cLon1 = cosLon[c + 1]

            // Cell centre visibility cull (cheap z test).
            val zA = cLat0 * cLon0; val zB = cLat0 * cLon1
            val zC = cLat1 * cLon0; val zD = cLat1 * cLon1
            if (zA < -0.05 && zB < -0.05 && zC < -0.05 && zD < -0.05) continue

            // Project the four corners.
            val x0 = (cLat0 * sLon0 * radius).toFloat()
            val y0 = (-sLat0 * radius).toFloat()
            val x1 = (cLat0 * sLon1 * radius).toFloat()
            val y1 = (-sLat0 * radius).toFloat()
            val x2 = (cLat1 * sLon1 * radius).toFloat()
            val y2 = (-sLat1 * radius).toFloat()
            val x3 = (cLat1 * sLon0 * radius).toFloat()
            val y3 = (-sLat1 * radius).toFloat()

            // Compute centre fallback for hidden corners so polygon stays convex.
            val xCent = ((x0 + x2) * 0.5f)
            val yCent = ((y0 + y2) * 0.5f)
            val px0 = if (zA >= 0.0) x0 else xCent
            val py0 = if (zA >= 0.0) y0 else yCent
            val px1 = if (zB >= 0.0) x1 else xCent
            val py1 = if (zB >= 0.0) y1 else yCent
            val px2 = if (zD >= 0.0) x2 else xCent
            val py2 = if (zD >= 0.0) y2 else yCent
            val px3 = if (zC >= 0.0) x3 else xCent
            val py3 = if (zC >= 0.0) y3 else yCent

            path.moveTo(cx + px0, cy + py0)
            path.lineTo(cx + px1, cy + py1)
            path.lineTo(cx + px2, cy + py2)
            path.lineTo(cx + px3, cy + py3)
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

/** Latitude / longitude graticule lines (every 30°). */
private fun DrawScope.drawGraticule(
    cx: Float,
    cy: Float,
    radius: Float,
    rotation: Float,
    color: Color,
) {
    val stroke = Stroke(width = 0.7f)
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
            val proj = projectOrtho(plat.toDouble(), plon.toDouble(), rotation, radius)
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
