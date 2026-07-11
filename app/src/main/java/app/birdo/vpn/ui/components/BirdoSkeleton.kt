package app.birdo.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.birdo.vpn.ui.theme.BirdoColors

/**
 * Ghost-loading primitives so lists shimmer into place instead of popping
 * in from a blank void. One shared alpha pulse per skeleton group keeps the
 * cost to a single animated value.
 */
@Composable
fun birdoSkeletonPulse(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    return alpha
}

/** A single ghosted block. */
@Composable
fun BirdoSkeletonBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    pulseAlpha: Float = 1f,
) {
    Box(
        modifier = modifier
            .alpha(pulseAlpha)
            .clip(RoundedCornerShape(cornerRadius))
            .background(BirdoColors.current.surfaceRaised),
    )
}

/**
 * Ghost row shaped like a server card: leading flag chip, two text bars,
 * trailing status column. Use 5-6 in a Column while a list first loads.
 */
@Composable
fun BirdoSkeletonServerRow(
    modifier: Modifier = Modifier,
    pulseAlpha: Float = 1f,
) {
    BirdoCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BirdoSkeletonBox(
                modifier = Modifier.size(44.dp),
                cornerRadius = 12.dp,
                pulseAlpha = pulseAlpha,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BirdoSkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(14.dp),
                    pulseAlpha = pulseAlpha,
                )
                Spacer(Modifier.height(8.dp))
                BirdoSkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.35f)
                        .height(11.dp),
                    pulseAlpha = pulseAlpha,
                )
            }
            Spacer(Modifier.width(12.dp))
            BirdoSkeletonBox(
                modifier = Modifier.size(width = 36.dp, height = 12.dp),
                pulseAlpha = pulseAlpha,
            )
        }
    }
}
