package app.birdo.vpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * Adaptive container that constrains content width on tablets and foldables.
 * On phones (<600dp wide): fills the entire width.
 * On tablets (600dp+): centers content with max 480dp width.
 * On large tablets (840dp+): max 560dp width.
 */
@Composable
fun AdaptiveContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // The WINDOW width, not Configuration.screenWidthDp: in multi-window or a
    // freeform window the content is laid out in the window, so a phone-sized
    // window on a tablet gets the phone layout instead of a 480dp column
    // squeezed into 400dp.
    val windowInfo = LocalWindowInfo.current
    val screenWidthDp = with(LocalDensity.current) { windowInfo.containerSize.width.toDp().value.toInt() }

    val maxContentWidth = when {
        screenWidthDp >= 840 -> 560.dp  // Large tablet / foldable opened
        screenWidthDp >= 600 -> 480.dp  // Small tablet / foldable
        else -> Int.MAX_VALUE.dp        // Phone — full width
    }

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
