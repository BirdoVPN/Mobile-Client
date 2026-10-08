package app.birdo.vpn.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.birdo.vpn.R
import app.birdo.vpn.ui.AppCoverPolicy

/*
 * The Hide App Contents cover, and the only door to a dialog or sheet window
 * (MR-937, REVIEW-AND-016).
 *
 * The cover used to be a Dialog: a window of its own over the activity. Every
 * Compose dialog and bottom sheet is a window too, and Android stacks an
 * app's windows in the order they are added, so a dialog that opened AFTER
 * the cover (an async result: the anonymous account number, "Account
 * deleted", a voucher result) was drawn above it. No window an app can add
 * without SYSTEM_ALERT_WINDOW stays above the windows it adds later, and a
 * cover drawn inside the activity's own window is below all of them.
 *
 * So the cover is not a window. It is drawn last in the activity's content,
 * over the nav graph ([AppCoverHost]), and while it is up no dialog or sheet
 * window exists: each one goes through [BirdoAlertDialog] or
 * [BirdoModalBottomSheet] below, which compose nothing while [LocalAppObscured]
 * is true. A dialog's state (whether it is open, what was typed into it)
 * belongs to its caller, which stays composed, so after unlock the dialog is
 * back as it was. CoverWindowGuardTest fails the build if a window-creating
 * composable is called anywhere but this file.
 *
 * FLAG_SECURE is MainActivity's and unchanged: it covers every window of the
 * activity, this one included.
 */

/** Test tag of the cover's surface. */
const val APP_COVER_TAG = "app_cover"

/**
 * The app's content with the Hide App Contents cover over it while [covered].
 *
 * Underneath, the content stays composed (A2-009: swapping it out for the
 * cover lost the open screen, its dialogs and typed text on every unlock),
 * hidden from accessibility services, and told it cannot be seen
 * ([LocalAppObscured]): animations stop, and dialogs and sheets are not shown.
 *
 * Hardware keys are this window's too, now that the cover has no window of its
 * own to take them: without a filter, Tab and the arrows moved focus onto a
 * control nobody can see and Enter pressed it. While covered every key is
 * dropped here, before any of the app sees it, except the system keys and
 * Enter to unlock ([AppCoverPolicy.keyWhileCovered]).
 *
 * @param onBackWhileCovered Back while covered. It never reaches the content
 *   underneath, which would pop a screen nobody can see.
 */
@Composable
fun AppCoverHost(
    covered: Boolean,
    onUnlock: () -> Unit,
    onBackWhileCovered: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The parent of the content and the cover alike, so it sees every
            // key whichever of them holds focus.
            .onPreviewKeyEvent { covered && consumeKeyWhileCovered(it, onUnlock) },
    ) {
        CompositionLocalProvider(LocalAppObscured provides covered) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (covered) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                content()
            }
        }
        if (covered) {
            BackHandler(onBack = onBackWhileCovered)
            AppCoverScreen(onUnlock = onUnlock)
        }
    }
}

/** True when a key pressed while covered is consumed: everything but Back, volume and media. */
private fun consumeKeyWhileCovered(event: KeyEvent, onUnlock: () -> Unit): Boolean {
    val key = event.nativeKeyEvent
    val confirm = key.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        key.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
        key.keyCode == AndroidKeyEvent.KEYCODE_SPACE ||
        key.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER
    return when (
        AppCoverPolicy.keyWhileCovered(
            covered = true,
            systemKey = key.isSystem,
            confirmReleased = confirm && key.action == AndroidKeyEvent.ACTION_UP,
        )
    ) {
        AppCoverPolicy.CoverKey.PASS -> false
        AppCoverPolicy.CoverKey.UNLOCK -> {
            onUnlock()
            true
        }
        AppCoverPolicy.CoverKey.DROP -> true
    }
}

/**
 * The cover: full-screen and opaque, so nothing of the app shows or takes
 * input behind the system prompt (a Surface consumes the touches that land on
 * it), with an explicit "Unlock" for when the user dismissed the prompt. It
 * hides the screen and nothing else, and says so (P1-011): the VPN keeps
 * running behind it.
 *
 * Hardware keys are filtered by [AppCoverHost]. The soft keyboard sends no
 * key events, so it is closed here instead.
 */
@Composable
private fun AppCoverScreen(onUnlock: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val coverFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Focus moves onto the cover, so a text field underneath gives it up
        // and the keyboard goes with it: nothing can be typed into a screen
        // nobody can see. (The Dialog took the window's focus, which did the
        // same.) Merely clearing focus is not enough: in keyboard mode Android
        // hands it straight back to the first focusable, the hidden field.
        coverFocus.requestFocus()
        keyboard?.hide()
    }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag(APP_COVER_TAG)
            // A focus target without semantics: TalkBack still reads only the
            // cover's text and its Unlock button.
            .focusRequester(coverFocus)
            .focusTarget(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.app_cover_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_cover_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onUnlock) {
                Text(stringResource(R.string.app_cover_unlock))
            }
        }
    }
}

/**
 * Material 3's AlertDialog, shown only while nothing covers the app (see the
 * top of this file). Same parameters and defaults.
 */
@Composable
fun BirdoAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    if (LocalAppObscured.current) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/**
 * Material 3's ModalBottomSheet, shown only while nothing covers the app (see
 * the top of this file). The caller's [sheetState] outlives a covered spell,
 * so the sheet comes back as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirdoModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalAppObscured.current) return
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = dragHandle,
        content = content,
    )
}
