package app.birdo.vpn.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Birdo spacing scale — 4dp base grid for vertical rhythm.
 * Use these instead of bare dp values so density stays consistent
 * across screens and tablet redesigns.
 */
object Spacing {
    val xxs = 2.dp
    val xs  = 4.dp
    val sm  = 8.dp
    val md  = 12.dp
    val lg  = 16.dp
    val xl  = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
    val giant = 64.dp

    // Screen edge padding (responsive friendly)
    val screenH = 20.dp
    val screenV = 16.dp

    // Touch target floor (Material guideline)
    val touch = 48.dp
}
