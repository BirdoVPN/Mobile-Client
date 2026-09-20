package app.birdo.vpn

import app.birdo.vpn.ui.viewmodel.AuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OPEN-WORK A5 — every SSO provider the app ACCEPTS must also be one the user
 * can actually reach, and vice versa.
 *
 * The defect this exists for is the one Android shipped with: the web broker
 * learned `apple` in birdo-web#519, iOS shipped native Sign in with Apple in
 * Mobile-Client#427, and Android did neither — `startSso` refused anything but
 * google/github and the login screen had no Apple button. Nothing failed. Both
 * halves compile perfectly on their own, so only a test that compares them
 * catches it.
 *
 * It reads `LoginScreen.kt` as source for the same reason
 * [QuickSelectGuardTest] reads Swift: the failure is a MISSING hand-written
 * call site, which is invisible to the compiler and to any test that only
 * exercises what is there.
 */
class SsoProviderParityTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        val text = file.readText()
        assertTrue("scan target $path is empty", text.length > 200)
        return text
    }

    private val loginScreen by lazy {
        source("app/src/main/java/app/birdo/vpn/ui/screen/LoginScreen.kt")
    }

    /** The providers the login screen actually offers a button for. */
    private val wiredInUi: Set<String> by lazy {
        Regex("""onSsoLogin\("([a-z]+)"\)""").findAll(loginScreen)
            .map { it.groupValues[1] }
            .toSet()
            .also {
                assertTrue(
                    "found no onSsoLogin(\"…\") call sites at all — the regex no " +
                        "longer matches how the buttons are written, so every " +
                        "assertion below would pass vacuously",
                    it.isNotEmpty(),
                )
            }
    }

    @Test
    fun `the allowlist is exactly the three the web broker supports`() {
        // Mirrors NATIVE_OAUTH_PROVIDERS in birdo-web/lib/native-oauth.ts.
        // Changing one side without the other is the whole point of this test:
        // a provider allowed here but not there sends the user out to a browser
        // that answers 400 and strands the app on a spinner.
        assertEquals(setOf("google", "github", "apple"), AuthViewModel.SSO_PROVIDERS)
    }

    @Test
    fun `every allowed provider has a button on the login screen`() {
        val missing = AuthViewModel.SSO_PROVIDERS - wiredInUi
        assertTrue(
            "accepted by startSso but unreachable in the UI: $missing — " +
                "add an OutlinedButton calling onSsoLogin(\"…\") in LoginScreen.kt",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every button on the login screen is an allowed provider`() {
        val unsupported = wiredInUi - AuthViewModel.SSO_PROVIDERS
        assertTrue(
            "offered in the UI but refused by startSso: $unsupported — tapping " +
                "these shows \"Unsupported sign-in provider\" and nothing else",
            unsupported.isEmpty(),
        )
    }

    @Test
    fun `each provider button carries its own distinct test tag`() {
        // A copy-pasted button that kept the tag it was cloned from makes the
        // UI tests assert the same button twice and the new one never at all.
        val tags = Regex("""testTag\(TestTags\.(LOGIN_SSO_[A-Z]+)\)""")
            .findAll(loginScreen).map { it.groupValues[1] }.toList()
        assertEquals(
            "one distinct LOGIN_SSO_* tag per provider button",
            wiredInUi.size, tags.toSet().size,
        )
        assertEquals("a LOGIN_SSO_* tag is used twice: $tags", tags.size, tags.toSet().size)
    }

    @Test
    fun `each provider button takes its label from a string resource`() {
        // Hard-coded English here would ship untranslated to every locale the
        // Play listing claims, and Apple's wording is a brand requirement.
        val strings = source("app/src/main/res/values/strings.xml")
        wiredInUi.forEach { p ->
            assertTrue(
                "no <string name=\"login_sso_$p\"> for the $p button",
                strings.contains("\"login_sso_$p\""),
            )
            assertTrue(
                "the $p button does not reference R.string.login_sso_$p",
                loginScreen.contains("R.string.login_sso_$p"),
            )
        }
    }
}
