package app.luma.standby.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Settings/console UI palette. Deliberately dark: the app is a night-time
 * appliance, and a white settings screen at 2am is hostile.
 *
 * Container roles are spelled out explicitly - Material's defaults are purple,
 * which fights every theme in this app.
 */
private val LumaColors = darkColorScheme(
    primary = Color(0xFFD9B97E),
    onPrimary = Color(0xFF1A1509),
    primaryContainer = Color(0xFF3A3222),
    onPrimaryContainer = Color(0xFFF0DEB8),
    secondary = Color(0xFFE9A8C0),
    onSecondary = Color(0xFF2A121B),
    secondaryContainer = Color(0xFF3A3122),
    onSecondaryContainer = Color(0xFFF0DEB8),
    tertiary = Color(0xFF9EBAA5),
    onTertiary = Color(0xFF10231A),
    tertiaryContainer = Color(0xFF2A3A31),
    onTertiaryContainer = Color(0xFFDCE9E0),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFEDE9E2),
    surface = Color(0xFF141518),
    onSurface = Color(0xFFEDE9E2),
    surfaceVariant = Color(0xFF22242A),
    onSurfaceVariant = Color(0xFFB9B6AE),
    outline = Color(0xFF4A4D55),
    outlineVariant = Color(0xFF2E3138),
)

@Composable
fun LumaAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LumaColors,
        typography = Typography(),
        content = content,
    )
}
