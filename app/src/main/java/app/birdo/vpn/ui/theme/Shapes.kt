package app.birdo.vpn.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Shapes mapping for components that read from MaterialTheme.shapes.
 */
val BirdoShapes = Shapes(
    extraSmall = RoundedCornerShape(CornerSize(6.dp)),
    small = RoundedCornerShape(CornerSize(10.dp)),
    medium = RoundedCornerShape(CornerSize(14.dp)),
    large = RoundedCornerShape(CornerSize(18.dp)),
    extraLarge = RoundedCornerShape(CornerSize(24.dp)),
)
