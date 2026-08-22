package app.birdo.vpn.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

/**
 * Fill-rate pins for the Home-screen globe.
 *
 * `GlobeGeometryTest` counts transcendental calls and `Path` verbs — the CPU
 * half of the frame. It is silent about the other half. The globe paints the
 * same disc six times over per frame (atmosphere, ocean, continents, limb,
 * specular, night veil, sun hotspot), and on an older phone with a weak GPU and
 * a 1080p screen that alpha blending is the likelier binding constraint. A
 * pass can therefore be made three times more expensive without moving a single
 * number in the other test file.
 *
 * Two of those passes were painting large regions their own gradient defines as
 * fully transparent. That is measurable, deterministic waste, so it is pinned
 * here rather than left to a reviewer noticing a `radius =` argument.
 */
class GlobeOverdrawTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above " + File("").absolutePath)
        }
        dir
    }

    private val source: String by lazy {
        val text = File(
            repoRoot,
            "app/src/main/java/app/birdo/vpn/ui/components/WorldGlobe.kt",
        ).readText()
        assertTrue("WorldGlobe.kt not found or empty", text.length > 1_000)
        text
    }

    /** A representative phone canvas, matching GlobeGeometryTest's fixture. */
    private val radius = 496.8
    private val atmR = radius * 1.22

    /**
     * Reads a `private const val NAME = 0.78f` out of the real renderer.
     *
     * The saving below used to be computed from float literals typed into this
     * file, which made it a statement about the test's own constants: widen a
     * band in WorldGlobe.kt and the assertion still passed. The numbers have to
     * come from the code being measured or the measurement is decoration.
     */
    private fun stop(name: String): Double {
        val m = Regex("private const val " + name + " = ([0-9.]+)f").find(source)
        assertTrue(
            name + " is not a private const val Float in WorldGlobe.kt — the " +
                "fill-rate arithmetic below has nothing real to read",
            m != null,
        )
        return m!!.groupValues[1].toDouble()
    }

    private fun disc(r: Double) = PI * r * r
    private fun band(outer: Double, innerFraction: Double) =
        PI * (outer * outer - (outer * innerFraction) * (outer * innerFraction))

    // ─────────────────────────────────────────────────────────────────────
    // The two transparent-region passes
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the atmosphere ring is painted as a band and not as a disc`() {
        // If this reverts to `radius = atmR` with no `style = Stroke`, 61% of
        // the pass goes back to blending alpha-zero pixels.
        assertTrue(
            "the atmosphere inner stop constant is gone — the gradient and the " +
                "band it is painted through must share one number or they drift " +
                "apart and start clipping visible pixels",
            source.contains("private const val ATMOSPHERE_INNER_STOP = 0.78f"),
        )
        assertTrue(
            "the atmosphere band width must be derived from the gradient's own " +
                "first stop",
            source.contains("val atmBand = atmR * (1f - ATMOSPHERE_INNER_STOP)"),
        )
        assertTrue(
            "the atmosphere pass is a filled disc again",
            source.contains("radius = atmR - atmBand * 0.5f") &&
                source.contains("style = Stroke(width = atmBand)"),
        )
        assertTrue(
            "the gradient's first stop must be the shared constant, or the band " +
                "can be painted over a region that is no longer transparent",
            source.contains("ATMOSPHERE_INNER_STOP to atmosphere.copy(alpha = 0f)"),
        )
    }

    @Test
    fun `the limb vignette is painted as a band and not as a disc`() {
        assertTrue(
            "the limb inner stop constant is gone",
            source.contains("private const val LIMB_INNER_STOP = 0.65f"),
        )
        assertTrue(
            "the limb band width must be derived from the gradient's own stop",
            source.contains("val limbBand = radius * (1f - LIMB_INNER_STOP)"),
        )
        assertTrue(
            "the limb pass is a filled disc again",
            source.contains("radius = radius - limbBand * 0.5f") &&
                source.contains("style = Stroke(width = limbBand)"),
        )
        assertTrue(
            "the limb gradient's transparent stop must be the shared constant",
            source.contains("LIMB_INNER_STOP to Color.Transparent"),
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // The saving, as a number
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `dropping the transparent regions removes about a million blends a frame`() {
        // Both inner stops are read out of WorldGlobe.kt, so widening either
        // band in the renderer moves these numbers and this test fails for a
        // real reason instead of restating its own literals.
        val atmosphereStop = stop("ATMOSPHERE_INNER_STOP")
        val limbStop = stop("LIMB_INNER_STOP")
        assertEquals("the atmosphere band moved", 0.78, atmosphereStop, 1e-9)
        assertEquals("the limb band moved", 0.65, limbStop, 1e-9)

        val atmosphereBefore = disc(atmR)
        val atmosphereAfter = band(atmR, atmosphereStop)
        val limbBefore = disc(radius)
        val limbAfter = band(radius, limbStop)

        val saved = (atmosphereBefore - atmosphereAfter) + (limbBefore - limbAfter)
        assertEquals(
            "the pixels saved per frame moved — re-derive the claim in the " +
                "WorldGlobe kdoc before changing this number",
            1_029_700.0,
            saved,
            1_000.0,
        )

        // Against the rest of the frame: the remaining alpha-blended passes.
        val specular = disc(radius * 0.55)
        val ocean = disc(radius)
        val night = disc(radius)
        val sun = disc(radius)
        val blendedAfter =
            atmosphereAfter + ocean + limbAfter + specular + night + sun
        val blendedBefore =
            atmosphereBefore + ocean + limbBefore + specular + night + sun

        // 4.49 M -> 3.46 M alpha-blended pixels across the six gradient passes.
        assertTrue(
            "expected the gradient passes to fall by ~23%, got " +
                "${"%.1f".format(100 * (1 - blendedAfter / blendedBefore))}%",
            (1 - blendedAfter / blendedBefore) in 0.21..0.25,
        )

        // And against the whole blended frame, which also carries the continent
        // pass (~1.05 M painted pixels at FULL quality, from the 8,081-quad
        // camera GlobeGeometryTest pins, squares of side radius*cellSizeRad
        // foreshortened by 0.55..1.0). Quoting only the 23% would overstate it.
        val land = 1_050_000.0
        assertTrue(
            "expected the whole blended frame to fall by ~19%, got " +
                "${"%.1f".format(100 * (1 - (blendedAfter + land) / (blendedBefore + land)))}%",
            (1 - (blendedAfter + land) / (blendedBefore + land)) in 0.17..0.21,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // The passes that must NOT get this treatment
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the sun and night discs stay full size because they are clips`() {
        // Both gradients are centred on the sun / anti-sun point via a canvas
        // `translate`, while the circle they are painted through stays centred
        // on the GLOBE. That circle is therefore not just a fill region, it is
        // what keeps the glow inside the disc. Shrinking either one to its
        // gradient radius — the obvious "same optimisation again" — would clip
        // the glow whenever the sun is near the limb.
        //
        // This looked like a third free win and was not. Pinned so the next
        // person does not have to re-derive that.
        val night = source.indexOf("val nightBrush = brushes.night.obtain")
        assertTrue("night veil pass not found", night > 0)
        val nightDraw = source.indexOf("drawCircle(", source.indexOf("translate(ndx, ndy)"))
        assertTrue(
            "the night veil is no longer drawn at the full disc radius — it is " +
                "the clip for an off-centre gradient, not a fill region",
            source.substring(nightDraw, nightDraw + 200).contains("radius = radius,"),
        )

        val sunDraw = source.indexOf("drawCircle(", source.indexOf("translate(sdx, sdy)"))
        assertTrue("sun hotspot pass not found", sunDraw > 0)
        assertTrue(
            "the sun hotspot is no longer drawn at the full disc radius — same " +
                "reason as the night veil above",
            source.substring(sunDraw, sunDraw + 220).contains("radius = radius,"),
        )
    }

    @Test
    fun `every full disc pass is still accounted for`() {
        // Vacuity guard, and a tripwire for a NEW pass. If someone adds a
        // seventh full-disc gradient the globe just got 775 k blended pixels a
        // frame heavier, and this test should be the thing that says so.
        val gradients = Regex("Brush\\.radialGradient\\(").findAll(source).count()
        assertEquals(
            "the renderer paints a different number of radial gradients than the " +
                "six this analysis covers — re-do the fill-rate arithmetic",
            6,
            gradients,
        )
    }
}
