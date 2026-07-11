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
    val AccentDeep  = Color(0xFF047857)  // emerald-700
    val Accent      = BirdoAccent         // emerald-500 #10B981
    val AccentSoft  = Color(0xFF6EE7B7)   // emerald-300
    val Cyan        = Color(0xFF22D3EE)   // cyan-400
    val Teal        = Color(0xFF14B8A6)   // teal-500
    val Indigo      = Color(0xFF6366F1)   // indigo-500

    // ── Surface elevation tiers (dark theme) ──────────────────────────
    val Surface0 = Color(0xFF050507)   // App background
    val Surface1 = Color(0xFF0B0B10)   // Cards
    val Surface2 = Color(0xFF12121A)   // Raised cards
    val Surface3 = Color(0xFF1A1A24)   // Modals / popovers
    val Hairline = Color(0x1FFFFFFF)   // 12% white — primary border
    val HairlineSoft = Color(0x14FFFFFF) // 8% white — subtle divider

    // ── Brushes ───────────────────────────────────────────────────────

    /**
     * Primary brand fill — a restrained, DEEP emerald gradient (emerald-700 →
     * emerald-900).
     *
     * Deliberately dark: this is the idle Connect CTA, and the connected one is
     * a luminous mint. With a green brand, hue can no longer carry connection
     * state, so the two must differ by luminance instead — see the note in
     * Color.kt. A bright emerald here would make "protected" and "not
     * protected" look alike, which on a VPN is a privacy bug, not a style one.
     */
    val PrimaryGradient: Brush
        get() = Brush.linearGradient(
            colors = listOf(Color(0xFF047857), Color(0xFF064E3B)),
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

    /** Disconnected ambient (subtle emerald bloom). */
    val IdleGradient: Brush
        get() = Brush.radialGradient(
            colors = listOf(Accent.copy(alpha = 0.18f), Color.Transparent),
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

    /** Brand text gradient — mint→teal for accent words. */
    val BrandTextGradient: Brush
        get() = Brush.linearGradient(
            colors = listOf(AccentSoft, Teal),
        )

    // ── Plan tier identity ────────────────────────────────────────────
    // Single source of truth for subscription-tier colors, so a tier looks the
    // same on every screen (Profile, Subscription, badges). Tiers stay
    // distinguishable inside the emerald family: SOVEREIGN is the brand itself,
    // OPERATIVE steps to teal, RECON stays neutral slate.

    /** Accent color for a subscription plan slug (case-insensitive). */
    fun planAccent(plan: String?): Color = when (plan?.uppercase()) {
        "SOVEREIGN" -> Accent
        "OPERATIVE" -> Teal
        else -> Color(0xFF64748B) // slate-500 — RECON / free tier
    }

    /** Hero gradient for a subscription plan slug (case-insensitive). */
    fun planGradient(plan: String?): Brush = when (plan?.uppercase()) {
        "SOVEREIGN" -> Brush.linearGradient(listOf(Color(0xFF059669), Color(0xFF064E3B)))
        "OPERATIVE" -> Brush.linearGradient(listOf(Color(0xFF0D9488), Color(0xFF115E59)))
        else -> Brush.linearGradient(listOf(Color(0xFF475569), Color(0xFF334155)))
    }
}
