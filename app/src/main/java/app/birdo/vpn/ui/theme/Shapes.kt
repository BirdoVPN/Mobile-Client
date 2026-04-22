package app.birdo.vpn.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Birdo corner radii. We bias slightly larger than Material defaults
 * for a softer, more premium feel.
 */
object BirdoRadii {
    val xs   = RoundedCornerShape(6.dp)
    val sm   = RoundedCornerShape(10.dp)
    val md   = RoundedCornerShape(14.dp)
    val lg   = RoundedCornerShape(18.dp)
    val xl   = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(999.dp)

    val topMd = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    val bottomMd = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
}

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
