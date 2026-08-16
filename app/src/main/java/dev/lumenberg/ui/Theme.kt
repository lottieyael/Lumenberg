package dev.lumenberg.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Everything the user can change about how Lumenberg looks. */
data class Look(
    val appearance: Appearance = Appearance.System,
    val accent: Accent = Accent.Wallpaper,
    val surface: Surfaces = Surfaces.Soft,
    val header: Header = Header.Both,
) {
    fun save(context: Context) = context
        .getSharedPreferences("look", Context.MODE_PRIVATE).edit()
        .putString("appearance", appearance.name)
        .putString("accent", accent.name)
        .putString("surface", surface.name)
        .putString("header", header.name)
        .apply()

    companion object {
        fun load(context: Context): Look {
            val prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE)
            fun <T : Enum<T>> read(key: String, values: List<T>, fallback: T) =
                values.firstOrNull { it.name == prefs.getString(key, null) } ?: fallback
            return Look(
                appearance = read("appearance", Appearance.entries, Appearance.System),
                accent = read("accent", Accent.entries, Accent.Wallpaper),
                surface = read("surface", Surfaces.entries, Surfaces.Soft),
                header = read("header", Header.entries, Header.Both),
            )
        }
    }
}

enum class Appearance(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** What the bar across the top of the home screen shows. */
enum class Header(val label: String) {
    Both("Time and date"),
    Clock("Time"),
    Date("Date"),
    None("Nothing"),
    ;

    val showsClock: Boolean get() = this == Both || this == Clock
    val showsDate: Boolean get() = this == Both || this == Date
}

/** What the top bar shows, read where it is drawn. */
val LocalHeader = staticCompositionLocalOf { Header.Both }

/**
 * Wallpaper colours where the platform offers them, otherwise a pair per accent: a deep
 * shade for light backgrounds and a bright one for dark.
 */
enum class Accent(val label: String, val onLight: Color, val onDark: Color) {
    Wallpaper("Wallpaper", Color(0xFF7A5A00), Color(0xFFF2B441)),
    Amber("Amber", Color(0xFF7A5A00), Color(0xFFF2B441)),
    Ocean("Ocean", Color(0xFF0B5D82), Color(0xFF7DD3F0)),
    Forest("Forest", Color(0xFF2A6141), Color(0xFF86D6A6)),
    Violet("Violet", Color(0xFF5B4396), Color(0xFFC3B0FF)),
    Rose("Rose", Color(0xFF9A3355), Color(0xFFFFAFC6)),
    Slate("Slate", Color(0xFF44515E), Color(0xFFB6C4D2)),
    ;

    val available: Boolean
        get() = this != Wallpaper || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/** How much wallpaper shows through the cards. */
enum class Surfaces(val label: String, val alpha: Float) {
    Solid("Solid", 1f),
    Soft("Soft", 0.90f),
    Sheer("Sheer", 0.72f),

    /** Frosted: the wallpaper behind the card, blurred, under a tint. */
    Glass("Glass", 0.62f),
}

/** Which background treatment every card should use. */
val LocalSurfaceStyle = staticCompositionLocalOf { Surfaces.Soft }

/** Read by every card, so one setting reaches the whole screen. */
val LocalSurfaceAlpha = staticCompositionLocalOf { Surfaces.Soft.alpha }

@Composable
fun LumenbergTheme(look: Look = Look(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (look.appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    val colors = when {
        look.accent == Accent.Wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Ink.tinted(look.accent.onDark, dark = true)
        else -> Paper.tinted(look.accent.onLight, dark = false)
    }
    CompositionLocalProvider(
        LocalSurfaceAlpha provides look.surface.alpha,
        LocalSurfaceStyle provides look.surface,
        LocalHeader provides look.header,
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/**
 * Recolours a base scheme around one accent. Containers are the accent laid over the
 * surface rather than a second hand-picked colour, so every accent stays consistent with
 * the neutrals instead of needing a palette of its own.
 */
private fun ColorScheme.tinted(accent: Color, dark: Boolean): ColorScheme {
    val container = accent.copy(alpha = if (dark) 0.22f else 0.16f).compositeOver(surface)
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF17140C) else Color.White
    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = if (dark) accent else onSurface,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = container,
        onSecondaryContainer = if (dark) accent else onSurface,
        tertiary = accent,
        onTertiary = onAccent,
    )
}

// Neutrals sit a little lighter than a pure black so a card still reads as a card on a
// dark wallpaper, which is what "dark mode looks weird" usually means.
private val Ink = darkColorScheme(
    surface = Color(0xFF121210),
    onSurface = Color(0xFFE9E5DC),
    surfaceContainer = Color(0xFF1E1E1B),
    surfaceContainerHigh = Color(0xFF292924),
    surfaceContainerHighest = Color(0xFF34342E),
    onSurfaceVariant = Color(0xFFB5B1A6),
    outline = Color(0xFF4C4941),
    error = Color(0xFFFFB4A4),
    scrim = Color(0xFF000000),
)
private val Paper = lightColorScheme(
    surface = Color(0xFFFCFAF5),
    onSurface = Color(0xFF1B1A16),
    surfaceContainer = Color(0xFFF2EFE7),
    surfaceContainerHigh = Color(0xFFECE8DF),
    surfaceContainerHighest = Color(0xFFE5E1D7),
    onSurfaceVariant = Color(0xFF4A4740),
    outline = Color(0xFF7C7768),
    error = Color(0xFFA4372A),
    scrim = Color(0xFF000000),
)

/** One motion vocabulary, so nothing on screen moves at a speed nothing else uses. */
object Motion {
    val Snap = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    val Settle = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow,
    )
    val Fade = tween<Float>(durationMillis = 160)
    val Radius = 26.dp
    val Gap = 10.dp
}
