package app.birdo.vpn.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure-maths core of [WorldGlobe]'s continent pass.
 *
 * Deliberately free of every Android and Compose type so the hot loop can be
 * exercised by a plain JVM unit test (see `GlobeGeometryTest`): the number of
 * quads the renderer emits per frame, and its numerical agreement with the
 * legacy trigonometric projection, are then CI facts rather than opinions.
 *
 * ## Why there is no trigonometry in the per-frame loop
 *
 * The v1.3.13 loop stored `[sinPhi, cosPhi, lonRad]` per cell and computed
 * `sin(lon - camLon)` and `cos(lon - camLon)` for EVERY cell, before the
 * back-face test that then threw ~55% of them away. At the shipped stride that
 * is 42,678 transcendental calls per frame, ~1.3 M/second at 30 fps.
 *
 * Both are removable outright, because the camera only ever rotates the sphere
 * about its polar axis. Writing `lam = lonCell - camLon` and expanding the
 * angle-difference identities:
 *
 * ```
 * cosPhi*cos(lam) = (cosPhi*cos lonCell)*cos camLon + (cosPhi*sin lonCell)*sin camLon
 * cosPhi*sin(lam) = (cosPhi*sin lonCell)*cos camLon - (cosPhi*cos lonCell)*sin camLon
 * ```
 *
 * The two bracketed terms are properties of the CELL, not of the frame, so they
 * are folded into the packed buffer at startup as `a` and `b`. `cos camLon` and
 * `sin camLon` are computed once per frame by the caller. The loop is then pure
 * multiply-add, and — the point of the exercise — `a*cosCam + b*sinCam` is
 * exactly the term the back-face test needs, so the cull happens BEFORE the
 * work that only visible cells require.
 *
 * The buffer stays three floats wide: `cosPhi` itself is never needed on its
 * own, only as those two products.
 */
internal class LandSamples(
    /**
     * Packed per cell: `[sinPhi, cosPhi*cos(lonCell), cosPhi*sin(lonCell)]`.
     * Three floats per cell, laid out flat so the loop has no indirection.
     */
    @JvmField val data: FloatArray,
    @JvmField val count: Int,
    /** Angular extent of one cell at the equator, radians. */
    @JvmField val cellSizeRad: Float,
)

/**
 * Receives the quads the continent pass survives with. Implemented by the
 * renderer with three retained `Path`s, and by tests with a counter.
 */
internal interface LandQuadSink {
    /**
     * @param bucket one of [GlobeGeometry.BUCKET_DIM] / [GlobeGeometry.BUCKET_MID] /
     *   [GlobeGeometry.BUCKET_LIT].
     * @param px screen x of the cell centre.
     * @param py screen y of the cell centre.
     * @param half half-extent of the (axis-aligned, foreshortened) square.
     */
    fun quad(bucket: Int, px: Float, py: Float, half: Float)
}

internal object GlobeGeometry {

    const val BUCKET_DIM = 0
    const val BUCKET_MID = 1
    const val BUCKET_LIT = 2

    /** Directional light in camera space (upper-left, towards the camera). */
    const val LIGHT_X = -0.42f
    const val LIGHT_Y = -0.55f
    const val LIGHT_Z = 0.72f

    /** Brightness bucket thresholds on the light dot product. */
    const val LIT_THRESHOLD = 0.55f
    const val MID_THRESHOLD = 0.20f

    /**
     * Back-face cutoff. Cells at or behind this depth are skipped; the small
     * positive bias keeps the limb from shimmering as cells cross the horizon.
     */
    const val FRONT_FACE_EPS = 0.02f

    /**
     * Overlap factor applied to the on-screen cell square. Cell spacing on the
     * disc is `radius * cellLat * stride`, so a square 1.55x that size makes
     * neighbouring cells overlap and read as continuous landmass instead of a
     * dot grid. Independent of the stride, which is why the lite path can
     * quadruple the stride without leaving holes in the continents.
     */
    const val CELL_OVERLAP = 1.55f

    /**
     * Flattens a lat/lon land bitmask into the packed per-cell buffer.
     *
     * Runs exactly once per (stride) at first composition — never per frame.
     */
    fun packLandSamples(
        rows: Int,
        cols: Int,
        strideRow: Int,
        strideCol: Int,
        isLand: (Int, Int) -> Boolean,
    ): LandSamples {
        require(rows > 0 && cols > 0) { "mask must be non-empty" }
        require(strideRow > 0 && strideCol > 0) { "stride must be positive" }

        val cellLat = PI / rows.toDouble()
        val cellLon = (2.0 * PI) / cols.toDouble()

        var counted = 0
        var r = 0
        while (r < rows) {
            var c = 0
            while (c < cols) {
                if (isLand(r, c)) counted++
                c += strideCol
            }
            r += strideRow
        }

        val arr = FloatArray(counted * 3)
        var idx = 0
        r = 0
        while (r < rows) {
            val phi = (PI / 2.0) - (r + 0.5) * cellLat
            val sinPhi = sin(phi)
            val cosPhi = cos(phi)
            var c = 0
            while (c < cols) {
                if (isLand(r, c)) {
                    val lon = -PI + (c + 0.5) * cellLon
                    arr[idx] = sinPhi.toFloat()
                    arr[idx + 1] = (cosPhi * cos(lon)).toFloat()
                    arr[idx + 2] = (cosPhi * sin(lon)).toFloat()
                    idx += 3
                }
                c += strideCol
            }
            r += strideRow
        }
        val cellSizeRad = (cellLat * strideRow * CELL_OVERLAP).toFloat()
        return LandSamples(arr, idx / 3, cellSizeRad)
    }

    /**
     * Projects every packed cell, culls the back hemisphere, and hands the
     * survivors to [sink] already classified by brightness.
     *
     * Contains no transcendental call of any kind: see the class docs. Pinned
     * by `GlobeGeometryTest.per-frame land projection contains no trigonometry`.
     *
     * @param cosLon `cos(cameraLongitudeRad)`, computed once per frame.
     * @param sinLon `sin(cameraLongitudeRad)`, computed once per frame.
     * @return the number of quads emitted (i.e. cells that survived the cull).
     */
    @Suppress("LongParameterList")
    fun projectLandQuads(
        samples: LandSamples,
        cx: Float,
        cy: Float,
        radius: Float,
        cosLat: Float,
        sinLat: Float,
        cosLon: Float,
        sinLon: Float,
        cellPx: Float,
        sink: LandQuadSink,
    ): Int {
        val data = samples.data
        val limit = samples.count * 3
        val halfScale = cellPx * 0.5f
        var visible = 0
        var i = 0
        while (i < limit) {
            val sinPhi = data[i]
            val a = data[i + 1]
            val b = data[i + 2]
            i += 3

            // sz == cosPhi * cos(lonCell - camLon) — the only term the cull needs.
            val sz = a * cosLon + b * sinLon
            val tz = sinPhi * sinLat + sz * cosLat
            if (tz <= FRONT_FACE_EPS) continue

            // Everything below is paid for by VISIBLE cells only.
            val sx = b * cosLon - a * sinLon
            val ty = sinPhi * cosLat - sz * sinLat
            val px = cx + sx * radius
            val py = cy - ty * radius
            val dot = sx * LIGHT_X + ty * LIGHT_Y + tz * LIGHT_Z
            // Foreshorten cells near the limb to disguise the rectangular grid.
            val half = halfScale * (0.55f + 0.45f * tz)
            val bucket = when {
                dot > LIT_THRESHOLD -> BUCKET_LIT
                dot > MID_THRESHOLD -> BUCKET_MID
                else -> BUCKET_DIM
            }
            sink.quad(bucket, px, py, half)
            visible++
        }
        return visible
    }
}

/**
 * Cost profile for one globe render.
 *
 * The trigger is deliberately NOT the API level: a 2026 budget phone can ship
 * API 36 on an A55-class CPU, and a 2019 flagship on API 29 will out-render it.
 * What actually predicts "this device will drop frames on 20k quads" is the
 * memory tier Android itself assigns the device — `isLowRamDevice` for the
 * Android Go / heavily constrained tier, and the per-app heap `memoryClass`,
 * which tracks the device's overall class closely enough to be a usable proxy
 * and is what Google's own guidance points at.
 */
internal class GlobeQuality(
    /** Sampling stride over the 720x360 landmask, in mask cells. */
    @JvmField val landStride: Int,
    @JvmField val starCount: Int,
    /** Minimum ms between published animation clock ticks. */
    @JvmField val frameIntervalMs: Long,
    /** Whether the star field twinkles (a per-frame alpha modulation). */
    @JvmField val twinkle: Boolean,
    /** Whether the warm sub-solar hotspot is drawn. */
    @JvmField val sunHotspot: Boolean,
    @JvmField val lite: Boolean,
) {
    companion object {
        /**
         * Every device that is not explicitly low-tier. Identical in output to
         * the globe shipped in 1.4.20 — same stride, same star count, same
         * 30 fps cadence.
         */
        val FULL = GlobeQuality(
            landStride = 2,
            starCount = 90,
            frameIntervalMs = 33L,
            twinkle = true,
            sunHotspot = true,
            lite = false,
        )

        /**
         * Low-tier path. Stride 4 emits ~4x fewer quads for the SAME painted
         * area — [GlobeGeometry.CELL_OVERLAP] scales the squares with the
         * stride, so fill rate is unchanged and only the per-quad CPU and JNI
         * cost falls away. Visible difference: chunkier coastlines, a sparser
         * and non-twinkling star field, no sub-solar hotspot, 20 fps.
         */
        val LITE = GlobeQuality(
            landStride = 4,
            starCount = 36,
            frameIntervalMs = 50L,
            twinkle = false,
            sunHotspot = false,
            lite = true,
        )

        /** Per-app heap (MB) at or below which a device is treated as low-tier. */
        const val LOW_MEMORY_CLASS_MB = 128

        /** Pure decision function — the part worth unit testing. */
        fun forDevice(isLowRamDevice: Boolean, memoryClassMb: Int): GlobeQuality =
            if (isLowRamDevice || memoryClassMb <= LOW_MEMORY_CLASS_MB) LITE else FULL
    }
}
