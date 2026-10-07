package app.birdo.vpn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.birdo.vpn.R

/**
 * The Birdo brand mark as the app shows it — login, top bars, Settings > About.
 *
 * It draws its OWN raster drawables (`app_mark`, `app_mark_round`), never the
 * launcher icon. The launcher icon is an adaptive icon now (A2-025), and an
 * adaptive-icon XML is not something `painterResource` can draw; tying the
 * in-app mark to it is also how the old 832-command vector foreground (issue
 * #150) took down every screen that showed the brand. Two PNGs cannot fail to
 * inflate, and the launcher icon can change without touching any screen.
 */
@Composable
fun AppIconMark(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
    // When true, the SQUARE (squircle) mark shown whole (Fit, no crop) instead
    // of the circular one. Used by the login header and Settings > About.
    square: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(if (square) R.drawable.app_mark else R.drawable.app_mark_round),
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = if (square) ContentScale.Fit else ContentScale.Crop,
        )
    }
}
