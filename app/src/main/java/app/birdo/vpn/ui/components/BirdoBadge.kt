package app.birdo.vpn.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.ui.theme.*

enum class BadgeTone { Neutral, Success, Warning, Danger, Info, Brand }

/** Pill-shaped badge with optional pulsing dot. */
@Composable
fun BirdoBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.Neutral,
    icon: ImageVector? = null,
    pulseDot: Boolean = false,
) {
    val (bg, fg, border) = when (tone) {
        BadgeTone.Neutral -> Triple(BirdoWhite05, BirdoWhite80, BirdoBrand.HairlineSoft)
        BadgeTone.Success -> Triple(BirdoGreenBg, BirdoGreenLight, BirdoGreen.copy(alpha = 0.3f))
        BadgeTone.Warning -> Triple(BirdoYellowBg, BirdoYellowLight, BirdoYellow.copy(alpha = 0.3f))
        BadgeTone.Danger  -> Triple(BirdoRedBg, BirdoRed, BirdoRed.copy(alpha = 0.3f))
        BadgeTone.Info    -> Triple(BirdoBlueBg, BirdoBlue, BirdoBlue.copy(alpha = 0.3f))
        BadgeTone.Brand   -> Triple(BirdoPurpleBg, BirdoBrand.PurpleSoft, BirdoBrand.Purple.copy(alpha = 0.3f))
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pulseDot) {
                PulsingDot(color = fg)
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = fg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun PulsingDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "s",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "a",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .scale(scale)
                .alpha(alpha)
                .clip(CircleShape)
                .background(color),
        )
        Box(
            Modifier
                .size(size * 0.6f)
                .clip(CircleShape)
                .background(color),
        )
    }
}
