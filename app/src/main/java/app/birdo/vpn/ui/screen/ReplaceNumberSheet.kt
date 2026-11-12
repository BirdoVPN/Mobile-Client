package app.birdo.vpn.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.birdo.vpn.R
import app.birdo.vpn.data.repository.RotationFailure
import app.birdo.vpn.ui.components.BirdoAlertDialog
import app.birdo.vpn.ui.theme.BirdoGreen
import app.birdo.vpn.ui.theme.BirdoRed
import app.birdo.vpn.ui.theme.BirdoSurface
import app.birdo.vpn.ui.theme.BirdoWhite60
import app.birdo.vpn.ui.theme.BirdoWhite80
import app.birdo.vpn.ui.viewmodel.ReplaceNumberViewModel
import app.birdo.vpn.ui.viewmodel.ReplaceStep
import app.birdo.vpn.utils.copySensitiveToClipboard
import app.birdo.vpn.utils.formatAnonymousId

/** The words for each refusal. One string per [RotationFailure]; the flow decides where the user lands. */
@StringRes
internal fun replaceNumberFailureText(failure: RotationFailure): Int = when (failure) {
    RotationFailure.DISABLED -> R.string.replace_number_err_disabled
    RotationFailure.PROOF_INVALID -> R.string.replace_number_err_proof
    RotationFailure.SESSION_EXPIRED -> R.string.replace_number_err_session
    RotationFailure.NOT_PENDING -> R.string.replace_number_err_not_pending
    RotationFailure.ACCOUNT_CHANGED -> R.string.replace_number_err_account_changed
    RotationFailure.MISMATCH -> R.string.replace_number_err_mismatch
    RotationFailure.TOO_MANY -> R.string.replace_number_err_too_many
    RotationFailure.REJECTED -> R.string.replace_number_err_rejected
    RotationFailure.NETWORK -> R.string.replace_number_err_network
}

/**
 * MR-1707: replace the anonymous account number in three steps (proof, save the
 * new number, re-type it), behind `BuildConfig.ANON_NUMBER_ROTATION`. The
 * caller shows it only when [app.birdo.vpn.utils.replaceNumberEntryVisible].
 *
 * Nothing typed or received here is saved to instance state (`remember`, never
 * `rememberSaveable`), logged or sent to analytics; [onDismiss] and leaving the
 * composition clear the view model's copy of the new number.
 *
 * @param hasPassword whether the account has a password to ask for.
 * @param onReplaced the number was replaced and the fresh tokens are stored: reload the profile.
 */
@Composable
fun ReplaceNumberSheet(
    hasPassword: Boolean,
    onReplaced: () -> Unit,
    onDismiss: () -> Unit,
    /** Sign out locally and open sign-in: offered when the replacement may already be active. */
    onSignInAgain: () -> Unit = {},
    viewModel: ReplaceNumberViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.account_number_clip_label)
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var retyped by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { viewModel.clear() } }
    LaunchedEffect(state.step) {
        if (state.step == ReplaceStep.DONE) {
            current = ""; password = ""; code = ""; retyped = ""
            onReplaced()
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BirdoGreen,
        cursorColor = BirdoWhite80,
        focusedLabelColor = BirdoGreen,
    )

    BirdoAlertDialog(
        onDismissRequest = { if (!state.busy) { viewModel.clear(); onDismiss() } },
        containerColor = BirdoSurface,
        titleContentColor = BirdoWhite80,
        title = { Text(stringResource(R.string.replace_number_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (state.step) {
                    ReplaceStep.PROOF -> {
                        Text(stringResource(R.string.replace_number_proof_body), style = MaterialTheme.typography.bodySmall, color = BirdoWhite60)
                        OutlinedTextField(
                            value = current,
                            onValueChange = { current = it.filter { c -> c.isDigit() || c == ' ' }.take(40) },
                            label = { Text(stringResource(R.string.replace_number_current_label)) },
                            singleLine = true,
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors,
                        )
                        if (hasPassword) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.replace_number_password_label)) },
                                singleLine = true,
                                enabled = !state.busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = fieldColors,
                            )
                        }
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { c -> c.isLetterOrDigit() }.take(32) },
                            label = { Text(stringResource(R.string.replace_number_2fa_label)) },
                            singleLine = true,
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors,
                        )
                    }
                    ReplaceStep.SAVE -> {
                        Text(stringResource(R.string.replace_number_save_body), style = MaterialTheme.typography.bodySmall, color = BirdoWhite60)
                        // Shown ONCE: it is read here, while this step draws it, and nowhere else.
                        Text(
                            text = formatAnonymousId(viewModel.newNumberDigits()),
                            color = BirdoWhite80,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = {
                            copySensitiveToClipboard(context, clipLabel, viewModel.newNumberDigits())
                        }) { Text(stringResource(R.string.replace_number_copy)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.savedAcknowledged,
                                onCheckedChange = { viewModel.setSavedAcknowledged(it) },
                                colors = CheckboxDefaults.colors(checkedColor = BirdoGreen, checkmarkColor = Color.Black),
                            )
                            Text(stringResource(R.string.replace_number_saved_check), style = MaterialTheme.typography.bodySmall, color = BirdoWhite80)
                        }
                    }
                    ReplaceStep.CONFIRM -> {
                        if (state.maybeCommitted) {
                            // The new number is still in memory: keep it in view, it is what they sign in with.
                            Text(
                                text = formatAnonymousId(viewModel.newNumberDigits()),
                                color = BirdoWhite80,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(stringResource(R.string.replace_number_confirm_body), style = MaterialTheme.typography.bodySmall, color = BirdoWhite60)
                        }
                        OutlinedTextField(
                            value = retyped,
                            onValueChange = { retyped = it.filter { c -> c.isDigit() || c == ' ' }.take(40) },
                            label = { Text(stringResource(R.string.replace_number_retype_label)) },
                            singleLine = true,
                            enabled = !state.busy && !state.maybeCommitted,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors,
                        )
                    }
                    ReplaceStep.DONE -> Text(
                        stringResource(R.string.replace_number_done),
                        style = MaterialTheme.typography.bodySmall,
                        color = BirdoGreen,
                    )
                }
                state.failure?.let { failure ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(
                            if (state.maybeCommitted) R.string.replace_number_maybe_committed else replaceNumberFailureText(failure),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = BirdoRed,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            val primary = Modifier
            when (state.step) {
                ReplaceStep.PROOF -> Button(
                    onClick = {
                        viewModel.prepare(current, password.takeIf { hasPassword }, code.takeIf { it.isNotBlank() })
                        current = ""; password = ""; code = ""
                    },
                    enabled = !state.busy && current.count { it.isDigit() } >= 24 && (!hasPassword || password.isNotEmpty()),
                    colors = ButtonDefaults.buttonColors(containerColor = BirdoGreen, contentColor = Color.Black),
                    modifier = primary,
                ) { SheetButtonLabel(state.busy, R.string.replace_number_get_new) }
                ReplaceStep.SAVE -> Button(
                    onClick = { viewModel.continueToConfirm() },
                    enabled = state.savedAcknowledged,
                    colors = ButtonDefaults.buttonColors(containerColor = BirdoGreen, contentColor = Color.Black),
                ) { Text(stringResource(R.string.replace_number_continue), fontWeight = FontWeight.SemiBold) }
                ReplaceStep.CONFIRM -> Button(
                    onClick = { viewModel.confirm(retyped) },
                    enabled = !state.busy && !state.maybeCommitted && retyped.count { it.isDigit() } >= 24,
                    colors = ButtonDefaults.buttonColors(containerColor = BirdoGreen, contentColor = Color.Black),
                ) { SheetButtonLabel(state.busy, R.string.replace_number_confirm) }
                ReplaceStep.DONE -> Button(
                    onClick = { viewModel.clear(); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = BirdoGreen, contentColor = Color.Black),
                ) { Text(stringResource(R.string.replace_number_close), fontWeight = FontWeight.SemiBold) }
            }
        },
        dismissButton = {
            when (state.step) {
                ReplaceStep.DONE -> Unit
                ReplaceStep.CONFIRM -> if (state.maybeCommitted) {
                    TextButton(onClick = { retyped = ""; viewModel.clear(); onSignInAgain() }) {
                        Text(stringResource(R.string.replace_number_sign_in_again), color = BirdoGreen)
                    }
                } else {
                    TextButton(onClick = { retyped = ""; viewModel.showNumberAgain() }, enabled = !state.busy) {
                        Text(stringResource(R.string.replace_number_show_again), color = BirdoWhite60)
                    }
                }
                else -> TextButton(onClick = { viewModel.clear(); onDismiss() }, enabled = !state.busy) {
                    Text(stringResource(R.string.replace_number_cancel), color = BirdoWhite60)
                }
            }
        },
    )
}

@Composable
private fun SheetButtonLabel(busy: Boolean, @StringRes label: Int) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
    } else {
        Text(stringResource(label), fontWeight = FontWeight.SemiBold)
    }
}
