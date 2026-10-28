package app.birdo.vpn.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-958 (owner decision 2026-10-28): androidx.security:security-crypto, which
 * is deprecated upstream with no successor, is out of the app, and with it the
 * Tink runtime it pulled in.
 *
 * It was kept only for the one-shot migration of a pre-1.4.28 install's token
 * store and BirdoPQ secret key into the app's own Keystore AES-GCM sealing
 * (LegacySecurityCrypto.kt, shipped in 1.4.28 to 1.4.34). What an older
 * install still has is now deleted unread (LegacyLeftovers.kt; see
 * TokenManagerTest and BirdoPqKeyStoreTest). This test keeps the library from
 * creeping back: not declared, not locked on any configuration (so not on
 * releaseRuntimeClasspath), and not imported by any app source.
 */
class SecurityCryptoRemovedTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun text(path: String): String =
        File(repoRoot, path).also { assertTrue("missing: $path", it.isFile) }.readText()

    @Test
    fun `the build does not declare security-crypto`() {
        assertFalse(
            "app/build.gradle.kts declares androidx.security:security-crypto again",
            "androidx.security:security-crypto" in text("app/build.gradle.kts"),
        )
    }

    @Test
    fun `no configuration locks security-crypto or Tink`() {
        val locked = text("app/gradle.lockfile").lines()
            .filter { it.startsWith("androidx.security:security-crypto:") || it.startsWith("com.google.crypto.tink:") }
            .map { it.substringBefore('=') }
        assertEquals(
            "app/gradle.lockfile still pins these; refresh it with --write-locks",
            emptyList<String>(),
            locked,
        )
    }

    @Test
    fun `no app source uses androidx security crypto`() {
        val users = File(repoRoot, "app/src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { "androidx.security.crypto" in it.readText() }
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
            .toList()
        assertEquals(emptyList<String>(), users)
        assertFalse(File(repoRoot, "app/src/main/java/app/birdo/vpn/security/LegacySecurityCrypto.kt").exists())
    }
}
