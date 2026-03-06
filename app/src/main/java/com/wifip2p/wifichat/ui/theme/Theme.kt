package com.wifip2p.wifichat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Color Scheme
 *
 * WHY THESE COLORS:
 * - Blue theme matches common chat app aesthetics
 * - High contrast for readability
 * - Material 3 dynamic color system
 */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2196F3),      // Blue for primary actions
    onPrimary = Color.White,          // White text on blue
    primaryContainer = Color(0xFFBBDEFB),
    onPrimaryContainer = Color(0xFF1565C0),

    secondary = Color(0xFF03A9F4),
    onSecondary = Color.White,

    background = Color(0xFFFAFAFA),   // Light gray background
    onBackground = Color(0xFF212121),

    surface = Color.White,
    onSurface = Color(0xFF212121),

    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF757575),

    error = Color(0xFFD32F2F),
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2196F3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1565C0),
    onPrimaryContainer = Color(0xFFBBDEFB),

    secondary = Color(0xFF03A9F4),
    onSecondary = Color.White,

    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),

    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),

    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD),

    error = Color(0xFFEF5350),
    onError = Color.White
)

/**
 * App Theme
 *
 * WHAT THIS DOES:
 * Wraps the entire app in Material 3 theming
 * Supports both light and dark mode
 *
 * WHY MATERIAL 3:
 * - Latest Material Design standard
 * - Automatic dark mode support
 * - Consistent look and feel
 * - Beautiful components out of the box
 */
@Composable
fun WifiChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}