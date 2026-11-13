package app.birdo.vpn

import app.birdo.vpn.utils.NativeLibraryVerifier
import app.birdo.vpn.utils.RootDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F-968-02..04: saved account number, signer selection, installer and Play-cert trust. */
class F968LockAndSignerTest {

    private fun repoFile(rel: String): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("no repo root")
        val f = File(dir, rel)
        assertTrue("missing $rel", f.isFile)
        return f
    }

    @Test
    fun `login screen keeps the account number out of saved state`() {
        val text = repoFile("app/src/main/java/app/birdo/vpn/ui/screen/LoginScreen.kt").readText()
        assertFalse(Regex("""anonId\s+by\s+rememberSaveable""").containsMatchIn(text))
        assertTrue(Regex("""var\s+anonId\s+by\s+remember\s*\{""").containsMatchIn(text))
    }

    private val old = byteArrayOf(1, 2, 3)
    private val current = byteArrayOf(9, 9, 9)

    @Test
    fun `rotated history uses the current (last) signer`() {
        assertArrayEquals(current, RootDetector.selectCurrentSignerCert(false, null, listOf(old, current)))
    }

    @Test
    fun `single entry history is that entry, empty is null`() {
        assertArrayEquals(current, RootDetector.selectCurrentSignerCert(false, null, listOf(current)))
        assertNull(RootDetector.selectCurrentSignerCert(false, null, emptyList()))
        assertNull(RootDetector.selectCurrentSignerCert(false, null, null))
    }

    @Test
    fun `multiple signers keeps the first contents signer`() {
        assertArrayEquals(old, RootDetector.selectCurrentSignerCert(true, listOf(old, current), listOf(current)))
    }

    @Test
    fun `spoofed installer is not a strict play install on API 30 plus`() {
        val vending = "com.android.vending"
        assertTrue(RootDetector.isPlayInstall(vending, vending, 34))
        assertFalse(RootDetector.isPlayInstall(vending, null, 34))
        assertFalse(RootDetector.isPlayInstall(vending, "com.android.shell", 34))
        assertFalse(RootDetector.isPlayInstall("com.example.store", vending, 34))
        assertTrue(RootDetector.isPlayInstall(vending, null, 29))
        assertFalse(RootDetector.isPlayInstall(null, null, 29))
    }

    private val upload = "F1:67:8A:E9:8A:19:96:54:B1:A7:E1:A2:1F:38:61:CC:6B:EB:74:90:B7:3F:08:68:5E:90:7A:55:A8:1F:2A:A9"
    private val play = RootDetector.PLAY_APP_SIGNING_SHA256
    private val foreign = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"

    @Test
    fun `play app signing cert is the documented app signing line, not the upload key`() {
        assertTrue(play != upload)
        val doc = repoFile("docs/PLAY-APP-SIGNING.md").readText()
        val section = doc.substringAfter("### App signing key certificate").substringBefore("### Upload key certificate")
        assertTrue(section.contains(play))
    }

    @Test
    fun `a play cert signed package is trusted whatever the installer or initiator`() {
        val baked = setOf(upload)
        assertTrue(RootDetector.isSignatureTrusted(play, baked, installedFromPlay = false))
        assertFalse(RootDetector.isTampered(baked, play, installedFromPlayStrict = false))
        assertTrue(RootDetector.isSignatureTrusted(upload, baked, installedFromPlay = false))
    }

    @Test
    fun `unknown cert is trusted for native libs on installer of record alone`() {
        val baked = setOf(upload)
        assertTrue(RootDetector.isSignatureTrusted(foreign, baked, installedFromPlay = true))
        assertFalse(RootDetector.isSignatureTrusted(foreign, baked, installedFromPlay = false))
        assertFalse(RootDetector.isSignatureTrusted(null, baked, installedFromPlay = true))
    }

    @Test
    fun `a null or foreign initiator affects only the warning never the native-lib decision`() {
        val baked = setOf(upload)
        for (initiator in listOf(null, "com.android.shell", "com.samsung.android.smartswitchassistant")) {
            val strict = RootDetector.isPlayInstall("com.android.vending", initiator, 34)
            assertFalse(strict)
            assertTrue(RootDetector.isTampered(baked, foreign, installedFromPlayStrict = strict))
            val trusted = RootDetector.isSignatureTrusted(foreign, baked, installedFromPlay = true)
            assertTrue(
                NativeLibraryVerifier.decide(
                    isDebugBuild = false,
                    libraryPresent = true,
                    expectedHash = "abc",
                    actualHash = "abc",
                    signatureTrusted = trusted,
                ),
            )
        }
    }
}
