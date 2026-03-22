package io.digibyte.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// DigiByte brand colors
val DigiByteNavy = Color(0xFF002352)
val DigiByteBlue = Color(0xFF0066CC)
val DigiByteAccent = Color(0xFF00AAFF)
val DigiByteGreen = Color(0xFF4CAF50)
val DigiByteRed = Color(0xFFE53935)

private val DarkColorScheme = darkColorScheme(
    primary = DigiByteBlue,
    onPrimary = Color.White,
    primaryContainer = DigiByteNavy,
    onPrimaryContainer = Color.White,
    secondary = DigiByteAccent,
    background = Color(0xFF0A1628),
    surface = Color(0xFF1A2742),
    surfaceVariant = Color(0xFF243352),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB0BEC5),
    error = DigiByteRed,
)

private val LightColorScheme = lightColorScheme(
    primary = DigiByteBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = DigiByteNavy,
    secondary = DigiByteAccent,
    background = Color(0xFFF8F9FA),
    surface = Color.White,
    onBackground = Color(0xFF1A1A2E),
    onSurface = Color(0xFF1A1A2E),
)

@Composable
fun DigiByteTheme(
    darkTheme: Boolean = true, // Default dark per mockup
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}
