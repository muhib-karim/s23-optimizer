package dev.muhib.s23optimizer.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun OptimizerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF9BD7CA), onPrimary = Color(0xFF00382F),
        secondary = Color(0xFFC2CBC6), tertiary = Color(0xFFE3C38C),
        background = Color(0xFF101416), surface = Color(0xFF101416),
        surfaceContainer = Color(0xFF1B2224), onSurface = Color(0xFFE0E6E6),
        onSurfaceVariant = Color(0xFFB9C4C5), error = Color(0xFFFFB4AB)
    ), typography = Typography(), content = content)
}
