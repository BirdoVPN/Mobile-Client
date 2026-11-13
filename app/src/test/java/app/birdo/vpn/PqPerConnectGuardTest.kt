package app.birdo.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-703 / MR-1742: BirdoPQ keys are per connect and held in memory only, on
 * Android AND iOS. The Swift half cannot compile into the un-hosted iOS test
 * bundle (it imports BirdoPQNative), and Apple CI runs on dispatch and tags,
 * so this reads the sources on every PR the way ReleaseLogGuardTest does.
 *
 * It does not prove the behaviour (BirdoPqManagerTest does, on Android); it
 * pins that nothing writes a PQ secret key at rest again.
 */
class PqPerConnectGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found")
        }
        dir
    }

    private fun source(path: String): String {
        val f = File(repoRoot, path)
        assertTrue("scan target is missing: $path", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    /** The file minus `//` and `*` comment lines, so prose that names a removed API does not trip a guard. */
    private fun code(path: String) =
        source(path).lines().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }
            .joinToString("\n")

    @Test
    fun `Android no longer persists a PQ keypair or seals one in the Keystore`() {
        assertFalse(
            "BirdoPqKeyStore.kt is back",
            File(repoRoot, "app/src/main/java/app/birdo/vpn/service/BirdoPqKeyStore.kt").exists(),
        )
        val manager = code("app/src/main/java/app/birdo/vpn/service/BirdoPqManager.kt")
        for (gone in listOf("BirdoPqKeyStore", "store.save(", "AesGcmSealer", "loadOrGenerateKeypair", "storedKeyUsable", "uploadedPublicKey")) {
            assertFalse("BirdoPqManager mentions $gone again", gone in manager)
        }
        assertTrue("a keypair must be generated per getClientPublicKeyB64 call", "RosenpassNative.generateKeypair()" in manager)
        // The one purge entry points the callers use.
        assertTrue("fun discardKeypair(" in manager && "fun purgeLegacyKeypair(" in manager)
        val app = code("app/src/main/java/app/birdo/vpn/BirdoApp.kt")
        assertTrue("the app start must purge the legacy key", "BirdoPqManager.purgeLegacyKeypair(this)" in app)
        val device = code("app/src/main/java/app/birdo/vpn/data/auth/DeviceInfoProvider.kt")
        assertTrue("sign-out must purge", "BirdoPqManager.discardKeypair(context)" in device)
    }

    @Test
    fun `iOS no longer writes the PQ keypair to the Keychain`() {
        val manager = code("iosApp/iosApp/Services/BirdoPQManager.swift")
        for (gone in listOf("setHostData", "writeKeypairToKeychain", "readStoredBlob", "loadOrGenerateKeypair", "StoredPQKeyTriage", "uploadedPublicKey", "hostData(")) {
            assertFalse("BirdoPQManager mentions $gone again", gone in manager)
        }
        assertTrue("generateKeypair()" in manager)
        // The only Keychain call left is the delete of the legacy item.
        val keychainCalls = Regex("""KeychainService\.shared\.(\w+)""").findAll(manager).map { it.groupValues[1] }.toSet()
        assertTrue("only deleteHostData may touch the Keychain, got $keychainCalls", keychainCalls == setOf("deleteHostData"))
        assertFalse(
            "StoredPQKeyTriage.swift is back",
            File(repoRoot, "iosApp/iosApp/Services/StoredPQKeyTriage.swift").exists(),
        )
        // A secret key read from the Keychain by anything else would resurrect it.
        val keychain = code("iosApp/iosApp/Services/KeychainService.swift")
        assertFalse("something else stores .pqKeypair", Regex("""setHostData\([^)]*\.pqKeypair""").containsMatchIn(keychain))
    }
}
