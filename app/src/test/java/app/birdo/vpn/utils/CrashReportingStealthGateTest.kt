package app.birdo.vpn.utils

import io.sentry.IConnectionStatusProvider.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-953 F5 — opted-in crash reports are never SENT while Stealth runs, nor
 * after a Stealth session that ended without a tunnel or a user stop since.
 *
 * During Stealth the app is outside its own tunnel and the Sentry SDK resolves
 * and connects with the network's own resolver from the real IP; it cannot use
 * the app's DoH. [CrashReporting.StealthHoldTransportGate] holds every send
 * (the SDK keeps the envelope in its on-disk cache) while Stealth runs and
 * while the persisted hold is set, and otherwise answers exactly as the SDK's
 * own AndroidTransportGate. Review of #622: a crash during Stealth ends
 * Stealth, so the live flag alone let the report go at the next start, before
 * any tunnel existed.
 */
class CrashReportingStealthGateTest {

    private fun gate(stealth: Boolean, status: ConnectionStatus, held: Boolean = false) =
        CrashReporting.StealthHoldTransportGate({ stealth }, { held }, { status })

    @Test
    fun `nothing is sent while stealth runs whatever the network says`() {
        for (status in ConnectionStatus.values()) {
            assertFalse("sent during Stealth with $status", gate(stealth = true, status = status).isConnected())
        }
    }

    @Test
    fun `with no stealth and no hold it answers as the SDK AndroidTransportGate does`() {
        assertTrue(gate(stealth = false, status = ConnectionStatus.CONNECTED).isConnected())
        assertTrue(gate(stealth = false, status = ConnectionStatus.UNKNOWN).isConnected())
        assertTrue(gate(stealth = false, status = ConnectionStatus.NO_PERMISSION).isConnected())
        assertFalse(gate(stealth = false, status = ConnectionStatus.DISCONNECTED).isConnected())
    }

    /** Review of #622, item 9: the next start after a crash during Stealth. */
    @Test
    fun `a held stealth session holds every send with stealth off, whatever the network says`() {
        for (status in ConnectionStatus.values()) {
            assertFalse(
                "sent with Stealth off and the hold set, $status",
                gate(stealth = false, status = status, held = true).isConnected(),
            )
        }
    }

    /** Read on every check, so held reports go once Stealth ends and the hold is released. */
    @Test
    fun `the stealth flag and the hold are read live on every check`() {
        var stealth = true
        var held = true
        var stealthReads = 0
        val gate = CrashReporting.StealthHoldTransportGate(
            { stealthReads++; stealth },
            { held },
            { ConnectionStatus.CONNECTED },
        )
        assertFalse(gate.isConnected())
        stealth = false
        assertFalse("the hold must outlive the live flag", gate.isConnected())
        held = false
        assertTrue(gate.isConnected())
        assertEquals(3, stealthReads)
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    private fun source(path: String): String {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("repo root not found")
        return File(dir, path).readText().replace("\r\n", "\n")
    }

    /**
     * The wiring: BirdoApp.initSentry installs the gate with the LIVE Stealth
     * flag, the persisted hold and the SDK's connection status. Without it the
     * SDK's default gate sends during Stealth.
     */
    @Test
    fun `initSentry installs the stealth gate`() {
        val app = source("app/src/main/java/app/birdo/vpn/BirdoApp.kt")
        val init = app.substringAfter("SentryAndroid.init(this) { options ->")
        assertTrue("initSentry no longer configures Sentry", init.length < app.length)
        val install = init.substringAfter("options.setTransportGate(").substringBefore("\n            )")
        assertTrue("initSentry does not set a transport gate", install.length < init.length)
        assertTrue(install.contains("CrashReporting.StealthHoldTransportGate("))
        assertTrue(install.contains("stealthActive = { app.birdo.vpn.service.BirdoVpnService.stealthActive }"))
        assertTrue(install.contains("heldForStealth = { appPreferences.crashReportsHeldForStealth }"))
        assertTrue(install.contains("connectionStatus = { options.connectionStatusProvider.connectionStatus }"))
        assertEquals(
            "a second transport gate would replace the stealth gate",
            1,
            Regex("""options\.(setTransportGate\(|transportGate\s*=)""").findAll(app).count(),
        )
    }

    /**
     * Who sets and releases the hold (BirdoVpnService): set when Stealth
     * starts; released only by a verified tunnel without Stealth or by a stop
     * the user asked for. Nothing else writes it, so a crash, Xray exiting, a
     * re-dial or an error stop leaves it set.
     */
    @Test
    fun `the service sets the hold when stealth starts and releases it only for a plain tunnel or a user stop`() {
        val svc = source("app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt")

        val setter = svc.substringAfter("private fun setStealthActive(active: Boolean) {").substringBefore("\n    }")
        assertTrue(setter.contains("if (active) appPrefs.crashReportsHeldForStealth = true"))
        assertTrue(
            "the hold must be set before the flag, so no send slips between them",
            setter.indexOf("crashReportsHeldForStealth = true") < setter.indexOf("_stealthActiveFlow.getAndUpdate"),
        )

        val connected = svc.substringAfter("private fun publishConnected(handle: Int) {").substringBefore("\n    }")
        assertTrue(
            connected.contains(
                "if (!stealthActive && appPrefs.crashReportsHeldForStealth) appPrefs.crashReportsHeldForStealth = false",
            ),
        )

        val stop = svc.substringAfter("private fun stopTunnel(reason: VpnState.Error?, userInitiated: Boolean) {")
            .substringBefore("\n    }")
        assertTrue(
            stop.contains(
                "if (userInitiated && appPrefs.crashReportsHeldForStealth) appPrefs.crashReportsHeldForStealth = false",
            ),
        )

        assertEquals(
            "the hold is written in exactly three places (set on Stealth start, two releases)",
            3,
            Regex("""appPrefs\.crashReportsHeldForStealth = """).findAll(svc).count(),
        )
        assertEquals(
            "only the service writes the hold",
            0,
            Regex("""crashReportsHeldForStealth = """).findAll(source("app/src/main/java/app/birdo/vpn/BirdoApp.kt")).count(),
        )
    }
}
