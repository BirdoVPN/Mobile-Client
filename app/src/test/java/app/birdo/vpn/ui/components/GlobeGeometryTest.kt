package app.birdo.vpn.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Deterministic cost + correctness pins for the Home-screen globe.
 *
 * The globe is the first thing an install sees, on the oldest hardware in the
 * fleet, so its per-frame cost is a business number, not a detail. None of it
 * can be measured from a phone in CI — so this file measures the two things
 * that ARE deterministic and that dominate the frame:
 *
 *  1. **Transcendental calls per frame.** The legacy loop is re-implemented
 *     verbatim below with a counter wrapped around every `sin`/`cos`, so the
 *     "before" number is measured rather than asserted. The "after" number is
 *     zero, pinned by brace-matching the shipped [GlobeGeometry.projectLandQuads]
 *     and refusing to find a transcendental call in it.
 *  2. **Path verbs per frame.** Every surviving cell costs five
 *     `moveTo`/`lineTo`/`close` calls on an `android.graphics.Path`, i.e. five
 *     JNI crossings. Counting the quads a camera produces counts those exactly.
 *
 * And the correctness half: the new projection must land on the same pixels as
 * the old one, or the optimisation is a redesign of the product's signature
 * visual instead of a speed-up.
 */
class GlobeGeometryTest {

    // ─────────────────────────────────────────────────────────────────────
    // Test fixtures
    // ─────────────────────────────────────────────────────────────────────

    /** Repo root, found by walking up from the test working dir. */
    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above " + File("").absolutePath)
        }
        dir
    }

    /** A representative phone canvas: 1080x1080 logical px, globe radius 0.46. */
    private val cx = 540f
    private val cy = 540f
    private val radius = 496.8f

    /**
     * Cameras the idle spin actually visits. `focusLon` sweeps the full circle
     * every 90 s and `focusLat` is the (user, server) midpoint, which for the
     * live fleet stays inside roughly +-60 degrees.
     */
    private val cameraLats = floatArrayOf(0f, 12f, -12f, 40f, -40f, 60f)
    private val cameraLons = floatArrayOf(0f, 37f, 90f, 133f, 180f, 214f, 270f, 318f)

    private fun realMaskSamples(stride: Int): LandSamples =
        GlobeGeometry.packLandSamples(
            rows = WorldLandmask.rowCount(),
            cols = WorldLandmask.colCount(),
            strideRow = stride,
            strideCol = stride,
            isLand = { r, c -> WorldLandmask.isLandCell(r, c) },
        )

    private class RecordingSink(capacity: Int) : LandQuadSink {
        val bucket = IntArray(capacity)
        val px = FloatArray(capacity)
        val py = FloatArray(capacity)
        val half = FloatArray(capacity)
        var n = 0
        override fun quad(bucket: Int, px: Float, py: Float, half: Float) {
            this.bucket[n] = bucket
            this.px[n] = px
            this.py[n] = py
            this.half[n] = half
            n++
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // The v1.4.20 implementation, verbatim, with the trig instrumented.
    //
    // Kept in the test rather than in the app so the "before" numbers below are
    // measured from running code instead of asserted from a changelog.
    // ─────────────────────────────────────────────────────────────────────

    private var trigCalls = 0

    private fun tSin(x: Float): Float {
        trigCalls++
        return sin(x)
    }

    private fun tCos(x: Float): Float {
        trigCalls++
        return cos(x)
    }

    /** Legacy packing: `[sinPhi, cosPhi, lonRad]` per cell. */
    private fun legacyPack(stride: Int): Pair<FloatArray, Int> {
        val rows = WorldLandmask.rowCount()
        val cols = WorldLandmask.colCount()
        val cellLat = PI / rows.toDouble()
        val cellLon = (2.0 * PI) / cols.toDouble()
        var counted = 0
        var r = 0
        while (r < rows) {
            var c = 0
            while (c < cols) {
                if (WorldLandmask.isLandCell(r, c)) counted++
                c += stride
            }
            r += stride
        }
        val arr = FloatArray(counted * 3)
        var idx = 0
        r = 0
        while (r < rows) {
            val phi = (PI / 2.0) - (r + 0.5) * cellLat
            val sinPhi = sin(phi).toFloat()
            val cosPhi = cos(phi).toFloat()
            var c = 0
            while (c < cols) {
                if (WorldLandmask.isLandCell(r, c)) {
                    val lon = -PI + (c + 0.5) * cellLon
                    arr[idx] = sinPhi
                    arr[idx + 1] = cosPhi
                    arr[idx + 2] = lon.toFloat()
                    idx += 3
                }
                c += stride
            }
            r += stride
        }
        return arr to (idx / 3)
    }

    /** Legacy per-frame loop: sin/cos for EVERY cell, before the cull. */
    @Suppress("LongParameterList")
    private fun legacyProject(
        data: FloatArray,
        count: Int,
        cosLat: Float,
        sinLat: Float,
        lonRad: Float,
        cellPx: Float,
        sink: RecordingSink,
    ): Int {
        var visible = 0
        val limit = count * 3
        var i = 0
        while (i < limit) {
            val sinPhi = data[i]
            val cosPhi = data[i + 1]
            val lon = data[i + 2]
            i += 3
            val lam = lon - lonRad
            val sinL = tSin(lam)
            val cosL = tCos(lam)
            val sx = cosPhi * sinL
            val sy = sinPhi
            val sz = cosPhi * cosL
            val ty = sy * cosLat - sz * sinLat
            val tz = sy * sinLat + sz * cosLat
            if (tz <= 0.02f) continue
            val px = cx + sx * radius
            val py = cy - ty * radius
            val dot = sx * (-0.42f) + ty * (-0.55f) + tz * 0.72f
            val half = cellPx * (0.55f + 0.45f * tz) * 0.5f
            val bucket = when {
                dot > 0.55f -> GlobeGeometry.BUCKET_LIT
                dot > 0.20f -> GlobeGeometry.BUCKET_MID
                else -> GlobeGeometry.BUCKET_DIM
            }
            sink.quad(bucket, px, py, half)
            visible++
        }
        return visible
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. The landmask still decodes to the same planet
    // ─────────────────────────────────────────────────────────────────────

    /**
     * WorldLandmask moved from `android.util.Base64` to `java.util.Base64` so
     * the real 720x360 mask can be decoded in a plain JVM test (which is what
     * makes every number below a measurement of the SHIPPED data rather than of
     * a toy fixture). Pin the decode: a decoder that silently produced a
     * different byte stream would change the whole coastline.
     */
    @Test
    fun `landmask decodes to the pinned land cell count`() {
        var land = 0
        for (r in 0 until WorldLandmask.rowCount()) {
            for (c in 0 until WorldLandmask.colCount()) {
                if (WorldLandmask.isLandCell(r, c)) land++
            }
        }
        assertEquals(85_698, land)
        // Spot-checks that the mask is oriented the way the projection assumes:
        // row 0 is the north pole, column 0 is longitude -180.
        assertTrue("central Australia should be land", WorldLandmask.isLand(-25.0, 133.0))
        assertTrue("central Siberia should be land", WorldLandmask.isLand(62.0, 100.0))
        assertFalse("mid Pacific should be ocean", WorldLandmask.isLand(0.0, -140.0))
        assertFalse("mid Atlantic should be ocean", WorldLandmask.isLand(30.0, -40.0))
    }

    @Test
    fun `packing the real mask yields the pinned sample counts`() {
        assertEquals(21_339, realMaskSamples(2).count)
        assertEquals(4, GlobeQuality.LITE.landStride)
        assertEquals(5_248, realMaskSamples(4).count)
        // Three floats per cell, no more: the longitude products replaced the
        // stored longitude rather than being added alongside it.
        assertEquals(21_339 * 3, realMaskSamples(2).data.size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. The optimisation did not move a single pixel
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The whole optimisation rests on one identity — `lam = lonCell - camLon`,
     * expanded with the angle-difference formulas so the per-cell terms can be
     * pre-folded. If that is right, the new loop must reproduce the old loop's
     * output for every cell and every camera, and it must classify every cell
     * into the same brightness bucket.
     *
     * The two paths do not perform the same float operations in the same order,
     * so bit-equality is not on offer; sub-pixel equality is, and that is what
     * "the user cannot see the difference" actually means.
     */
    @Test
    fun `new projection matches the legacy trigonometric projection sub-pixel`() {
        val packed = realMaskSamples(2)
        val (legacyData, legacyCount) = legacyPack(2)
        assertEquals(legacyCount, packed.count)

        val cellPx = radius * packed.cellSizeRad
        var worstPos = 0f
        var worstHalf = 0f
        var bucketMismatches = 0
        var comparedQuads = 0

        for (latDeg in cameraLats) {
            for (lonDeg in cameraLons) {
                val latRad = latDeg * (PI.toFloat() / 180f)
                val lonRad = lonDeg * (PI.toFloat() / 180f)
                val cosLat = cos(latRad)
                val sinLat = sin(latRad)

                val legacySink = RecordingSink(legacyCount)
                legacyProject(legacyData, legacyCount, cosLat, sinLat, lonRad, cellPx, legacySink)

                val newSink = RecordingSink(packed.count)
                GlobeGeometry.projectLandQuads(
                    samples = packed,
                    cx = cx,
                    cy = cy,
                    radius = radius,
                    cosLat = cosLat,
                    sinLat = sinLat,
                    cosLon = cos(lonRad),
                    sinLon = sin(lonRad),
                    cellPx = cellPx,
                    sink = newSink,
                )

                assertEquals(
                    "visible cell count diverged at lat=$latDeg lon=$lonDeg",
                    legacySink.n,
                    newSink.n,
                )
                for (i in 0 until legacySink.n) {
                    worstPos = max(worstPos, abs(legacySink.px[i] - newSink.px[i]))
                    worstPos = max(worstPos, abs(legacySink.py[i] - newSink.py[i]))
                    worstHalf = max(worstHalf, abs(legacySink.half[i] - newSink.half[i]))
                    if (legacySink.bucket[i] != newSink.bucket[i]) bucketMismatches++
                }
                comparedQuads += legacySink.n
            }
        }

        // Vacuity guard: an empty comparison would pass every assertion here.
        assertTrue(
            "only $comparedQuads quads compared — the fixture is broken",
            comparedQuads > 400_000,
        )
        assertTrue(
            "worst screen-space deviation was $worstPos px (limit 0.001)",
            worstPos < 0.001f,
        )
        assertTrue(
            "worst half-extent deviation was $worstHalf px (limit 0.001)",
            worstHalf < 0.001f,
        )
        assertEquals(
            "$bucketMismatches of $comparedQuads cells changed brightness bucket",
            0,
            bucketMismatches,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. Transcendental calls per frame: measured before, pinned after
    // ─────────────────────────────────────────────────────────────────────

    /**
     * "Before" is measured by running the legacy loop with a counter. It is
     * unconditional — every cell pays, including the ~55% that the very next
     * line throws away.
     */
    @Test
    fun `legacy land loop spent two transcendental calls on every cell`() {
        val (legacyData, legacyCount) = legacyPack(2)
        val sink = RecordingSink(legacyCount)
        trigCalls = 0
        val visible = legacyProject(
            data = legacyData,
            count = legacyCount,
            cosLat = 1f,
            sinLat = 0f,
            lonRad = 0f,
            cellPx = 12.4f,
            sink = sink,
        )
        assertEquals(21_339, legacyCount)
        assertEquals(42_678, trigCalls)
        // At this camera 11,714 cells survive, so 9,625 cells — 19,250 of the
        // 42,678 transcendental calls, 45% — were computed purely to be thrown
        // away one line later. At the worst camera in `visible quad counts per
        // camera are pinned` only 7,623 survive and the waste reaches 64%.
        assertEquals(11_714, visible)
        assertEquals(2 * legacyCount, trigCalls)
        assertTrue(
            "the cull should discard a large fraction, but kept $visible of $legacyCount",
            visible < (legacyCount * 3) / 5,
        )
    }

    /**
     * "After" is zero, and it has to be pinned structurally: a counter in
     * production code would be the wrong trade, and a numeric assertion on a
     * function that has no counter can only ever be vacuous. So brace-match the
     * shipped function out of the shipped file and refuse to find trigonometry
     * in it.
     *
     * This is the guard that survives the next refactor. Someone reintroducing
     * `sin(lam)` into the hot loop breaks the build here, not on a user's phone.
     */
    @Test
    fun `per-frame land projection contains no transcendental call`() {
        val body = shippedFunctionBody(
            "app/src/main/java/app/birdo/vpn/ui/components/GlobeGeometry.kt",
            "fun projectLandQuads(",
        )
        // Vacuity guard: prove we extracted the real loop and not an empty span.
        assertTrue("extracted body is too short to be the loop: $body", body.length > 400)
        assertTrue("extracted body is not the land loop", body.contains("sink.quad("))
        assertTrue("extracted body is missing the cull", body.contains("FRONT_FACE_EPS"))

        val banned = listOf(
            "sin(", "cos(", "tan(", "asin(", "acos(", "atan(", "atan2(",
            "sqrt(", "hypot(", "pow(", "exp(", "ln(", "log(", "Math.",
        )
        for (call in banned) {
            assertFalse(
                "projectLandQuads must stay free of transcendental work, found `$call`",
                body.contains(call),
            )
        }
    }

    /**
     * The cull now precedes the work only visible cells need, so the saving is
     * proportional to what is actually thrown away. Pin the ordering: `sx`
     * (needed only for on-screen position and lighting) must be computed AFTER
     * the `continue`.
     */
    @Test
    fun `back face cull runs before the per-cell work`() {
        val body = shippedFunctionBody(
            "app/src/main/java/app/birdo/vpn/ui/components/GlobeGeometry.kt",
            "fun projectLandQuads(",
        )
        val cull = body.indexOf("if (tz <= FRONT_FACE_EPS) continue")
        val sx = body.indexOf("val sx =")
        val dot = body.indexOf("val dot =")
        assertTrue("cull line not found", cull > 0)
        assertTrue("sx computation not found", sx > 0)
        assertTrue("light dot product not found", dot > 0)
        assertTrue("`sx` is computed before the cull", sx > cull)
        assertTrue("the light dot product is computed before the cull", dot > cull)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. Path verbs per frame — the JNI cost that dominates the land pass
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Every surviving cell emits `moveTo` + 3x `lineTo` + `close` = five verbs
     * on an `android.graphics.Path`, i.e. five JNI crossings. These pins are
     * the before/after headline: same numbers at FULL quality (the render is
     * unchanged), a quarter of them on the lite path.
     */
    @Test
    fun `visible quad counts per camera are pinned`() {
        val packed = realMaskSamples(2)
        val cellPx = radius * packed.cellSizeRad
        val counts = mutableListOf<Int>()
        for (lonDeg in cameraLons) {
            val lonRad = lonDeg * (PI.toFloat() / 180f)
            val sink = RecordingSink(packed.count)
            counts += GlobeGeometry.projectLandQuads(
                samples = packed,
                cx = cx,
                cy = cy,
                radius = radius,
                cosLat = 1f,
                sinLat = 0f,
                cosLon = cos(lonRad),
                sinLon = sin(lonRad),
                cellPx = cellPx,
                sink = sink,
            )
        }
        assertEquals(
            listOf(11_714, 12_242, 12_647, 9_223, 8_081, 7_720, 7_623, 11_079),
            counts,
        )
        // The land pass therefore issues 5x these, i.e. 38k-64k Path verbs per
        // frame, ~1.2M-1.9M per second at 30 fps. That is the cost the lite
        // path exists to divide by four.
        assertEquals(38_115, counts.min() * 5)
        assertEquals(63_235, counts.max() * 5)
    }

    @Test
    fun `lite quality quarters the quad count for the same painted area`() {
        val full = realMaskSamples(GlobeQuality.FULL.landStride)
        val lite = realMaskSamples(GlobeQuality.LITE.landStride)

        val lonRad = 180f * (PI.toFloat() / 180f)
        fun visible(s: LandSamples): Int = GlobeGeometry.projectLandQuads(
            samples = s,
            cx = cx,
            cy = cy,
            radius = radius,
            cosLat = 1f,
            sinLat = 0f,
            cosLon = cos(lonRad),
            sinLon = sin(lonRad),
            cellPx = radius * s.cellSizeRad,
            sink = RecordingSink(s.count),
        )

        val fullVisible = visible(full)
        val liteVisible = visible(lite)
        // 8,081 quads -> 2,022: 25.0% of the geometry, i.e. 40,405 Path verbs
        // (JNI crossings) per frame down to 10,110.
        assertEquals(8_081, fullVisible)
        assertEquals(2_022, liteVisible)
        assertTrue(
            "lite should emit at most a third of full, got $liteVisible vs $fullVisible",
            liteVisible * 3 <= fullVisible,
        )

        // Fill rate is deliberately unchanged: the square grows with the stride,
        // so the same disc area is painted with a quarter of the geometry. If
        // this ratio drifts, the lite path starts leaving holes in continents.
        val ratio = lite.cellSizeRad / full.cellSizeRad
        assertEquals(
            GlobeQuality.LITE.landStride.toFloat() / GlobeQuality.FULL.landStride.toFloat(),
            ratio,
            1e-6f,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. Low-end trigger
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `low end path triggers on the memory tier and never on the API level`() {
        assertSame(GlobeQuality.LITE, GlobeQuality.forDevice(isLowRamDevice = true, memoryClassMb = 512))
        assertSame(GlobeQuality.LITE, GlobeQuality.forDevice(isLowRamDevice = false, memoryClassMb = 96))
        assertSame(GlobeQuality.LITE, GlobeQuality.forDevice(isLowRamDevice = false, memoryClassMb = 128))
        assertSame(GlobeQuality.FULL, GlobeQuality.forDevice(isLowRamDevice = false, memoryClassMb = 192))
        assertSame(GlobeQuality.FULL, GlobeQuality.forDevice(isLowRamDevice = false, memoryClassMb = 512))

        // FULL must be byte-for-byte the render we shipped in 1.4.20, or this
        // change is a visual redesign wearing a performance label.
        assertEquals(2, GlobeQuality.FULL.landStride)
        assertEquals(90, GlobeQuality.FULL.starCount)
        assertEquals(33L, GlobeQuality.FULL.frameIntervalMs)
        assertTrue(GlobeQuality.FULL.twinkle)
        assertTrue(GlobeQuality.FULL.sunHotspot)
        assertFalse(GlobeQuality.FULL.lite)

        // And the API level must never be the proxy for speed.
        val source = File(
            repoRoot,
            "app/src/main/java/app/birdo/vpn/ui/components/WorldGlobe.kt",
        ).readText()
        assertTrue("WorldGlobe.kt not found or empty", source.length > 1_000)
        assertFalse(
            "the globe must not branch on Build.VERSION",
            source.contains("Build.VERSION"),
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // 6. Shader construction
    // ─────────────────────────────────────────────────────────────────────

    /**
     * `Brush.radialGradient` caches its native shader inside the brush
     * INSTANCE, so a fresh instance per frame defeats the cache and rebuilds a
     * native shader every time — six of them per frame, ~180/second at 30 fps.
     * Every gradient must therefore be created inside a [BrushSlot] builder.
     */
    @Test
    fun `every radial gradient is built inside the frame to frame brush cache`() {
        val source = File(
            repoRoot,
            "app/src/main/java/app/birdo/vpn/ui/components/WorldGlobe.kt",
        ).readText()
        assertTrue("WorldGlobe.kt not found or empty", source.length > 1_000)

        val gradients = source.indicesOf("Brush.radialGradient(")
        // `.obtain(cx` rather than `.obtain(` so the extension declaration itself
        // (`fun BrushSlot.obtain(`) is not counted as a call site.
        val obtains = source.indicesOf(".obtain(cx")
        assertEquals("the renderer paints six radial gradients", 6, gradients.size)
        assertEquals("every one of them must come from a cache slot", 6, obtains.size)

        for (g in gradients) {
            val nearestObtain = obtains.filter { it < g }.maxOrNull()
            assertNotNull("a gradient at offset $g is built outside any cache slot", nearestObtain)
            assertTrue(
                "a gradient at offset $g is too far from its cache slot to be inside it",
                g - nearestObtain!! < 200,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 7. Star twinkle
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The twinkle used to cost one `sin` per star per frame (90 of them). It is
     * now one angle addition per star against two per-frame `sin`/`cos`. Pin
     * the identity, because getting it subtly wrong would make the whole sky
     * pulse in lockstep instead of individually — visible, and not obviously
     * attributable to a performance change months later.
     */
    @Test
    fun `star twinkle angle addition reproduces the direct sine`() {
        var worst = 0f
        var samples = 0
        for (step in 0 until 120) {
            val phase01 = step / 120f
            val angle = phase01 * (2.0 * PI).toFloat()
            val sinA = sin(angle)
            val cosA = cos(angle)
            for (bStep in 0 until 40) {
                val b = bStep / 40f
                val starPhase = b * 13.7f
                val direct = 0.55f + 0.45f * sin(angle + starPhase)
                val folded = 0.55f + 0.45f * (sinA * cos(starPhase) + cosA * sin(starPhase))
                worst = max(worst, abs(direct - folded))
                samples++
            }
        }
        assertEquals(4_800, samples)
        assertTrue("worst twinkle deviation $worst exceeds 1e-5", worst < 1e-5f)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 8. The animation actually stops
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Nothing should be drawing while nothing is looking, and nothing should be
     * spinning for a user who has turned animation off. Both are single lines
     * that a refactor removes without any visible symptom on the developer's
     * unlocked, animations-on device — which is exactly why they are pinned.
     */
    @Test
    fun `the animation clock is gated on lifecycle and on the animator scale`() {
        val source = File(
            repoRoot,
            "app/src/main/java/app/birdo/vpn/ui/components/WorldGlobe.kt",
        ).readText()
        assertTrue("WorldGlobe.kt not found or empty", source.length > 1_000)

        assertTrue(
            "the clock must stop when the app is stopped",
            source.contains("Lifecycle.Event.ON_STOP -> started = false"),
        )
        assertTrue(
            "the clock must restart when the app is started",
            source.contains("Lifecycle.Event.ON_START ->"),
        )
        assertTrue(
            "the globe must honour the system animator duration scale",
            source.contains("ValueAnimator.areAnimatorsEnabled()"),
        )
        assertTrue(
            "both gates must feed the single `animating` flag",
            source.contains("val animating = started && animatorsEnabled"),
        )
        assertTrue(
            "the frame driver must be keyed on `animating` so it is cancelled",
            source.contains("LaunchedEffect(animating, frameIntervalMs)"),
        )
        assertTrue(
            "the frame driver must bail out when not animating",
            source.contains("if (!animating) return@LaunchedEffect"),
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun String.indicesOf(needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var from = indexOf(needle)
        while (from >= 0) {
            out += from
            from = indexOf(needle, from + 1)
        }
        return out
    }

    /**
     * Removes `//` line comments and block comments from Kotlin source, so a
     * prose mention of `cos(x)` cannot make a scan lie in either direction.
     */
    private fun stripComments(src: String): String {
        val noBlocks = src.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        return noBlocks.lineSequence()
            .map { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
            .joinToString("\n")
    }

    /**
     * Extracts the brace-matched, comment-stripped body of a shipped Kotlin
     * function so a scan can be scoped to the function instead of the whole file.
     */
    private fun shippedFunctionBody(relativePath: String, signatureStart: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(relativePath + " does not exist at " + file.absolutePath, file.isFile)
        val text = file.readText()
        val sigIdx = text.indexOf(signatureStart)
        assertTrue("signature [$signatureStart] not found in $relativePath", sigIdx >= 0)
        // The parameter list contains no braces, so the first `{` after the
        // signature is the body brace.
        val open = text.indexOf('{', sigIdx)
        assertTrue("no body brace found after [$signatureStart]", open >= 0)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    // Comments are stripped: a KDoc or an inline `// cos(x)`
                    // note would otherwise make the transcendental scan lie in
                    // whichever direction happened to be convenient.
                    if (depth == 0) return stripComments(text.substring(open + 1, i))
                }
            }
            i++
        }
        error("unbalanced braces after [$signatureStart] in $relativePath")
    }
}
