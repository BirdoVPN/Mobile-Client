package app.birdo.vpn.ui.components

import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.birdo.vpn.R

/**
 * Renders the actual Birdo launcher icon. Used wherever the app needs to
 * represent itself — login, top bars, profile avatar. Replaces the previous
 * bright purple→pink gradient shield with the real brand mark.
 *
 * NOTE: We deliberately load `ic_launcher_round` through `AppCompatResources`
 * + `Drawable.toBitmap()` rather than `painterResource()`, because on API 26+
 * the launcher resource resolves to `mipmap-anydpi-v26/ic_launcher_round.xml`
 * (an adaptive-icon XML) which Compose's `painterResource()` cannot inflate
 * — it throws `IllegalArgumentException: Only VectorDrawables and rasterized
 * asset types are supported`.
 *
 * CRASH FIX: loading the launcher icon must NEVER throw. It previously crashed
 * the whole app with `Resources$NotFoundException` on real devices, because the
 * old adaptive-icon foreground was an 832-command potrace VectorDrawable that
 * Android refused to inflate as an icon. The adaptive vector has been removed
 * (the icon now resolves to the raster mipmap PNGs), and this load is wrapped in
 * runCatching so any future resource failure degrades to "no mark" instead of
 * taking down every screen that shows the brand.
 */
@Composable
fun AppIconMark(
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }.coerceAtLeast(1)

    val bitmap = remember(sizePx) {
        runCatching {
            val drawable = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher_round)
                ?: AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)
            drawable?.toBitmap(width = sizePx, height = sizePx)?.asImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
