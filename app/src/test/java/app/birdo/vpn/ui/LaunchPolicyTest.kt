package app.birdo.vpn.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPolicyTest {

    // ── Hide App Contents cover (A2-009, A2-046) ────────────────────────

    @Test
    fun `a cold start with the feature on is covered, and off is never covered`() {
        assertTrue(AppCoverPolicy.coveredAtCreate(enabled = true, savedCovered = null))
        assertFalse(AppCoverPolicy.coveredAtCreate(enabled = false, savedCovered = null))
        assertFalse(AppCoverPolicy.coveredAtCreate(enabled = false, savedCovered = true))
    }

    /** Folding, rotating or a system theme switch recreates the activity mid-use. */
    @Test
    fun `a recreation keeps the cover as it was`() {
        assertFalse(AppCoverPolicy.coveredAtCreate(enabled = true, savedCovered = false))
        assertTrue(AppCoverPolicy.coveredAtCreate(enabled = true, savedCovered = true))
    }

    @Test
    fun `leaving the app raises the cover, a prompt or a recreation does not`() {
        assertTrue(AppCoverPolicy.coverOnStop(enabled = true, authenticating = false, changingConfigurations = false))
        assertFalse(AppCoverPolicy.coverOnStop(enabled = true, authenticating = true, changingConfigurations = false))
        assertFalse(AppCoverPolicy.coverOnStop(enabled = true, authenticating = false, changingConfigurations = true))
        assertFalse(AppCoverPolicy.coverOnStop(enabled = false, authenticating = false, changingConfigurations = false))
    }

    // ── Notification permission in context (A2-026) ────────────────────

    @Test
    fun `notifications are explained once, when a tunnel starts, on Android 13 and later`() {
        assertTrue(shouldExplainNotifications(sdkInt = 33, granted = false, alreadyExplained = false, tunnelStarting = true))
        // Not at launch: nothing is connecting yet (this used to prompt over Consent).
        assertFalse(shouldExplainNotifications(sdkInt = 33, granted = false, alreadyExplained = false, tunnelStarting = false))
        assertFalse(shouldExplainNotifications(sdkInt = 35, granted = false, alreadyExplained = true, tunnelStarting = true))
        assertFalse(shouldExplainNotifications(sdkInt = 35, granted = true, alreadyExplained = false, tunnelStarting = true))
        assertFalse(shouldExplainNotifications(sdkInt = 32, granted = false, alreadyExplained = false, tunnelStarting = true))
    }
}
