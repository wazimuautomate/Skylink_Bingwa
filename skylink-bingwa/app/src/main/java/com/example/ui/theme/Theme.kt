package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = PrimaryActionGreen,
    onPrimary = LightMainSurface,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightPrimaryText,
    secondary = InfoActionBlue,
    onSecondary = LightMainSurface,
    secondaryContainer = InfoContainer,
    onSecondaryContainer = LightPrimaryText,
    tertiary = PromotionOrange,
    onTertiary = LightMainSurface,
    tertiaryContainer = PromotionContainer,
    onTertiaryContainer = LightPrimaryText,
    background = LightAppBackground,
    onBackground = LightPrimaryText,
    surface = LightMainSurface,
    onSurface = LightPrimaryText,
    surfaceVariant = LightSoftGroupedSurface,
    onSurfaceVariant = LightSecondaryText,
    outline = LightOutline,
    outlineVariant = LightDivider,
    error = LightError,
    onError = LightMainSurface,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightPrimaryText
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimaryAction,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkPrimaryText,
    secondary = DarkInformation,
    onSecondary = DarkAppBackground,
    secondaryContainer = DarkInformationContainer,
    onSecondaryContainer = DarkPrimaryText,
    tertiary = DarkPromotion,
    onTertiary = DarkAppBackground,
    tertiaryContainer = DarkPromotionContainer,
    onTertiaryContainer = DarkPrimaryText,
    background = DarkAppBackground,
    onBackground = DarkPrimaryText,
    surface = DarkMainSurface,
    onSurface = DarkPrimaryText,
    surfaceVariant = DarkSoftGroupedSurface,
    onSurfaceVariant = DarkSecondaryText,
    outline = DarkOutline,
    outlineVariant = DarkDivider,
    error = DarkError,
    onError = DarkAppBackground,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkPrimaryText
)

@Composable
fun SkylinkBingwaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
