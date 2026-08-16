package dev.lumenberg.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One offer in the widget catalogue, already resolved to things a person can read. */
data class Offer(
    val provider: AppWidgetProviderInfo,
    val app: String,
    val name: String,
    val preview: ImageBitmap?,
)

/**
 * Lumenberg lists widgets itself rather than handing users to `ACTION_APPWIDGET_PICK`.
 * The system picker is a bare alphabetical list that also crashes outright on some builds,
 * and it is the one screen where the choice should look like the thing being chosen.
 */
suspend fun catalog(context: Context): List<Offer> = withContext(Dispatchers.IO) {
    val manager = AppWidgetManager.getInstance(context)
    val packages = context.packageManager
    manager.getInstalledProvidersForProfile(Process.myUserHandle())
        .mapNotNull { info ->
            runCatching {
                val app = packages.getApplicationLabel(
                    packages.getApplicationInfo(info.provider.packageName, 0),
                ).toString()
                Offer(
                    provider = info,
                    app = app,
                    name = info.loadLabel(packages).takeIf { it.isNotBlank() && it != app } ?: app,
                    preview = preview(context, info),
                )
            }.getOrNull()
        }
        .sortedWith(compareBy({ it.app.lowercase() }, { it.name.lowercase() }))
}

private fun preview(context: Context, info: AppWidgetProviderInfo): ImageBitmap? = runCatching {
    val drawable = info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0) ?: return null
    val width = drawable.intrinsicWidth.coerceIn(1, 512)
    val height = drawable.intrinsicHeight.coerceIn(1, 512)
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        drawable.bounds = Rect(0, 0, width, height)
        drawable.draw(Canvas(it))
    }.asImageBitmap()
}.getOrNull()
