package app.birdo.vpn.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Birdo Brand Colors ──────────────────────────────────────────────────────
// Exactly matching the Windows desktop client's CSS variables / Tailwind config

// Core backgrounds — matches CSS --background: #050505
val BirdoBlack = Color(0xFF000000)       // App background (pure black)
val BirdoBackground = Color(0xFF050505)  // --background
val BirdoSurface = Color(0xFF0D0D0D)     // Slightly lighter surface
val BirdoSurfaceVariant = Color(0xFF1A1A1A) // rgba(26,26,26,0.95) from --card
val BirdoCard = Color(0xB314141A)        // Glass card: rgba(20,20,25,0.7)
val BirdoBorder = Color(0x14FFFFFF)      // rgba(255,255,255,0.08)

// Glass backgrounds
val GlassLight = Color(0x08FFFFFF)       // rgba(255,255,255,0.03)
val GlassStrong = Color(0x0FFFFFFF)      // rgba(255,255,255,0.06)
val GlassInput = Color(0x0AFFFFFF)       // rgba(255,255,255,0.04)

// White scale
val BirdoWhite = Color(0xFFF2F2F2)       // --foreground
val BirdoWhite80 = Color(0xCCFFFFFF)     // 80% white
val BirdoWhite60 = Color(0x99FFFFFF)     // 60% — labels
val BirdoWhite40 = Color(0x66FFFFFF)     // 40% — muted text
val BirdoWhite20 = Color(0x33FFFFFF)     // 20% — borders/toggles
val BirdoWhite10 = Color(0x1AFFFFFF)     // 10% — subtle bg
val BirdoWhite05 = Color(0x0DFFFFFF)     // 5% — very subtle

// ─── Primary accent — EMERALD ────────────────────────────────────────────────
// Matches the web rebrand (birdo.app): emerald-500 #10B981 / emerald-600
// #059669, keyed off the green hero globe.
val BirdoAccent = Color(0xFF10B981)      // emerald-500 — primary accent
val BirdoAccentDeep = Color(0xFF059669)  // emerald-600
val BirdoAccentSoft = Color(0xFF6EE7B7)  // emerald-300 — focus rings, links
val BirdoAccentBg = Color(0x1A10B981)    // 10% opacity

// ─── Status colors ───────────────────────────────────────────────────────────
// The brand is now green, so "connected" can no longer be signalled by hue
// alone — an emerald idle button and a green connected button would look the
// same, and misreading that on a VPN is a privacy problem, not a cosmetic one.
// Connection state is separated by LUMINANCE instead: the idle CTA is a deep,
// dark emerald (BirdoBrand.PrimaryGradient) and the connected one is this
// luminous mint, plus the glow and the pulsing dot.
val BirdoGreen = Color(0xFF34D399)       // emerald-400 — Connected (luminous)
val BirdoGreenLight = Color(0xFF6EE7B7)  // emerald-300 — text
val BirdoGreenBg = Color(0x1A34D399)     // 10% opacity — status badge bg
val BirdoGreenShadow = Color(0x4D34D399) // 30% opacity — glow

val BirdoYellow = Color(0xFFEAB308)      // yellow-500 — Connecting state
val BirdoYellowLight = Color(0xFFFACC15) // yellow-400 — text
val BirdoYellowBg = Color(0x1AEAB308)    // 10% opacity

val BirdoRed = Color(0xFFF87171)         // red-400 — Error state
val BirdoRedBg = Color(0x1AF87171)       // 10% opacity

val BirdoBlue = Color(0xFF3B82F6)        // blue-500 — Info / P2P
val BirdoBlueBg = Color(0x1A3B82F6)

// Emerald for update UI
val BirdoEmerald = Color(0xFF10B981)     // emerald-500
val BirdoEmeraldBg = Color(0x1A10B981)

// Primary button — solid white on dark (matching Windows .btn-primary)
val BirdoPrimary = Color.White
val BirdoOnPrimary = Color.Black

// Muted
val BirdoMuted = Color(0xFFA6A6A6)       // --muted-foreground

// ─── "Dim Light" Theme Tokens ─────────────────────────────────────────────
// User feedback: pure white light mode is too bright. This is a near-dark
// neutral that reads as "light" against the OLED-black dark theme but is
// still soft on the eyes — think GitHub's dim/grey theme.
val BirdoLightBackground       = Color(0xFF1B1C24) // App background — slate near-black
val BirdoLightSurface          = Color(0xFF22232C) // Cards
val BirdoLightSurfaceVariant   = Color(0xFF2A2B36) // Raised cards / inputs
val BirdoLightSurfaceElevated  = Color(0xFF2F3040) // Modals / popovers
val BirdoLightOnBackground     = Color(0xFFE8E9F0) // Body text
val BirdoLightOnSurfaceVariant = Color(0xFFB7B9C9) // Secondary text
val BirdoLightOutline          = Color(0x33FFFFFF) // Strong border
val BirdoLightOutlineSoft      = Color(0x1AFFFFFF) // Subtle divider
val BirdoLightAccent           = Color(0xFF34D399) // emerald-400 — pops on dim slate
val BirdoLightAccentBg         = Color(0x2910B981) // 16% emerald on dim grey

// ─── Semantic Color Palette (theme-aware) ────────────────────────────────────
/**
 * Semantic colour bundle that swaps based on the active theme. Read it via
 * `BirdoColors.current` from any composable. Use these in NEW screens — legacy
 * screens still use the literal BirdoBlack/BirdoWhite tokens above for the
 * dark-only design but will get correct text contrast through MaterialTheme.
 */
@androidx.compose.runtime.Immutable
data class BirdoSemanticPalette(
    val isLight: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceElevated: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceFaint: Color,
    val hairline: Color,
    val hairlineSoft: Color,
    val accent: Color,
    val accentBg: Color,
    val mapWater: Color,
    val mapLand: Color,
    val mapDot: Color,
    val mapDotMuted: Color,
)

val BirdoDarkPalette = BirdoSemanticPalette(
    isLight = false,
    background = Color(0xFF050507),
    surface = Color(0xFF0B0B10),
    surfaceRaised = Color(0xFF12121A),
    surfaceElevated = Color(0xFF1A1A24),
    onBackground = BirdoWhite,
    onSurface = Color.White,
    onSurfaceMuted = BirdoWhite60,
    onSurfaceFaint = BirdoWhite40,
    hairline = Color(0x1FFFFFFF),
    hairlineSoft = Color(0x14FFFFFF),
    accent = BirdoAccent,
    accentBg = BirdoAccentBg,
    mapWater = Color(0x4D0B0B1A),       // translucent so the pixel canvas reads through
    mapLand = Color(0x336EE7B7),        // dim mint land
    mapDot = BirdoAccent,
    mapDotMuted = Color(0x6610B981),
)

val BirdoLightPalette = BirdoSemanticPalette(
    isLight = true,
    background = BirdoLightBackground,
    surface = BirdoLightSurface,
    surfaceRaised = BirdoLightSurfaceVariant,
    surfaceElevated = BirdoLightSurfaceElevated,
    onBackground = BirdoLightOnBackground,
    onSurface = BirdoLightOnBackground,
    onSurfaceMuted = BirdoLightOnSurfaceVariant,
    onSurfaceFaint = Color(0xFF7A7C8E),
    hairline = Color(0x33FFFFFF),
    hairlineSoft = Color(0x1AFFFFFF),
    accent = BirdoLightAccent,
    accentBg = BirdoLightAccentBg,
    mapWater = Color(0x661B1C24),       // translucent matching dim background
    mapLand = Color(0x5534D399),        // soft mint on dim background
    mapDot = BirdoLightAccent,
    mapDotMuted = Color(0x8034D399),
)

val LocalBirdoColors = androidx.compose.runtime.staticCompositionLocalOf { BirdoDarkPalette }

/** Convenience accessor — `BirdoColors.current` from any composable. */
object BirdoColors {
    val current: BirdoSemanticPalette
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalBirdoColors.current
}
