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

/**
 * What the sign-out confirmation says about a deletion on hold (review of
 * #616, MR-2378): "signing out cancels it" only for the hold this session
 * filed (its own 202). The server leaves any other hold in place, and a user
 * who signed out trusting that sentence would be deleted anyway.
 */
@StringRes
internal fun signOutHoldNotice(filedHere: Boolean): Int =
    if (filedHere) R.string.sign_out_cancels_pending_deletion else R.string.sign_out_keeps_pending_deletion

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
 *   signing out of this app may not cancel it, naming no origin, and offers
 *   "Cancel deletion" ([onCancelPendingDeletion]);
 * - not checked (the read failed or timed out): says so, and that signing
 *   out may not cancel a deletion.
 * The dismiss button is "Stay signed in" (common wording).
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
     * filed it ends); one filed on the website or another device survives.
     */
    pendingDeletionFiledHere: Boolean = false,
    /** MR-2378: no hold is known because the read failed or timed out. */
    pendingDeletionUnknown: Boolean = false,
    isCancellingPendingDeletion: Boolean = false,
    pendingDeletionError: String? = null,
    /** "Cancel deletion" for a hold this session did not file; null shows no button. */
    onCancelPendingDeletion: (() -> Unit)? = null,
    /** MR-2378: this session's hold could not be cancelled, so the sign-out stopped; the reason. */
    signOutBlockedReason: String? = null,
    onSignOutAnyway: (() -> Unit)? = null,
) {
    val palette = BirdoColors.current
    var signingOut by rememberSaveable { mutableStateOf(false) }
    // The sign-out stopped (its cancel-first failed): the buttons come back.
    LaunchedEffect(signOutBlockedReason) {
        if (signOutBlockedReason != null) signingOut = false
    }
    val blocked = signOutBlockedReason != null && !signingOut
    BirdoAlertDialog(
        onDismissRequest = { if (!signingOut) onDismiss() },
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
                if (pendingDeletionAt != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(signOutHoldNotice(pendingDeletionFiledHere), pendingDeletionAt),
                        color = BirdoRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (pendingDeletionError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            pendingDeletionError,
                            color = BirdoRed,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (!pendingDeletionFiledHere && onCancelPendingDeletion != null) {
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
                } else if (pendingDeletionUnknown) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.sign_out_pending_deletion_unknown), fontWeight = FontWeight.SemiBold)
                }
                if (blocked) {
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(
                            stringResource(R.string.sign_out_hold_cancel_failed),
                            color = BirdoRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(signOutBlockedReason.orEmpty(), color = BirdoRed)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !signingOut,
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
                        stringResource(if (blocked) R.string.retry else R.string.sign_out),
                        color = BirdoRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss, enabled = !signingOut) {
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
