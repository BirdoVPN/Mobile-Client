package app.birdo.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A1-043: a privacy product declares only the permissions it uses. The two
 * removed here had no reader: nothing reads Wi-Fi state (SSIDs are
 * deliberately never read), and the app registers network callbacks but never
 * requests or binds a network, which is what CHANGE_NETWORK_STATE is for.
 */
class ManifestPermissionsTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found")
        }
        dir
    }

    private val manifest: String by lazy { File(repoRoot, "app/src/main/AndroidManifest.xml").readText() }

    private val sources: List<String> by lazy {
        File(repoRoot, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
    }

    private fun declares(permission: String) =
        Regex("""<uses-permission\s+android:name="android\.permission\.$permission"""").containsMatchIn(manifest)

    @Test
    fun `no Wi-Fi state permission, and nothing that would need it`() {
        assertFalse(declares("ACCESS_WIFI_STATE"))
        assertTrue("found ${sources.size} sources", sources.size > 50)
        assertFalse(sources.any { "WifiManager" in it || ".connectionInfo" in it })
    }

    @Test
    fun `no CHANGE_NETWORK_STATE, and no network is requested or bound`() {
        assertFalse(declares("CHANGE_NETWORK_STATE"))
        assertFalse(sources.any { ".requestNetwork(" in it || "bindProcessToNetwork(" in it })
        // The callbacks it does use need only this one.
        assertTrue(declares("ACCESS_NETWORK_STATE"))
    }
}
