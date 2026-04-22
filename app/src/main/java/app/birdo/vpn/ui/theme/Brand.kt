package app.birdo.vpn.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Brand gradients & extended palette tokens used across the new component
 * library. Pure colors live in Color.kt — this file composes Brushes and
 * elevation/state tints derived from them.
 */
object BirdoBrand {
    // ── Core brand stops ──────────────────────────────────────────────
    val PurpleDeep  = Color(0xFF6D28D9) // violet-700
    val Purple      = BirdoPurple        // #A855F7
    val PurpleSoft  = Color(0xFFC4B5FD)  // violet-300
    val Pink        = Color(0xFFEC4899)  // pink-500
    val Cyan        = Color(0xFF22D3EE)  // cyan-400
    val Teal        = Color(0xFF14B8A6)  // teal-500
    val Indigo      = Color(0xFF6366F1)  // indigo-500

    // ── Surface elevation tiers (dark theme) ──────────────────────────
    val Surface0 = Color(0xFF050507)   // App background
    val Surface1 = Color(0xFF0B0B10)   // Cards
    val Surface2 = Color(0xFF12121A)   // Raised cards
    val Surface3 = Color(0xFF1A1A24)   // Modals / popovers
    val Hairline = Color(0x1FFFFFFF)   // 12% white — primary border
    val HairlineSoft = Color(0x14FFFFFF) // 8% white — subtle divider

    // ── Brushes ───────────────────────────────────────────────────────

    /** Hero brand gradient — used for primary CTA & headline accents. */
    val PrimaryGradient: Brush
        get() = Brush.linearGradient(
            colors = listOf(Purple, Pink),
        )

    /** Cool secondary gradient — info / tech accents. */
    val InfoGradient: Brush
        get() = Brush.linearGradient(
            colors = listOf(Indigo, Cyan),
        )

    /** Connected glow gradient (soft green halo). */
    val ConnectedGradient: Brush
        get() = Brush.radialGradient(
            colors = listOf(BirdoGreen.copy(alpha = 0.28f), Color.Transparent),
            radius = 700f,
        )

    /** Disconnected ambient (subtle purple bloom). */
    val IdleGradient: Brush
        get() = Brush.radialGradient(
            colors = listOf(Purple.copy(alpha = 0.18f), Color.Transparent),
            radius = 800f,
        )

    /** Error halo. */
    val ErrorGradient: Brush
        get() = Brush.radialGradient(
            colors = listOf(BirdoRed.copy(alpha = 0.25f), Color.Transparent),
            radius = 700f,
        )

    /** Subtle vertical fade from surface to transparent (for top fades). */
    val SurfaceFadeTop: Brush
        get() = Brush.verticalGradient(
            colors = listOf(Surface0.copy(alpha = 0.85f), Color.Transparent),
        )

    /** Glass card stroke gradient — silver→transparent for premium border. */
    val GlassStrokeGradient: Brush
        get() = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.04f),
                Color.White.copy(alpha = 0.12f),
            ),
        )

    /** Headline text gradient — white→soft white. */
    val HeadlineTextGradient: Brush
        get() = Brush.verticalGradient(
            colors = listOf(Color.White, Color.White.copy(alpha = 0.55f)),
        )

    /** Brand text gradient — purple→pink for accent words. */
    val BrandTextGradient: Brush
        get() = Brush.linearGradient(
            colors = listOf(PurpleSoft, Pink),
        )
}
