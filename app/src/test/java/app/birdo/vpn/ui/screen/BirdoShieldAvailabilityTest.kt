package app.birdo.vpn.ui.screen

import app.birdo.vpn.data.model.ClientConfigResponse
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.ui.viewmodel.VpnUiState
import app.birdo.vpn.ui.viewmodel.nextDnsFilteringAvailable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BirdoShield (D18) FLEET GATE — the honesty rule for the VPN Settings toggle.
 *
 * birdo-web#465 added `dnsFilteringAvailable` to `GET /api/client-config`: it
 * is the backend's `DNS_FILTERING_ENABLED`, i.e. whether the fleet this account
 * dials does DNS filtering at all. While it is off the backend IGNORES the
 * per-device `dnsFiltering` connect flag and hands out the normal resolver, so
 * a toggle the user can switch ON is a lie about what the server will do — the
 * estate's "never render reassurance from missing data" rule.
 *
 * Three layers are covered here, all of them the REAL production code paths:
 *
 *  1. [birdoShieldRowState] — everything the row renders (switch position,
 *     interactivity, which subtitle). The composable derives all three from
 *     this one function, so testing it is testing the row.
 *  2. [nextDnsFilteringAvailable] — the ViewModel's defaulting rule: an error
 *     or an absent field must never produce `false`.
 *  3. The wire shape — `false` and "key absent" must stay distinguishable
 *     through the app's own Json instance.
 *  4. The WIRING — a source guard that the composable actually feeds (1) into
 *     the switch, its enabled state and its subtitle. Layers 1-3 all pass on a
 *     row left hard-coded `enabled = true`, which is the whole bug.
 *
 * Not an instrumented Compose test: this module has no Robolectric and
 * androidTest does not run in PR CI, so layer 4 reads the source the way
 * ReleaseLogGuardTest reads Swift.
 */
class BirdoShieldAvailabilityTest {

    // ── 1. The row ───────────────────────────────────────────────

    @Test
    fun `gate off disables the row, shows the reason and reads OFF`() {
        // Persisted preference is ON — the user turned BirdoShield on while the
        // gate was up. It must still read OFF now.
        val row = birdoShieldRowState(dnsFilteringEnabled = true, dnsFilteringAvailable = false)

        assertFalse("row must not read ON while the server ignores the flag", row.checked)
        assertFalse("row must not be switchable", row.enabled)
        assertTrue("row must explain why", row.unavailable)
    }

    @Test
    fun `gate off does not clear the stored preference`() {
        // The function is pure — it cannot write — and that is the guarantee:
        // the preference it was handed comes back unchanged the moment the gate
        // does, without the user having to remember to re-enable anything.
        val stored = true
        birdoShieldRowState(dnsFilteringEnabled = stored, dnsFilteringAvailable = false)
        assertTrue(stored)

        val restored = birdoShieldRowState(dnsFilteringEnabled = stored, dnsFilteringAvailable = true)
        assertTrue("the user's own choice returns with the gate", restored.checked)
        assertTrue(restored.enabled)
    }

    @Test
    fun `gate on leaves the row enabled and following the preference`() {
        val on = birdoShieldRowState(dnsFilteringEnabled = true, dnsFilteringAvailable = true)
        assertTrue(on.checked)
        assertTrue(on.enabled)
        assertFalse(on.unavailable)

        val off = birdoShieldRowState(dnsFilteringEnabled = false, dnsFilteringAvailable = true)
        assertFalse("an OFF preference stays off", off.checked)
        assertTrue("but the row is still switchable", off.enabled)
        assertFalse(off.unavailable)
    }

    /**
     * The default that matters. `null` is a cold start before the fetch lands,
     * a failed fetch, or a web deploy older than #465. Unknown is AVAILABLE:
     * hiding a feature that works because the client could not reach the web
     * app is the failure this flag exists to avoid, and it is the asymmetric
     * one — the backend refuses the flag anyway while the gate is off.
     */
    @Test
    fun `unknown gate leaves the row enabled`() {
        val row = birdoShieldRowState(dnsFilteringEnabled = true, dnsFilteringAvailable = null)
        assertTrue("unknown must not read as off", row.checked)
        assertTrue(row.enabled)
        assertFalse("and must not show the unavailable reason", row.unavailable)
    }

    @Test
    fun `the ui state starts unknown, not unavailable`() {
        assertNull(VpnUiState().dnsFilteringAvailable)
        assertTrue(birdoShieldRowState(true, VpnUiState().dnsFilteringAvailable).enabled)
    }

    // ── 2. The ViewModel's defaulting rule ───────────────────────

    @Test
    fun `a failed fetch keeps the current value and never yields false`() {
        assertNull(nextDnsFilteringAvailable(null, ApiResult.Error("Network error")))
        assertEquals(true, nextDnsFilteringAvailable(true, ApiResult.Error("timeout", 0)))
        // Even a 5xx from the web app must not flip a known-good gate off.
        assertEquals(true, nextDnsFilteringAvailable(true, ApiResult.Error("server error", 500)))
    }

    @Test
    fun `an absent field keeps the current value`() {
        val older = ApiResult.Success(ClientConfigResponse(dnsFilteringAvailable = null))
        assertNull(nextDnsFilteringAvailable(null, older))
        assertEquals(true, nextDnsFilteringAvailable(true, older))
    }

    @Test
    fun `an explicit value wins`() {
        assertEquals(
            false,
            nextDnsFilteringAvailable(null, ApiResult.Success(ClientConfigResponse(false))),
        )
        assertEquals(
            true,
            nextDnsFilteringAvailable(false, ApiResult.Success(ClientConfigResponse(true))),
        )
    }

    // ── 3. The wire shape ────────────────────────────────────────

    /**
     * Decoded through the app's OWN Json instance (the Retrofit converter's),
     * not a private `Json {}` here — a private one would keep passing while
     * `ignoreUnknownKeys` or `coerceInputValues` changed underneath it.
     */
    private val json: Json = NetworkModule.json

    @Test
    fun `false and absent stay distinguishable on the wire`() {
        assertEquals(
            true,
            json.decodeFromString<ClientConfigResponse>(
                """{"dnsFilteringAvailable":true}""",
            ).dnsFilteringAvailable,
        )
        assertEquals(
            false,
            json.decodeFromString<ClientConfigResponse>(
                """{"dnsFilteringAvailable":false}""",
            ).dnsFilteringAvailable,
        )
        assertNull(
            "a pre-#465 payload omits the key entirely — that is unknown, not off",
            json.decodeFromString<ClientConfigResponse>("""{"version":1}""").dnsFilteringAvailable,
        )
    }

    /**
     * The live payload carries cert pins, per-plan feature maps and consent copy
     * this client does not model. They must be ignored, not rejected: a strict
     * decode would turn every future web-side addition into a decode failure,
     * which the ViewModel treats exactly like a network error.
     */
    @Test
    fun `the unmodelled half of the real payload is ignored`() {
        val payload = """
            {
              "version": 1,
              "certPins": { "hosts": { "birdo.app": { "pins": [] } } },
              "dnsFilteringAvailable": false,
              "features": { "RECON": { "dnsFiltering": true, "stealthMode": false } },
              "consent": { "vpnDisclaimer": "...", "dataCollection": "..." },
              "minimumVersions": { "android": "1.0.0", "windows": "1.0.0" }
            }
        """.trimIndent()
        val cfg = json.decodeFromString<ClientConfigResponse>(payload)
        assertEquals(false, cfg.dnsFilteringAvailable)
        assertFalse(
            "and the decoded gate must drive the row",
            birdoShieldRowState(true, cfg.dnsFilteringAvailable).enabled,
        )
    }

    // -- 4. The wiring ------------------------------------------

    /**
     * Source guard: the composable must actually FEED [birdoShieldRowState] into
     * all three of the row's inputs.
     *
     * The pure function above cannot see a wiring mistake -- a row left
     * `enabled = true` or `checked = state.dnsFilteringEnabled` compiles, passes
     * every test above, and ships the exact lie this change exists to remove.
     * A Compose UI test would catch it, but this module has no Robolectric and
     * androidTest does not run in PR CI, so this reads the source instead, the
     * same way ReleaseLogGuardTest and BaselineProfileIntegrityTest pin things a
     * compiler cannot.
     */
    @Test
    fun `the BirdoShield row is driven by birdoShieldRowState, not by raw state`() {
        val src = File(repoRoot, "app/src/main/java/app/birdo/vpn/ui/screen/VpnSettingsScreen.kt")
        assertTrue("VpnSettingsScreen.kt not found at ${src.absolutePath}", src.isFile)
        val text = src.readText()

        val start = text.indexOf("birdoShieldRowState(state.dnsFilteringEnabled")
        assertTrue("the row must derive its state from birdoShieldRowState", start > 0)
        // The VpnToggle call that follows, ending at the row's own testTag.
        val end = text.indexOf(ROW_TEST_TAG, start)
        assertTrue("the BirdoShield VpnToggle call was not found after the derivation", end > start)
        val block = text.substring(start, end)

        assertTrue("checked must come from the derived state", "checked = shield.checked" in block)
        assertTrue("enabled must come from the derived state", "enabled = shield.enabled" in block)
        assertTrue(
            "the unavailable reason string must be selected from the derived state",
            "shield.unavailable" in block &&
                "R.string.vpn_settings_birdoshield_unavailable" in block,
        )
        assertFalse(
            "the switch must not read the raw preference, which ignores the gate",
            "checked = state.dnsFilteringEnabled" in block,
        )
    }

    private companion object {
        /** The row's own test tag, used as the end anchor for the block above. */
        const val ROW_TEST_TAG = "\"vpn_settings_birdoshield\""
    }

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }
}
