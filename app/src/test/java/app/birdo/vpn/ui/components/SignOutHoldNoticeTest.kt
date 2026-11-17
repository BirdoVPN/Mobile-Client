package app.birdo.vpn.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.birdo.vpn.R
import app.birdo.vpn.testing.StringsXml
import app.birdo.vpn.ui.screen.pendingDeletionSignOutLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review of #616 (High): the server voids a held deletion only when the
 * session that FILED it ends. A hold asked for on the website (no session)
 * or by another device survives a sign-out here, yet the sign-out
 * confirmation said "Signing out cancels the deletion" for any hold: a user
 * who trusted it and signed out was deleted 24 hours later. The sentence now
 * follows who filed the hold, and the confirmation offers Cancel itself.
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignOutHoldNoticeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val at = "16 Nov 2026, 12:00"

    private fun text(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private var cancels = 0
    private var confirms = 0
    private var anyway = 0

    private fun show(
        filedHere: Boolean,
        cancelling: Boolean = false,
        error: String? = null,
        hold: String? = at,
        unknown: Boolean = false,
        blocked: String? = null,
        cancellable: Boolean = hold != null,
    ) {
        rule.setContent {
            MaterialTheme {
                SignOutConfirmDialog(
                    isConnected = false,
                    isAnonymousAccount = false,
                    onConfirm = { confirms++ },
                    onDismiss = {},
                    pendingDeletionAt = hold,
                    pendingDeletionFiledHere = filedHere,
                    pendingDeletionUnknown = unknown,
                    isCancellingPendingDeletion = cancelling,
                    pendingDeletionError = error,
                    onCancelPendingDeletion = { cancels++ },
                    signOutBlockedReason = blocked,
                    onSignOutAnyway = { anyway++ },
                    pendingDeletionCancellable = cancellable,
                )
            }
        }
    }

    @Test
    fun `only a hold this session filed is said to be cancelled by signing out`() {
        assertEquals(R.string.sign_out_cancels_pending_deletion, signOutHoldNotice(filedHere = true))
        assertEquals(R.string.sign_out_keeps_pending_deletion, signOutHoldNotice(filedHere = false))
        assertEquals(R.string.delete_hold_signout_cancels, pendingDeletionSignOutLine(filedHere = true))
        assertEquals(R.string.delete_hold_signout_keeps, pendingDeletionSignOutLine(filedHere = false))
    }

    /** MR-2378: the common wording, word for word the same on Android, iOS (#624) and Desktop (#439). */
    @Test
    fun `the common wording`() {
        assertEquals(
            "Signing out cancels the deletion of your account that is scheduled for %1\$s.",
            StringsXml.text("sign_out_cancels_pending_deletion"),
        )
        assertEquals(
            "Your account will be deleted on %1\$s. Signing out of this app may not cancel this deletion. " +
                "To keep your account, cancel the deletion.",
            StringsXml.text("sign_out_keeps_pending_deletion"),
        )
        assertEquals(
            "We couldn't check whether a deletion is waiting. Signing out may not cancel it. " +
                "To keep your account, stay signed in and cancel it once you're online.",
            StringsXml.text("sign_out_pending_deletion_unknown"),
        )
        assertEquals("Stay signed in", StringsXml.text("sign_out_stay_signed_in"))
    }

    /**
     * A hold this session did not file may still be this device's (after a
     * restart, or a 409 for its own hold): the sentence names no origin and
     * promises nothing.
     */
    @Test
    fun `the sentence for a hold not filed here names no origin`() {
        for (key in listOf("sign_out_keeps_pending_deletion", "delete_hold_signout_keeps", "sign_out_pending_deletion_unknown")) {
            val sentence = StringsXml.text(key)
            assertTrue(sentence, sentence.contains("may not cancel"))
            assertFalse(sentence, sentence.contains("website"))
            assertFalse(sentence, sentence.contains("another device"))
            assertFalse(sentence, sentence.contains("does not cancel"))
        }
    }

    @Test
    fun `the way out of the confirmation is Stay signed in`() {
        show(filedHere = false)

        rule.onNodeWithText(text(R.string.sign_out_stay_signed_in)).assertIsDisplayed()
    }

    @Test
    fun `a hold filed here says signing out cancels it, and the sign-out itself does the cancel`() {
        show(filedHere = true)

        rule.onNodeWithText(text(R.string.sign_out_cancels_pending_deletion, at)).assertIsDisplayed()
        rule.onAllNodes(hasText(text(R.string.sign_out_keeps_pending_deletion, at))).assertCountEquals(0)
        // iOS #624: "Cancel deletion" is offered whenever there is a hold to cancel.
        rule.onNodeWithText(text(R.string.profile_pending_deletion_cancel)).assertIsDisplayed()
    }

    @Test
    fun `an unreadable hold gets a caution that promises nothing`() {
        show(filedHere = false, hold = null, unknown = true)

        rule.onNodeWithText(text(R.string.sign_out_pending_deletion_unknown)).assertIsDisplayed()
        val caution = StringsXml.text("sign_out_pending_deletion_unknown")
        assertTrue(caution, caution.contains("may not cancel"))
        assertFalse(caution, caution.contains("does not cancel") || caution.contains("cancels the deletion"))
    }

    @Test
    fun `no hold and a good read says nothing about a deletion`() {
        show(filedHere = false, hold = null, unknown = false)

        rule.onAllNodes(hasText(text(R.string.sign_out_pending_deletion_unknown))).assertCountEquals(0)
        rule.onAllNodes(hasText(text(R.string.profile_pending_deletion_cancel))).assertCountEquals(0)
    }

    @Test
    fun `a failed cancel-first replaces the hold's line, with Try again and Sign out anyway`() {
        val reason = text(R.string.hold_cancel_unreachable)
        show(filedHere = true, blocked = reason)

        rule.onNodeWithText(text(R.string.sign_out_hold_cancel_failed_dated, reason, at)).assertIsDisplayed()
        // It replaces "signing out cancels the deletion", which it would contradict.
        rule.onAllNodes(hasText(text(R.string.sign_out_cancels_pending_deletion, at))).assertCountEquals(0)
        rule.onAllNodes(hasText(text(R.string.profile_pending_deletion_cancel))).assertCountEquals(0)

        rule.onNodeWithText(text(R.string.sign_out_try_again)).performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun `with no date known the failed cancel-first names the deletion asked for here`() {
        val reason = text(R.string.hold_cancel_unreachable)
        show(filedHere = false, hold = null, unknown = true, blocked = reason)

        rule.onNodeWithText(text(R.string.sign_out_hold_cancel_failed_undated, reason)).assertIsDisplayed()
    }

    @Test
    fun `while the hold is read the confirmation says Checking and confirms nothing`() {
        rule.setContent {
            MaterialTheme {
                SignOutConfirmDialog(
                    isConnected = false,
                    isAnonymousAccount = false,
                    onConfirm = { confirms++ },
                    onDismiss = {},
                    isChecking = true,
                )
            }
        }

        rule.onNodeWithText(text(R.string.sign_out_checking_deletion)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.sign_out_checking_label)).performClick()
        assertEquals(0, confirms)
    }

    @Test
    fun `a cold start offline with this session's hold offers Cancel deletion under the unchecked line`() {
        show(filedHere = false, hold = null, unknown = true, cancellable = true)

        rule.onNodeWithText(text(R.string.sign_out_pending_deletion_unknown)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.profile_pending_deletion_cancel)).performClick()
        assertEquals(1, cancels)
    }

    /** Word for word iOS #624 at 1a7105d4 (AccountDeletion.swift, SignOutConfirmation.swift). */
    @Test
    fun `the remaining sign-out wording is iOS's`() {
        assertEquals(
            "%1\$s You are still signed in, and your account is still scheduled to be deleted on %2\$s. " +
                "Try again, or sign out anyway: the deletion may then still go ahead.",
            StringsXml.text("sign_out_hold_cancel_failed_dated"),
        )
        assertEquals(
            "%1\$s You are still signed in, and the deletion you asked for on this device may still be scheduled. " +
                "Try again, or sign out anyway: the deletion may then still go ahead.",
            StringsXml.text("sign_out_hold_cancel_failed_undated"),
        )
        assertEquals("Try again", StringsXml.text("sign_out_try_again"))
        assertEquals("Sign out anyway", StringsXml.text("sign_out_anyway"))
        assertEquals("Checking…", StringsXml.text("sign_out_checking_label"))
        assertEquals("Checking whether a deletion is waiting for this account…", StringsXml.text("sign_out_checking_deletion"))
        assertEquals("Unable to reach server. Check your connection.", StringsXml.text("hold_cancel_unreachable"))
        assertEquals("The deletion could not be cancelled. Please try again.", StringsXml.text("hold_cancel_failed"))
        assertEquals("The deletion is cancelled.", StringsXml.text("hold_cancelled_note"))
        assertEquals("That deletion was no longer waiting.", StringsXml.text("hold_already_gone_note"))
        assertEquals("Cancel deletion", StringsXml.text("profile_pending_deletion_cancel"))
        assertEquals("Cancelling…", StringsXml.text("profile_pending_deletion_cancelling"))
        assertEquals(
            "Stay signed in on this device until then; signing out cancels the deletion.",
            StringsXml.text("delete_hold_signout_cancels"),
        )
    }

    @Test
    fun `Sign out anyway signs out without the cancel`() {
        show(filedHere = true, blocked = text(R.string.hold_cancel_unreachable))

        rule.onNodeWithText(text(R.string.sign_out_anyway)).performClick()

        assertEquals(1, anyway)
        assertEquals(0, confirms)
    }

    @Test
    fun `a hold not filed here says signing out of this app may not cancel it`() {
        show(filedHere = false)

        rule.onNodeWithText(text(R.string.sign_out_keeps_pending_deletion, at)).assertIsDisplayed()
        rule.onAllNodes(hasText(text(R.string.sign_out_cancels_pending_deletion, at))).assertCountEquals(0)
    }

    @Test
    fun `the confirmation offers Cancel deletion, wired to the cancel`() {
        show(filedHere = false)

        rule.onNodeWithText(text(R.string.profile_pending_deletion_cancel)).performClick()

        assertEquals(1, cancels)
    }

    @Test
    fun `a cancel in flight cannot be sent twice, and a failed one says why`() {
        show(filedHere = false, cancelling = true, error = "No connection.")

        rule.onNodeWithText(text(R.string.profile_pending_deletion_cancelling)).performClick()
        rule.onNodeWithText("No connection.").assertIsDisplayed()

        assertEquals(0, cancels)
    }
}
