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

private val BirdoLightColorScheme = lightColorScheme(
    primary = Color(0xFF1A1A2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8F0),
    onPrimaryContainer = Color(0xFF1A1A2E),
    secondary = BirdoPurpleDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E5F5),
    onSecondaryContainer = Color(0xFF4A148C),
    tertiary = BirdoBlue,
    onTertiary = Color.White,
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1A1A2E),
    surface = Color.White,
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFF0F0F5),
    onSurfaceVariant = Color(0xFF5A5A6E),
    outline = Color(0xFFD0D0DE),
    outlineVariant = Color(0xFFE8E8F0),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFB71C1C),
)

@Composable
fun BirdoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) BirdoDarkColorScheme else BirdoLightColorScheme

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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BirdoTypography,
        shapes = BirdoShapes,
        content = content,
    )
}
