package app.birdo.vpn.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner decisions of 2026-10-07 that live in UI wiring, pinned from the source
 * in the style of OverhaulSourceGuardTest.
 */
class OwnerDecisionGuardTest {

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

    // ── MR-1519: the globe stops while connected, as on Windows ──────────

    @Test
    fun `the globe holds still while connected`() {
        assertTrue(
            "HomeScreen no longer stops the globe while connected",
            source("$main/ui/screen/HomeScreen.kt").contains("autoRotate = !isConnected"),
        )
        // Stopping only the spin changes nothing a connected user sees: with a
        // server selected, the focus already overrides it. The clock has to stop.
        assertTrue(
            "WorldGlobe's animation clock no longer stops when autoRotate is false",
            source("$main/ui/components/WorldGlobe.kt")
                .contains("val animating = rememberDecorativeMotionAllowed() && autoRotate"),
        )
    }

    // ── MR-1520: no "Secure Connection" badge on Login ───────────────────

    @Test
    fun `Login has no Secure Connection badge`() {
        val strings = source("app/src/main/res/values/strings.xml")
        assertFalse(strings.contains("name=\"login_status_badge\""))
        assertFalse(strings.contains(">Secure Connection<"))
        assertFalse(strings.contains("sovereign network", ignoreCase = true))
        assertFalse(source("$main/ui/screen/LoginScreen.kt").contains("login_status_badge"))
    }

    // ── MR-1518: no Quantum chip on Home, on any client ──────────────────

    @Test
    fun `Home has no Quantum chip on iOS or Android`() {
        // The chip stays removed everywhere (desktop dropped it in June). The
        // Quantum Protection toggle in Settings is not the chip and stays.
        val home = source("iosApp/iosApp/Views/HomeView.swift")
        assertFalse("iOS Home still shows the Quantum chip", Regex("""StatusBadgePill\(\s*"Quantum"""").containsMatchIn(home))
        assertFalse(home.contains("Quantum protection active"))
        assertFalse("iOS Home still reads quantumActive", home.contains("vpnVM.quantumActive"))
        assertFalse(source("$main/ui/screen/HomeScreen.kt").contains("quantum", ignoreCase = true))
    }

    // ── MR-1094: the iOS App Lock is real when on (owner, 2026-10-28) ──────

    @Test
    fun `the Face ID and Touch ID purpose strings name App Lock and the account number`() {
        // iOS shows this string in the system prompt the first time Face ID is
        // asked for: turning App Lock on, or revealing the account number.
        val purpose = Regex("""<key>NSFaceIDUsageDescription</key>\s*<string>([^<]*)</string>""")
        for (plist in listOf("iosApp/iosApp/Info.plist", "iosApp/iosApp/Info-macOS.plist")) {
            val text = purpose.find(source(plist))?.groupValues?.get(1)
            assertTrue("$plist has no NSFaceIDUsageDescription", text != null)
            assertTrue("$plist: \"$text\" does not name the setting it belongs to", text!!.contains("App Lock"))
            assertTrue("$plist: \"$text\" does not say it guards the account number", text.contains("account number"))
            assertFalse("$plist: \"$text\" still names the old cover", text.contains("Hide App Contents"))
        }
        // A localised copy would show its own text in the prompt.
        val localized = File(repoRoot, "iosApp").walkTopDown()
            .filter { it.isFile && it.extension == "strings" && it.readText().contains("NSFaceIDUsageDescription") }
            .toList()
        localized.forEach { assertFalse("${it.path} names the old cover", it.readText().contains("Hide App Contents")) }
    }

    @Test
    fun `the iOS App Lock is real when on and the account number asks first`() {
        val ios = "iosApp/iosApp"
        // The gate can switch the setting off itself (passcode removed).
        assertTrue(source("$ios/BirdoVPNApp.swift").contains("BiometricGate(enabled: \$settingsVM.biometricLockEnabled)"))
        val gate = source("$ios/Views/BiometricGate.swift")
        assertTrue("the gate no longer decides from AppLockPolicy", gate.contains("AppLockPolicy.reconcile("))
        assertTrue(gate.contains("AppLockPolicy.covered("))
        assertTrue(gate.contains("AppLockPolicy.locked(after:"))
        // Nothing shown: not visible, not touchable, not reachable by VoiceOver
        // or a hardware keyboard.
        listOf(".opacity(covered ? 0 : 1)", ".allowsHitTesting(!covered)", ".disabled(covered)", ".accessibilityHidden(covered)")
            .forEach { assertTrue("the covered content lost $it", gate.contains(it)) }
        assertFalse("the gate still fails open", gate.contains("fail open"))
        // Face ID or Touch ID with the passcode fallback, never biometrics alone.
        // Code only: the file's own doc comment names the policy it must not use.
        val auth = source("$ios/Services/DeviceOwnerAuth.swift").lines()
            .filterNot { it.trimStart().startsWith("//") }.joinToString("
")
        assertTrue(auth.contains("evaluatePolicy(.deviceOwnerAuthentication,"))
        assertFalse(auth.contains("deviceOwnerAuthenticationWithBiometrics"))
        // Owner: the tokens are NOT tied to Face ID.
        val keychain = source("$ios/Services/KeychainService.swift")
        assertFalse(keychain.contains("biometryCurrentSet") || keychain.contains(".userPresence"))
        // Settings: turning it on asks for the owner first; the copy is the real one.
        val settings = source("$ios/Views/SettingsView.swift")
        assertTrue(settings.contains("title: \"App Lock\""))
        assertFalse(settings.contains("protects no data"))
        assertTrue("turning App Lock on does not authenticate first", settings.contains("DeviceOwnerAuth.authenticate("))
        // Profile: both the reveal and the copy ask; the one-time "save your
        // number" screen after account creation is exempt.
        val profile = source("$ios/Views/ProfileView.swift")
        assertTrue(Regex("""DeviceOwnerAuth\.confirmReveal\(""").findAll(profile).count() >= 2)
        assertFalse(source("$ios/Views/LoginView.swift").contains("DeviceOwnerAuth"))
        val project = source("iosApp/project.yml")
        assertTrue(project.contains("- path: iosApp/Services/AppLockPolicy.swift"))
        assertTrue(File(repoRoot, "iosApp/BirdoVPNTests/AppLockPolicyTests.swift").isFile)
    }
}
