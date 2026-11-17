package app.birdo.vpn.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.birdo.vpn.R
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.ui.theme.BirdoRed
import app.birdo.vpn.ui.viewmodel.HoldCancelNote

/**
 * What the sign-out confirmation says about a deletion on hold (review of
 * #616, MR-2378): "signing out cancels it" only for the hold this session
 * filed (its own 202). The server leaves any other hold in place, and a user
 * who signed out trusting that sentence would be deleted anyway.
 */
@StringRes
internal fun signOutHoldNotice(filedHere: Boolean): Int =
    if (filedHere) R.string.sign_out_cancels_pending_deletion else R.string.sign_out_keeps_pending_deletion

/** What the confirmation's "Cancel deletion" did, in iOS #624's words. */
@StringRes
internal fun holdCancelNoteText(note: HoldCancelNote): Int = when (note) {
    HoldCancelNote.CANCELLED -> R.string.hold_cancelled_note
    HoldCancelNote.ALREADY_GONE -> R.string.hold_already_gone_note
}

/**
 * "Sign out?" confirmation, shared by the Connect tab and Profile (A2-006).
 *
 * Signing out drops the tunnel, and for an anonymous account whose number was
 * never saved it loses the account for good, so it is never one tap. Copy is
 * the canonical account vocabulary (iOS's "Log Out?" dialog, reworded).
 *
 * After confirming, the dialog stays up with a spinner: the server half of the
 * sign-out may take up to BirdoRepository.LOGOUT_SERVER_CALL_TIMEOUT_MS, and the
 * screen goes away by itself once the session is gone. Before this nothing on
 * screen changed for that long, which invited repeat taps.
 *
 * MR-2378, a deletion on hold (same rules as iOS and Desktop):
 * - filed by this session: says signing out cancels it; the sign-out cancels
 *   it first, and if that fails the dialog stays with the reason, Retry and
 *   "Sign out anyway" ([signOutBlockedReason], [onSignOutAnyway]);
 * - not filed by this session, or origin unknown (a restart, a 409): says
 *   signing out of this app may not cancel it, naming no origin;
 * - not checked (the read failed or timed out): says so, and that signing
 *   out may not cancel a deletion;
 * - while the hold is read (at most 3 s): "Checking…", nothing to confirm.
 * "Cancel deletion" is offered whenever there is a hold to cancel. The
 * dismiss button is "Stay signed in". The wording is iOS #624's, word for word.
 */
@Composable
fun SignOutConfirmDialog(
    isConnected: Boolean,
    isAnonymousAccount: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * MR-179: when the account's deletion is on hold, the local date and time
     * it takes effect, so the dialog can say what signing out does to it
     * before the user confirms.
     */
    pendingDeletionAt: String? = null,
    /**
     * Review of #616: the hold was filed by THIS session. Only then does
     * signing out cancel it (the server voids a hold when the session that
     * filed it ends); any other hold may survive.
     */
    pendingDeletionFiledHere: Boolean = false,
    /** MR-2378: no hold is known because the read failed or timed out. */
    pendingDeletionUnknown: Boolean = false,
    /**
     * MR-2378: there is a hold to cancel: one listed, or (a cold start that
     * could not read the list) the one this session filed. "Cancel deletion"
     * is offered then, whoever filed it (iOS #624).
     */
    pendingDeletionCancellable: Boolean = pendingDeletionAt != null,
    isCancellingPendingDeletion: Boolean = false,
    pendingDeletionError: String? = null,
    /** "Cancel deletion"; null shows no button. */
    onCancelPendingDeletion: (() -> Unit)? = null,
    /** MR-2378: what this dialog's "Cancel deletion" did, shown under it. */
    holdCancelNote: HoldCancelNote? = null,
    /** MR-2378: the hold is being read (at most 3 s): "Checking…", nothing to confirm yet. */
    isChecking: Boolean = false,
    /** MR-2378: this session's hold could not be cancelled, so the sign-out stopped; the reason. */
    signOutBlockedReason: String? = null,
    onSignOutAnyway: (() -> Unit)? = null,
    /**
     * MR-2378: changes when a sign-out stopped after its cancel-first (the
     * list still names a hold, or could not be read): the buttons come back.
     */
    signOutReconsidered: Int = 0,
    /**
     * MR-2378 (iOS #624 residual R2): the sign-out itself has begun. Until
     * then, a cancel-first in flight included, the dialog can be dismissed
     * ("Stay signed in"), and the VM then signs nobody out.
     */
    isSignOutProceeding: Boolean = false,
) {
    val palette = BirdoColors.current
    var signingOut by rememberSaveable { mutableStateOf(false) }
    // The sign-out stopped (its cancel-first failed, or the list read after it
    // did not say that nothing waits): the buttons come back.
    LaunchedEffect(signOutBlockedReason, signOutReconsidered) {
        if (signOutBlockedReason != null || signOutReconsidered > 0) signingOut = false
    }
    val blocked = signOutBlockedReason != null && !signingOut
    val checking = isChecking && !signingOut
    val dismissible = !(signingOut && isSignOutProceeding)
    BirdoAlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        containerColor = palette.surfaceElevated,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurfaceMuted,
        title = { Text(stringResource(R.string.sign_out_confirm_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    stringResource(
                        if (isConnected) R.string.sign_out_confirm_connected else R.string.sign_out_confirm_body,
                    ),
                )
                if (isAnonymousAccount) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.sign_out_anonymous_reminder), fontWeight = FontWeight.SemiBold)
                }
                when {
                    checking -> {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = palette.onSurfaceMuted,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sign_out_checking_deletion))
                        }
                    }
                    // A failed cancel-first REPLACES the hold's line (iOS #624):
                    // "signing out cancels the deletion" above "the deletion
                    // may then still go ahead" would contradict itself.
                    blocked -> {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (pendingDeletionAt != null) {
                                stringResource(R.string.sign_out_hold_cancel_failed_dated, signOutBlockedReason.orEmpty(), pendingDeletionAt)
                            } else {
                                stringResource(R.string.sign_out_hold_cancel_failed_undated, signOutBlockedReason.orEmpty())
                            },
                            color = BirdoRed,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    else -> {
                        val line = when {
                            pendingDeletionAt != null -> stringResource(signOutHoldNotice(pendingDeletionFiledHere), pendingDeletionAt)
                            pendingDeletionUnknown -> stringResource(R.string.sign_out_pending_deletion_unknown)
                            else -> null
                        }
                        if (line != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(line, color = BirdoRed, fontWeight = FontWeight.SemiBold)
                        }
                        if (pendingDeletionCancellable && onCancelPendingDeletion != null) {
                            TextButton(
                                onClick = onCancelPendingDeletion,
                                enabled = !signingOut && !isCancellingPendingDeletion,
                            ) {
                                Text(
                                    stringResource(
                                        if (isCancellingPendingDeletion) R.string.profile_pending_deletion_cancelling
                                        else R.string.profile_pending_deletion_cancel,
                                    ),
                                    color = palette.accent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        val note = pendingDeletionError ?: holdCancelNote?.let { stringResource(holdCancelNoteText(it)) }
                        if (note != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                note,
                                color = if (pendingDeletionError != null) BirdoRed else palette.onSurfaceMuted,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // Not while a "Cancel deletion" is out: one cancel at a time.
                enabled = !signingOut && !checking && !isCancellingPendingDeletion,
                onClick = {
                    signingOut = true
                    onConfirm()
                },
            ) {
                if (signingOut) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = palette.onSurfaceMuted,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.signing_out), color = palette.onSurfaceMuted)
                    }
                } else {
                    Text(
                        stringResource(
                            when {
                                checking -> R.string.sign_out_checking_label
                                blocked -> R.string.sign_out_try_again
                                else -> R.string.sign_out
                            },
                        ),
                        color = BirdoRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        dismissButton = {
            Row {
                // MR-2378 common wording: "Cancel" would read as cancelling the deletion.
                TextButton(onClick = onDismiss, enabled = dismissible) {
                    Text(stringResource(R.string.sign_out_stay_signed_in), color = palette.onSurfaceMuted)
                }
                if (blocked && onSignOutAnyway != null) {
                    TextButton(
                        onClick = {
                            signingOut = true
                            onSignOutAnyway()
                        },
                    ) {
                        Text(stringResource(R.string.sign_out_anyway), color = BirdoRed)
                    }
                }
            }
        },
    )
}
