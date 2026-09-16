package app.birdo.vpn.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iOS whole-row toggles must stay operable under VoiceOver.
 *
 * `SettingsRow`/`VpnToggleRow` are a Button wrapping a decorative Toggle
 * (`allowsHitTesting(false)`): sighted users tap the row, the Button's action
 * flips the preference. `.accessibilityRepresentation` REPLACES the row's
 * accessibility with the view it returns, so assistive-technology activation
 * goes to THAT view's binding and the Button's action is never reached.
 *
 * A `.constant(...)` binding has a no-op setter. Putting one in the
 * representation — which is exactly what the first cut of the BirdoShield gate
 * did, to make a gated row read OFF while the stored preference stayed ON —
 * leaves the row visible, announced, focusable and impossible to change with
 * VoiceOver on. The regression is silent on the visible switch (it never
 * writes) and hits EVERY call site, gated or not: Local Network Sharing has no
 * gate at all and would have shipped permanently un-togglable.
 *
 * Why a JVM test reads Swift: iOS is never built in PR CI, this compiles
 * perfectly, and the un-hosted `BirdoVPNTests` bundle cannot compile a SwiftUI
 * View at all — so there is no iOS-side test that could hold it. Same device as
 * ReleaseLogGuardTest and BirdoShieldAvailabilityTest's layer-4 guard.
 *
 * Verified honestly: this was read from source on Windows. A simulator +
 * VoiceOver pass is still the real proof, and nothing here replaces it.
 */
class IosToggleRowAccessibilityTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun relative(file: File): String =
        file.absolutePath.replace(File.separatorChar, '/')
            .removePrefix(repoRoot.absolutePath.replace(File.separatorChar, '/') + "/")

    private fun swiftSources(): List<File> {
        val root = File(repoRoot, "iosApp")
        assertTrue("iosApp/ is missing — the walk below would be vacuous", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .filterNot { relative(it).contains("/build/") || relative(it).contains("/.build") }
            .toList()
    }

    /** Text between `openIndex`'s brace and its match, braces excluded. */
    private fun braceBlock(text: String, openIndex: Int): String {
        var depth = 0
        for (i in openIndex until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openIndex + 1, i)
                }
            }
        }
        error("unbalanced braces from offset $openIndex")
    }

    private fun lineOf(text: String, offset: Int) = text.substring(0, offset).count { it == '\n' } + 1

    @Test
    fun `no accessibilityRepresentation binds a control to a constant`() {
        var blocks = 0
        val offenders = mutableListOf<String>()
        for (file in swiftSources()) {
            val text = file.readText()
            var from = 0
            while (true) {
                val at = text.indexOf(".accessibilityRepresentation", from)
                if (at < 0) break
                val open = text.indexOf('{', at)
                assertTrue("${relative(file)}: .accessibilityRepresentation with no block", open > at)
                val block = braceBlock(text, open)
                blocks++
                if (".constant(" in block) {
                    offenders += "${relative(file)}:${lineOf(text, at)}"
                }
                from = open + block.length
            }
        }
        // Vacuity guard: SettingsView and VpnSettingsView each have one. A run
        // that finds none is a broken walker, not a clean tree.
        assertTrue("found no accessibilityRepresentation blocks at all — the scan is broken", blocks >= 2)
        assertEquals(
            "an accessibilityRepresentation bound to .constant(...) swallows VoiceOver " +
                "activation: the representation REPLACES the row's accessibility, so the " +
                "enclosing Button's action is unreachable and a no-op setter is the only " +
                "path left. Bind a real (or computed, write-through) binding and use " +
                "`.disabled(...)` to express non-interactivity. Sites: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `VpnToggleRow's shared binding writes through to the preference`() {
        val path = "iosApp/iosApp/Views/VpnSettingsView.swift"
        val file = File(repoRoot, path)
        assertTrue("$path is missing — this test would be vacuous", file.isFile)
        val text = file.readText()

        val row = text.indexOf("private struct VpnToggleRow")
        assertTrue("$path no longer declares VpnToggleRow", row > 0)
        val end = text.indexOf("private struct VpnRowText", row)
        assertTrue("could not bound the VpnToggleRow declaration", end > row)
        val body = text.substring(row, end)

        val binding = body.indexOf("private var shownBinding: Binding<Bool>")
        assertTrue(
            "$path: VpnToggleRow no longer exposes the write-through `shownBinding` the " +
                "representation needs",
            binding > 0,
        )
        val bindingBody = braceBlock(body, body.indexOf('{', binding))
        assertTrue(
            "$path: shownBinding's setter must assign to `isOn` — a getter-only or " +
                ".constant binding is the VoiceOver regression this guards",
            Regex("""self\.isOn\s*=\s*newValue""").containsMatchIn(bindingBody),
        )
        assertTrue(
            "$path: shownBinding's setter must refuse writes while the row is disabled, " +
                "the same guard the Button's action carries",
            "guard self.isEnabled else { return }" in bindingBody,
        )
        assertTrue(
            "$path: the accessibility representation must use shownBinding",
            Regex("""accessibilityRepresentation[\s\S]{0,400}?Toggle\(isOn: shownBinding\)""")
                .containsMatchIn(body),
        )
    }
}
