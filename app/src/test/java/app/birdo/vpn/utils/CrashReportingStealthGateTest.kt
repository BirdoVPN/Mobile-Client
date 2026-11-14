package app.birdo.vpn.utils

import io.sentry.IConnectionStatusProvider.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-953 F5 — opted-in crash reports are never SENT while Stealth runs.
 *
 * During Stealth the app is outside its own tunnel and the Sentry SDK resolves
 * and connects with the network's own resolver from the real IP; it cannot use
 * the app's DoH. [CrashReporting.StealthHoldTransportGate] holds every send
 * (the SDK keeps the envelope in its on-disk cache) until Stealth ends, and
 * otherwise answers exactly as the SDK's own AndroidTransportGate.
 */
class CrashReportingStealthGateTest {

    private fun gate(stealth: Boolean, status: ConnectionStatus) =
        CrashReporting.StealthHoldTransportGate({ stealth }, { status })

    @Test
    fun `nothing is sent while stealth runs whatever the network says`() {
        for (status in ConnectionStatus.values()) {
            assertFalse("sent during Stealth with $status", gate(stealth = true, status = status).isConnected())
        }
    }

    @Test
    fun `with stealth off it answers as the SDK AndroidTransportGate does`() {
        assertTrue(gate(stealth = false, status = ConnectionStatus.CONNECTED).isConnected())
        assertTrue(gate(stealth = false, status = ConnectionStatus.UNKNOWN).isConnected())
        assertTrue(gate(stealth = false, status = ConnectionStatus.NO_PERMISSION).isConnected())
        assertFalse(gate(stealth = false, status = ConnectionStatus.DISCONNECTED).isConnected())
    }

    /** Read on every check, so reports held during Stealth go once it ends. */
    @Test
    fun `the stealth flag is read live on every check`() {
        var stealth = true
        var reads = 0
        val gate = CrashReporting.StealthHoldTransportGate({ reads++; stealth }, { ConnectionStatus.CONNECTED })
        assertFalse(gate.isConnected())
        stealth = false
        assertTrue(gate.isConnected())
        assertEquals(2, reads)
    }

    /**
     * The wiring: BirdoApp.initSentry installs the gate with the LIVE Stealth
     * flag and the SDK's connection status. Without it the SDK's default gate
     * sends during Stealth.
     */
    @Test
    fun `initSentry installs the stealth gate`() {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("repo root not found")
        val app = File(dir, "app/src/main/java/app/birdo/vpn/BirdoApp.kt").readText()
        val init = app.substringAfter("SentryAndroid.init(this) { options ->")
        assertTrue("initSentry no longer configures Sentry", init.length < app.length)
        assertTrue(init.contains("options.transportGate = CrashReporting.StealthHoldTransportGate("))
        assertTrue(init.contains("stealthActive = { app.birdo.vpn.service.BirdoVpnService.stealthActive }"))
        assertTrue(init.contains("connectionStatus = { options.connectionStatusProvider.connectionStatus }"))
        assertEquals(
            "a second transportGate assignment would replace the stealth gate",
            1,
            Regex("""options\.transportGate\s*=""").findAll(app).count(),
        )
    }
}
