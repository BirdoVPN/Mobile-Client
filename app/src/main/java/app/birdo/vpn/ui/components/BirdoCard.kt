package app.birdo.vpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.ui.theme.*

/**
 * Premium glass card with optional gradient hairline stroke and inner shadow
 * fallback (composable shadows are limited; we approximate with layered
 * surfaces). Use everywhere a card is needed for a uniform look.
 *
 * @param glow Optional brand glow brush rendered behind the card (e.g.
 *  [BirdoBrand.IdleGradient]). Leave null for plain glass.
 */
@Composable
fun BirdoCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    surface: Color = BirdoColors.current.surface,
    border: Brush? = BirdoBrand.GlassStrokeGradient,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    glow: Brush? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(surface, shape)
            .then(
                if (border != null) Modifier.border(1.dp, border, shape) else Modifier
            ),
    ) {
        if (glow != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(glow)
            )
        }
        Box(Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/** Lighter elevated surface variant for nested groups inside a card. */
@Composable
fun BirdoSubCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable () -> Unit,
) {
    val palette = BirdoColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = if (palette.isLight) palette.surfaceRaised else GlassLight,
        border = BorderStroke(1.dp, palette.hairlineSoft),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/**
 * Canonical uppercase section header — accent tick bar + tracked micro-label.
 * The single style for every settings/list section across the app.
 */
@Composable
fun BirdoSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val palette = BirdoColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.accent.copy(alpha = 0.85f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            color = palette.onSurfaceMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onActionClick != null) {
            Text(
                text = actionLabel,
                color = BirdoBrand.AccentSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
