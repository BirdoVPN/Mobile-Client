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
    val Teal        = Color(0xFF14B8A6)   // teal-500 — OPERATIVE plan identity

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

    /** Disconnected ambient (subtle emerald bloom). */
    val IdleGradient: Brush
        get() = Brush.radialGradient(
            colors = listOf(Accent.copy(alpha = 0.18f), Color.Transparent),
            radius = 800f,
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
