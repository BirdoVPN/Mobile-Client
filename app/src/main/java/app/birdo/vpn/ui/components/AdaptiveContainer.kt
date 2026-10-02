package app.birdo.vpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Adaptive container that constrains content width on tablets and foldables.
 * On phones (<600dp wide): fills the entire width.
 * On tablets (600dp+): centers content with max 480dp width.
 * On large tablets (840dp+): max 560dp width.
 *
 * [fullBleed] lets the content span the window and narrow itself with
 * [adaptiveMaxContentWidth]: the Connect tab's globe is a full-window
 * background, and clamping it into the column left a narrow phone layout
 * floating in a dark field on tablets (A2-048).
 */
@Composable
fun AdaptiveContainer(
    modifier: Modifier = Modifier,
    fullBleed: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val maxContentWidth = if (fullBleed) Dp.Unspecified else adaptiveMaxContentWidth()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxHeight(),
            content = content,
        )
    }
}

/** The width a column of controls may take in the current window (unbounded on a phone). */
@Composable
fun adaptiveMaxContentWidth(): Dp {
    // The WINDOW width, not Configuration.screenWidthDp: in multi-window or a
    // freeform window the content is laid out in the window, so a phone-sized
    // window on a tablet gets the phone layout instead of a 480dp column
    // squeezed into 400dp.
    val windowInfo = LocalWindowInfo.current
    val screenWidthDp = with(LocalDensity.current) { windowInfo.containerSize.width.toDp().value.toInt() }
    return when {
        screenWidthDp >= 840 -> 560.dp  // Large tablet / foldable opened
        screenWidthDp >= 600 -> 480.dp  // Small tablet / foldable
        else -> Dp.Unspecified          // Phone — full width
    }
}
