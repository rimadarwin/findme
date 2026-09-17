package it.xcc.findme.receiver

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonBlue = Color(0xFF00D9FF)
val MonitoringGreen = Color(0xFF38F28D)

private val FindMeReceiverColors = darkColorScheme(
    primary = NeonBlue,
    onPrimary = Color(0xFF001B24),
    secondary = Color(0xFF4D8DFF),
    background = Color(0xFF020812),
    onBackground = Color(0xFFE8F7FF),
    surface = Color(0xFF091522),
    onSurface = Color(0xFFE8F7FF),
    surfaceVariant = Color(0xFF10263A),
    onSurfaceVariant = Color(0xFFAAC9D8),
    error = Color(0xFFFF6B8A),
)

@Composable
fun FindMeReceiverTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FindMeReceiverColors, content = content)
}
