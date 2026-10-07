package app.birdo.vpn.ui.components

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * True while something opaque covers the WHOLE app: the Hide App Contents
 * cover. The navigation graph now stays composed underneath it (A2-009), so
 * anything that animates on its own has to be told it cannot be seen.
 */
val LocalAppObscured = compositionLocalOf { false }

/**
 * The rule for decorative, hand-rolled animation (the globe's clock, the
 * PixelCanvas twinkle): run only while the app is visible, nothing covers it,
 * and the user has not turned animations off.
 *
 * Compose's own tweens honour "Remove animations" through MotionDurationScale;
 * a loop driven by `delay` or `withFrameMillis` does not, and neither stops
 * when the Activity is merely STOPPED, because the composition is still alive.
 * Pure so the rule is pinned by a unit test rather than by a device run.
 */
internal fun decorativeMotionAllowed(started: Boolean, animatorsEnabled: Boolean, obscured: Boolean): Boolean =
    started && animatorsEnabled && !obscured

/**
 * [decorativeMotionAllowed] for the calling composable. The animator setting
 * is re-read on every ON_START because the user can change it while the app
 * is in the background.
 */
@Composable
fun rememberDecorativeMotionAllowed(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var animatorsEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    started = true
                    animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
                }
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return decorativeMotionAllowed(started, animatorsEnabled, LocalAppObscured.current)
}
