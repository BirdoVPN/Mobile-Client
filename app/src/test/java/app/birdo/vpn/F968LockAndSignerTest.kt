package app.birdo.vpn

import app.birdo.vpn.utils.RootDetector
import app.birdo.vpn.widget.WidgetLockGate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F-968-01..04: widget lock gate, saved account number, signer and installer checks. */
class F968LockAndSignerTest {

    private fun source(rel: String): String {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("no repo root")
        val f = File(dir, "app/src/main/java/app/birdo/vpn/$rel")
        assertTrue("missing $rel", f.isFile)
        return f.readText()
    }

    @Test
    fun `widget refuses disconnect on a locked device and allows it unlocked`() {
        assertTrue(WidgetLockGate.refusesDisconnect(deviceLocked = true))
        assertFalse(WidgetLockGate.refusesDisconnect(deviceLocked = false))
    }

    @Test
    fun `widget DISCONNECT branch goes through the lock gate`() {
        val text = source("widget/BirdoWidget.kt")
        val branch = text.substringAfter("QuickToggle.Action.DISCONNECT ->").substringBefore("QuickToggle.Action.OPEN_APP")
        assertTrue(branch.contains("WidgetLockGate.refusesDisconnect(isDeviceLocked(context))"))
        assertTrue(branch.contains("manager.requestDisconnect()"))
        assertTrue(
            "requestDisconnect must sit after the gate",
            branch.indexOf("refusesDisconnect") < branch.indexOf("requestDisconnect"),
        )
    }

    @Test
    fun `login screen keeps the account number out of saved state`() {
        val text = source("ui/screen/LoginScreen.kt")
        assertFalse(Regex("""anonId\s+by\s+rememberSaveable""").containsMatchIn(text))
        assertTrue(Regex("""var\s+anonId\s+by\s+remember\s*\{""").containsMatchIn(text))
    }

    private val old = byteArrayOf(1, 2, 3)
    private val current = byteArrayOf(9, 9, 9)

    @Test
    fun `rotated history uses the current (last) signer`() {
        assertArrayEquals(
            current,
            RootDetector.selectCurrentSignerCert(false, null, listOf(old, current)),
        )
    }

    @Test
    fun `single entry history is that entry, empty is null`() {
        assertArrayEquals(current, RootDetector.selectCurrentSignerCert(false, null, listOf(current)))
        assertNull(RootDetector.selectCurrentSignerCert(false, null, emptyList()))
        assertNull(RootDetector.selectCurrentSignerCert(false, null, null))
    }

    @Test
    fun `multiple signers keeps the first contents signer`() {
        assertArrayEquals(
            old,
            RootDetector.selectCurrentSignerCert(true, listOf(old, current), listOf(current)),
        )
    }

    @Test
    fun `spoofed installer name is not a play install on API 30 plus`() {
        val vending = "com.android.vending"
        assertTrue(RootDetector.isPlayInstall(vending, vending, 34))
        assertFalse(RootDetector.isPlayInstall(vending, "com.android.shell", 34))
        assertFalse(RootDetector.isPlayInstall(vending, null, 34))
        assertFalse(RootDetector.isPlayInstall("com.example.store", vending, 34))
        // Before API 30 only the installer of record exists.
        assertTrue(RootDetector.isPlayInstall(vending, null, 29))
        assertFalse(RootDetector.isPlayInstall(null, null, 29))
    }
}
