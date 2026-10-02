package app.birdo.vpn.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The launcher icon is adaptive (A2-025), built from raster layers only.
 */
class LauncherIconTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String =
        File(repoRoot, path).also { assertTrue("missing: $path", it.isFile) }.readText()

    private val main = "app/src/main/java/app/birdo/vpn"

    // ── A2-025 ───────────────────────────────────────────────────────────

    @Test
    fun `the launcher icon is adaptive with raster layers, and the in-app mark is its own drawable`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val xml = source("app/src/main/res/mipmap-anydpi/$name.xml")
            assertTrue(xml.contains("<adaptive-icon"))
            assertTrue("themed icons need a monochrome layer", xml.contains("<monochrome"))
        }
        for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            for (layer in listOf("background", "foreground", "monochrome")) {
                assertTrue(
                    "$density $layer",
                    File(repoRoot, "app/src/main/res/mipmap-$density/ic_launcher_$layer.png").isFile,
                )
            }
        }
        // Issue #150: a vector foreground crashed on inflate. Rasters only.
        assertFalse(File(repoRoot, "app/src/main/res/drawable/ic_launcher_foreground.xml").exists())
        val mark = source("$main/ui/components/AppIconMark.kt")
        assertFalse("the in-app mark must not draw the launcher icon", mark.contains("R.mipmap"))
    }
}
