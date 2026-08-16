package dev.lumenberg.ui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Wallpaper colours when Android offers them, a hand-picked amber-on-ink palette when it
 * does not. Flat surfaces only: no gradients, no glass.
 */
@Composable
fun LumenbergTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Ink
        else -> Paper
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private val Amber = Color(0xFFF2B441)
private val Ink = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF251A00),
    primaryContainer = Color(0xFF3A2C05),
    onPrimaryContainer = Color(0xFFFFE0A3),
    secondary = Color(0xFFD3C4A4),
    surface = Color(0xFF16150F),
    onSurface = Color(0xFFEDE5D6),
    surfaceContainer = Color(0xFF211F17),
    surfaceContainerHigh = Color(0xFF2B2820),
    onSurfaceVariant = Color(0xFFB6AE9C),
    outline = Color(0xFF4B473C),
    error = Color(0xFFFFB4A4),
)
private val Paper = lightColorScheme(
    primary = Color(0xFF7A5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6B5D3F),
    surface = Color(0xFFFDF8EE),
    onSurface = Color(0xFF1E1B13),
    surfaceContainer = Color(0xFFF3EDE2),
    surfaceContainerHigh = Color(0xFFEDE7DC),
    onSurfaceVariant = Color(0xFF4C4636),
    outline = Color(0xFF7E7664),
    error = Color(0xFFA4372A),
)

/** One motion vocabulary, so nothing on screen moves at a speed nothing else uses. */
object Motion {
    val Snap = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    val Settle = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow,
    )
    val SizeSettle = spring<androidx.compose.ui.unit.IntSize>(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMediumLow,
    )
    val Fade = tween<Float>(durationMillis = 160)
    val Radius = 26.dp
    val Gap = 10.dp
}
