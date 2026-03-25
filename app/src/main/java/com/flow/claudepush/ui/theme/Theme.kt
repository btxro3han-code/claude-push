package com.flow.claudepush.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Witcher silver-grey palette
private val WitcherColorScheme = darkColorScheme(
    primary = Color(0xFF8C8F93),         // Silver grey
    onPrimary = Color(0xFF1A1C1E),       // Dark charcoal
    primaryContainer = Color(0xFF3A3D40), // Dark grey
    onPrimaryContainer = Color(0xFFC8CBCE), // Light silver
    secondary = Color(0xFF787B7E),       // Medium grey
    onSecondary = Color(0xFF1A1C1E),
    secondaryContainer = Color(0xFF2E3134),
    onSecondaryContainer = Color(0xFFB0B3B6),
    tertiary = Color(0xFF6E7174),        // Darker accent grey
    onTertiary = Color(0xFFE0E2E4),
    background = Color(0xFF121416),      // Near black
    onBackground = Color(0xFFE0E2E4),    // Light text
    surface = Color(0xFF1A1C1E),         // Dark surface
    onSurface = Color(0xFFE0E2E4),
    surfaceVariant = Color(0xFF2E3134),  // Slightly lighter surface
    onSurfaceVariant = Color(0xFF8C8F93),
    outline = Color(0xFF4A4D50),
    error = Color(0xFFCF6679),
)

@Composable
fun ClaudePushTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WitcherColorScheme, content = content)
}
