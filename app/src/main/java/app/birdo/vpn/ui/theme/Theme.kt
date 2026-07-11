package app.birdo.vpn.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

private val BirdoDarkColorScheme = darkColorScheme(
    primary = BirdoPrimary,
    onPrimary = BirdoOnPrimary,
    primaryContainer = BirdoSurfaceVariant,
    onPrimaryContainer = BirdoWhite80,
    secondary = BirdoAccent,
    onSecondary = BirdoBlack,
    secondaryContainer = BirdoAccentBg,
    onSecondaryContainer = BirdoAccent,
    tertiary = BirdoBlue,
    onTertiary = BirdoBlack,
    background = BirdoBlack,
    onBackground = BirdoWhite,
    surface = BirdoSurface,
    onSurface = BirdoWhite,
    surfaceVariant = BirdoSurfaceVariant,
    onSurfaceVariant = BirdoWhite60,
    outline = BirdoBorder,
    outlineVariant = BirdoWhite10,
    error = BirdoRed,
    onError = BirdoBlack,
    errorContainer = BirdoRedBg,
    onErrorContainer = BirdoRed,
)

// ── "Dim Light" scheme — warm dark-grey, NOT pure white ──────────────────
//   Background: dim slate (#1B1C24) — not blinding
//   Cards:      slightly raised slate for layering
//   Accent:     softer violet that pops on the dim background
private val BirdoLightColorScheme = lightColorScheme(
    primary = BirdoLightAccent,
    onPrimary = Color(0xFF1B0F36),
    primaryContainer = BirdoLightAccentBg,
    onPrimaryContainer = BirdoLightAccent,
    secondary = BirdoAccentSoft,
    onSecondary = Color(0xFF1B0F36),
    secondaryContainer = BirdoLightAccentBg,
    onSecondaryContainer = BirdoLightAccent,
    tertiary = BirdoBlue,
    onTertiary = Color.White,
    background = BirdoLightBackground,
    onBackground = BirdoLightOnBackground,
    surface = BirdoLightSurface,
    onSurface = BirdoLightOnBackground,
    surfaceVariant = BirdoLightSurfaceVariant,
    onSurfaceVariant = BirdoLightOnSurfaceVariant,
    outline = BirdoLightOutline,
    outlineVariant = BirdoLightOutlineSoft,
    error = Color(0xFFF87171),
    onError = Color(0xFF2C0A0A),
    errorContainer = Color(0x33F87171),
    onErrorContainer = Color(0xFFFCA5A5),
)

/** Resolves a "system" / "dark" / "light" preference into a Boolean. */
@Composable
fun resolveDarkTheme(themeMode: String): Boolean = when (themeMode.lowercase()) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun BirdoTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode)
    val colorScheme = if (darkTheme) BirdoDarkColorScheme else BirdoLightColorScheme
    val birdoColors = if (darkTheme) BirdoDarkPalette else BirdoLightPalette

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowInsetsControllerCompat(window, view)
            // Dim-light theme is still dark enough to use light status-bar icons.
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalBirdoColors provides birdoColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BirdoTypography,
            shapes = BirdoShapes,
            content = content,
        )
    }
}
