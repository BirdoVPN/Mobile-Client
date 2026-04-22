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
    secondary = BirdoPurple,
    onSecondary = BirdoBlack,
    secondaryContainer = BirdoPurpleBg,
    onSecondaryContainer = BirdoPurple,
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

// ── Light scheme: refined warm-neutral palette (Fortune-500 polish) ───────
//   Background: near-white with a subtle cool tint
//   Cards:      pure white on top of bg for subtle elevation
//   Accent:     the same purple as dark theme so brand identity carries over
private val BirdoLightColorScheme = lightColorScheme(
    primary = BirdoLightPrimary,
    onPrimary = Color.White,
    primaryContainer = BirdoLightAccentBg,
    onPrimaryContainer = BirdoLightPrimary,
    secondary = BirdoPurpleDark,
    onSecondary = Color.White,
    secondaryContainer = BirdoLightAccentBg,
    onSecondaryContainer = BirdoLightPrimary,
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
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
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
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
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
