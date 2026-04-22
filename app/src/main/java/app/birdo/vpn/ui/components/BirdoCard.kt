package app.birdo.vpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    surface: Color = BirdoBrand.Surface1,
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = GlassLight,
        border = BorderStroke(1.dp, BirdoBrand.HairlineSoft),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** Section header with optional small action label on the right. */
@Composable
fun BirdoSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            color = BirdoWhite60,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                color = BirdoBrand.PurpleSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
