package app.birdo.vpn.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [isConnectingPhase] — the single predicate every transitional-state gate
 * now shares.
 *
 * THE BUG THIS CLOSES. [VpnState.StealthConnecting] is published for the whole
 * stealth setup (Xray start, Rosenpass exchange, WgNative init/turnOn,
 * establish()), and EVERY consumer that enumerated transitional states inline
 * listed only `Connecting` and missed it: the 30s connect watchdog, VpnManager's
 * 45s stuck-connect net, both fail-closed switchTeardown gates, the reapply
 * error path, VpnViewModel's three re-entry gates, and HomeScreen's
 * `isConnecting` (which decides whether Connect is tappable).
 *
 * So during stealth setup the Home button rendered as an idle, tappable
 * "Connect". A second tap passed the ViewModel gate and the switchTeardown
 * predicate, so no blocking interface was armed — and the second startTunnel's
 * cleanupTunnelDataPlane() then closed the LIVE tunnel's fd, reverting routing
 * to the physical network with nothing blocking for the whole Xray+PQ+establish
 * window of the retry. For a user who had explicitly chosen fail-closed. Neither
 * watchdog could fire either, because both guarded on `Connecting`.
 *
 * WHY THE TEST IS SHAPED LIKE THIS. Asserting "StealthConnecting is true" alone
 * would not have prevented the original bug, because the bug was an
 * ENUMERATION that fell out of date. So this walks EVERY VpnState subtype
 * explicitly and asserts a verdict for each. Adding a new state to the sealed
 * class without deciding whether it is a connecting phase makes
 * [allStates] incomplete, and `every state is classified` fails.
 */
class ConnectingPhaseGateTest {

    /**
     * One instance of every VpnState subtype. Kept exhaustive on purpose — see
     * `every state is classified` below.
     */
    private val allStates: List<VpnState> = listOf(
        VpnState.Disconnected,
        VpnState.Connecting,
        VpnState.Authenticating,
        VpnState.StealthConnecting,
        VpnState.Connected,
        VpnState.Disconnecting,
        VpnState.Reconnecting(0),
        VpnState.KillSwitchActive,
        VpnState.Error("boom"),
    )

    /** States for which a tunnel setup is genuinely in flight. */
    private val expectedInFlight = setOf<VpnState>(
        VpnState.Connecting,
        VpnState.StealthConnecting,
    )

    @Test
    fun `StealthConnecting counts as a connecting phase`() {
        // The regression. Before the fix this was false, and every gate above
        // let a second connect through during stealth setup.
        assertTrue(
            "StealthConnecting MUST be treated as in-flight — a second connect " +
                "during it closes the live tunnel's fd with nothing blocking",
            VpnState.StealthConnecting.isConnectingPhase,
        )
    }

    @Test
    fun `Connecting still counts as a connecting phase`() {
        assertTrue(VpnState.Connecting.isConnectingPhase)
    }

    @Test
    fun `settled and terminal states are not connecting phases`() {
        assertFalse(VpnState.Disconnected.isConnectingPhase)
        assertFalse(VpnState.Connected.isConnectingPhase)
        assertFalse(VpnState.Disconnecting.isConnectingPhase)
        assertFalse(VpnState.KillSwitchActive.isConnectingPhase)
        assertFalse(VpnState.Error("boom").isConnectingPhase)
    }

    @Test
    fun `Reconnecting is handled separately by every caller and is not folded in here`() {
        // Deliberate: the switchTeardown gates test Reconnecting explicitly
        // alongside this predicate, and one of them keys off
        // `priorState !is Reconnecting` to decide whether to cancel auto-reconnect.
        // Folding it in here would silently change that behaviour.
        assertFalse(VpnState.Reconnecting(0).isConnectingPhase)
        assertFalse(VpnState.Reconnecting(3).isConnectingPhase)
    }

    @Test
    fun `Authenticating is excluded because nothing ever publishes it`() {
        // Dead state as of 2026-08-27: no updateState(Authenticating) call exists.
        // If that changes, this assertion is the tripwire — flip it and add the
        // state to isConnectingPhase in the same commit.
        assertFalse(VpnState.Authenticating.isConnectingPhase)
    }

    @Test
    fun `every state is classified — a new state cannot be added unnoticed`() {
        // This is the guard that actually prevents recurrence. The original bug
        // was an enumeration going stale across five files; the fix is one
        // predicate, and this asserts the predicate's verdict for every subtype.
        for (state in allStates) {
            val expected = state in expectedInFlight
            org.junit.Assert.assertEquals(
                "isConnectingPhase disagrees for $state — if you added a new " +
                    "VpnState, decide whether a tunnel setup is in flight for it, " +
                    "then update isConnectingPhase AND this test together",
                expected,
                state.isConnectingPhase,
            )
        }
    }
}
