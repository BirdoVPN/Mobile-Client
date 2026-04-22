package app.birdo.vpn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.ui.theme.*

enum class BirdoButtonVariant { Primary, Brand, Secondary, Ghost, Danger }
enum class BirdoButtonSize { Small, Medium, Large }

/**
 * Unified Birdo button. Primary = solid white on dark (legacy compat).
 * Brand = purple→pink gradient (new hero CTA). Secondary = bordered glass.
 * Ghost = text only. Danger = red.
 */
@Composable
fun BirdoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BirdoButtonVariant = BirdoButtonVariant.Primary,
    size: BirdoButtonSize = BirdoButtonSize.Medium,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
) {
    val height = when (size) {
        BirdoButtonSize.Small -> 40.dp
        BirdoButtonSize.Medium -> 48.dp
        BirdoButtonSize.Large -> 56.dp
    }
    val fontSize = when (size) {
        BirdoButtonSize.Small -> 13.sp
        BirdoButtonSize.Medium -> 14.sp
        BirdoButtonSize.Large -> 16.sp
    }
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120, easing = BirdoMotion.EaseStandard),
        label = "press",
    )

    val (bgColor, fgColor, brush, borderBrush) = when (variant) {
        BirdoButtonVariant.Primary -> Quad(Color.White, Color.Black, null as Brush?, null as Brush?)
        BirdoButtonVariant.Brand -> Quad(Color.Transparent, Color.White, BirdoBrand.PrimaryGradient, null)
        BirdoButtonVariant.Secondary -> Quad(GlassStrong, BirdoWhite, null, BirdoBrand.GlassStrokeGradient)
        BirdoButtonVariant.Ghost -> Quad(Color.Transparent, BirdoWhite80, null, null)
        BirdoButtonVariant.Danger -> Quad(BirdoRed.copy(alpha = 0.12f), BirdoRed, null, null)
    }
    val effectiveBg = if (enabled) bgColor else BirdoWhite10
    val effectiveFg = if (enabled) fgColor else BirdoWhite40

    Box(
        modifier = modifier
            .height(height)
            .scale(pressScale)
            .clip(shape)
            .then(if (brush != null && enabled) Modifier.background(brush, shape) else Modifier.background(effectiveBg, shape))
            .then(if (borderBrush != null) Modifier.border(1.dp, borderBrush, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !isLoading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (size == BirdoButtonSize.Small) 14.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = effectiveFg,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = effectiveFg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = effectiveFg,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.alpha(if (enabled) 1f else 0.7f),
            )
        }
    }
}

/** Tiny helper instead of Pair-of-Pairs for readability. */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
