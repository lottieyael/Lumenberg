package dev.lumenberg.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The wallpaper, blurred once, so a card can show the part of it that sits behind it.
 *
 * There is no per-view backdrop blur on Android: `RenderEffect` blurs a view's own
 * content, and window blur covers the whole window. Sampling the wallpaper is what is
 * actually available, and blurring by downscaling costs one bilinear pass rather than a
 * gaussian on every frame.
 */
private object Wallpaper {
    private var cached: ImageBitmap? = null
    private var cachedFor: Int = 0

    suspend fun blurred(context: Context, width: Int): ImageBitmap? {
        cached?.takeIf { cachedFor == width }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val source = WallpaperManager.getInstance(context).fastDrawable ?: return@runCatching null
                val w = source.intrinsicWidth.takeIf { it > 0 } ?: width
                val h = source.intrinsicHeight.takeIf { it > 0 } ?: width
                // A quarter-percent-scale pass is the blur. Two hops keep it smooth.
                val tiny = Bitmap.createBitmap(
                    (w / 24).coerceAtLeast(2),
                    (h / 24).coerceAtLeast(2),
                    Bitmap.Config.ARGB_8888,
                )
                Canvas(tiny).also { canvas ->
                    source.setBounds(0, 0, canvas.width, canvas.height)
                    source.draw(canvas)
                }
                Bitmap.createScaledBitmap(tiny, (w / 6).coerceAtLeast(4), (h / 6).coerceAtLeast(4), true)
                    .asImageBitmap()
                    .also { cached = it; cachedFor = width }
            }.getOrNull()
        }
    }
}

/** Loads the blurred wallpaper for the current screen, or null when it cannot be read. */
@Composable
fun rememberBackdrop(enabled: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, enabled) {
        // Reading the wallpaper needs the home role on recent Android. Without it the
        // frosted tint below still applies, so glass degrades rather than disappears.
        value = if (enabled) Wallpaper.blurred(context, 1) else null
    }
    return image
}

/**
 * Draws [backdrop] behind this element, aligned to where the element actually sits on
 * screen, so the blur lines up with the wallpaper rather than repeating per card.
 */
fun Modifier.glass(backdrop: ImageBitmap?, tint: Color): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var window by remember { mutableStateOf(Offset.Zero) }

    this
        .onGloballyPositioned { coordinates ->
            origin = coordinates.positionInWindow()
            window = Offset(
                coordinates.findRootCoordinates().size.width.toFloat(),
                coordinates.findRootCoordinates().size.height.toFloat(),
            )
        }
        .drawBehind {
            if (backdrop == null || window.x <= 0f) {
                drawRect(tint)
                return@drawBehind
            }
            val scale = window.x / backdrop.width
            translate(-origin.x, -origin.y) {
                scale(scale, scale, pivot = Offset.Zero) {
                    drawImage(backdrop, filterQuality = FilterQuality.Low)
                }
            }
            drawRect(tint)
        }
}

private fun androidx.compose.ui.layout.LayoutCoordinates.findRootCoordinates() =
    generateSequence(this) { it.parentLayoutCoordinates }.last()
