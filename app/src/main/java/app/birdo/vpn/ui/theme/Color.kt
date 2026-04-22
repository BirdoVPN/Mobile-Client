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

// Primary accent — purple (#A855F7) matching Windows client
val BirdoPurple = Color(0xFFA855F7)      // Primary accent
val BirdoPurpleDark = Color(0xFF7C3AED)  // Purple-600
val BirdoPurpleLight = Color(0xFFC084FC) // Purple-300
val BirdoPurpleBg = Color(0x1AA855F7)    // 10% opacity

// Status colors — matching Windows client Tailwind classes
val BirdoGreen = Color(0xFF22C55E)       // green-500 — Connected state
val BirdoGreenLight = Color(0xFF4ADE80)  // green-400 — text
val BirdoGreenBg = Color(0x1A22C55E)     // 10% opacity — status badge bg
val BirdoGreenShadow = Color(0x4D22C55E) // 30% opacity — glow

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

// ─── Light Theme Tokens ──────────────────────────────────────────────────────
// A polished neutral light palette. Uses warm-grey tones with the same purple
// brand accent so the brand identity carries across themes.
val BirdoLightBackground       = Color(0xFFF7F7FB) // App background
val BirdoLightSurface          = Color(0xFFFFFFFF) // Cards
val BirdoLightSurfaceVariant   = Color(0xFFF1F2F7) // Raised cards / inputs
val BirdoLightSurfaceElevated  = Color(0xFFFAFAFD) // Modals / popovers
val BirdoLightOnBackground     = Color(0xFF0F1020) // Body text
val BirdoLightOnSurfaceVariant = Color(0xFF55576B) // Secondary text
val BirdoLightOutline          = Color(0xFFDADCE6) // Strong border
val BirdoLightOutlineSoft      = Color(0xFFEBEDF3) // Subtle divider
val BirdoLightPrimary          = Color(0xFF6D28D9) // violet-700 (matches dark accent)
val BirdoLightAccentBg         = Color(0x14A855F7) // 8% purple

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
    accent = BirdoPurple,
    accentBg = BirdoPurpleBg,
    mapWater = Color(0xFF080812),
    mapLand = Color(0x1AC4B5FD),       // dim violet land
    mapDot = Color(0xFFA855F7),
    mapDotMuted = Color(0x66A855F7),
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
    onSurfaceFaint = Color(0xFF8A8C9D),
    hairline = Color(0x14000000),
    hairlineSoft = Color(0x0A000000),
    accent = BirdoLightPrimary,
    accentBg = BirdoLightAccentBg,
    mapWater = Color(0xFFE7E9F2),
    mapLand = Color(0x336D28D9),
    mapDot = BirdoLightPrimary,
    mapDotMuted = Color(0x666D28D9),
)

val LocalBirdoColors = androidx.compose.runtime.staticCompositionLocalOf { BirdoDarkPalette }

/** Convenience accessor — `BirdoColors.current` from any composable. */
object BirdoColors {
    val current: BirdoSemanticPalette
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalBirdoColors.current
}
