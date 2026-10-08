package app.birdo.vpn.ui.components

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
 */
@Composable
fun SignOutConfirmDialog(
    isConnected: Boolean,
    isAnonymousAccount: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BirdoColors.current
    var signingOut by rememberSaveable { mutableStateOf(false) }
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
                    Text(stringResource(R.string.sign_out), color = BirdoRed, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !signingOut) {
                Text(stringResource(R.string.cancel), color = palette.onSurfaceMuted)
            }
        },
    )
}
