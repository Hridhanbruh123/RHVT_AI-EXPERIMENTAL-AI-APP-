package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Indigo (Friendly Default)
private val IndigoLightScheme = lightColorScheme(
    primary = Color(0xFF2B5C8F),
    secondary = Color(0xFF535F70),
    tertiary = Color(0xFF6B5778),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF191C1F),
    onSurface = Color(0xFF191C1F),
    primaryContainer = Color(0xFFD6E3F7),
    onPrimaryContainer = Color(0xFF001B3E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E)
)

private val IndigoDarkScheme = darkColorScheme(
    primary = Color(0xFFABC7E9),
    secondary = Color(0xFFBAC8DB),
    tertiary = Color(0xFFD6BFE3),
    background = Color(0xFF0F1216),
    surface = Color(0xFF141A21),
    onPrimary = Color(0xFF0D3057),
    onSecondary = Color(0xFF243141),
    onTertiary = Color(0xFF3B2947),
    onBackground = Color(0xFFE2E2E6),
    onSurface = Color(0xFFE2E2E6),
    primaryContainer = Color(0xFF274465),
    onPrimaryContainer = Color(0xFFD2E4FF),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7D0)
)

// Lavender (Creative Lavender)
private val LavenderLightScheme = lightColorScheme(
    primary = Color(0xFF75519E),
    secondary = Color(0xFF645C72),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFAF7FD),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1E1A22),
    onSurface = Color(0xFF1E1A22),
    primaryContainer = Color(0xFFF2DCFF),
    onPrimaryContainer = Color(0xFF2C0B57),
    surfaceVariant = Color(0xFFE6E0EC),
    onSurfaceVariant = Color(0xFF49454E)
)

private val LavenderDarkScheme = darkColorScheme(
    primary = Color(0xFFD3BBF6),
    secondary = Color(0xFFCEC2DC),
    tertiary = Color(0xFFEBB9C7),
    background = Color(0xFF131018),
    surface = Color(0xFF1B1621),
    onPrimary = Color(0xFF41206C),
    onSecondary = Color(0xFF352D40),
    onTertiary = Color(0xFF492532),
    onBackground = Color(0xFFE6E1E9),
    onSurface = Color(0xFFE6E1E9),
    primaryContainer = Color(0xFF5A3984),
    onPrimaryContainer = Color(0xFFF1DBFF),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4D0)
)

// Mint (Calm Nature)
private val MintLightScheme = lightColorScheme(
    primary = Color(0xFF1B6C4F),
    secondary = Color(0xFF4D6356),
    tertiary = Color(0xFF3A6572),
    background = Color(0xFFF4F9F6),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF191C1A),
    onSurface = Color(0xFF191C1A),
    primaryContainer = Color(0xFFA5F4D0),
    onPrimaryContainer = Color(0xFF002114),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943)
)

private val MintDarkScheme = darkColorScheme(
    primary = Color(0xFF8AD7B3),
    secondary = Color(0xFFB4CBBF),
    tertiary = Color(0xFFA2CEDC),
    background = Color(0xFF0E1311),
    surface = Color(0xFF141C18),
    onPrimary = Color(0xFF003825),
    onSecondary = Color(0xFF20352A),
    onTertiary = Color(0xFF033541),
    onBackground = Color(0xFFE1E3E0),
    onSurface = Color(0xFFE1E3E0),
    primaryContainer = Color(0xFF005139),
    onPrimaryContainer = Color(0xFFA2FCD1),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1)
)

// High Contrast
private val HighContrastLightScheme = lightColorScheme(
    primary = Color(0xFF000000),
    secondary = Color(0xFF000000),
    tertiary = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000),
    primaryContainer = Color(0xFFEEEEEE),
    onPrimaryContainer = Color(0xFF000000),
    surfaceVariant = Color(0xFFDDDDDD),
    onSurfaceVariant = Color(0xFF000000)
)

private val HighContrastDarkScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    secondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF333333),
    onPrimaryContainer = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF444444),
    onSurfaceVariant = Color(0xFFFFFFFF)
)

@Composable
fun MyApplicationTheme(
    themeSelection: String = "System", // System, Light, Dark
    colorPalette: String = "Indigo", // Indigo, Lavender, Mint
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeSelection) {
        "Light" -> false
        "Dark" -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        highContrast -> {
            if (darkTheme) HighContrastDarkScheme else HighContrastLightScheme
        }
        colorPalette == "Lavender" -> {
            if (darkTheme) LavenderDarkScheme else LavenderLightScheme
        }
        colorPalette == "Mint" -> {
            if (darkTheme) MintDarkScheme else MintLightScheme
        }
        else -> {
            if (darkTheme) IndigoDarkScheme else IndigoLightScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
