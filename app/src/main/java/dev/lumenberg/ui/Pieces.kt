package dev.lumenberg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumenberg.core.AppRepository
import dev.lumenberg.core.LauncherApp

/** The only card shape in the app. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Raised,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val alpha = LocalSurfaceAlpha.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Motion.Radius),
        color = when (tone) {
            Tone.Raised -> scheme.surfaceContainerHigh.copy(alpha = alpha)
            // Panels that sit over other content stay opaque whatever the setting:
            // a translucent app list on top of a translucent card is unreadable.
            Tone.Solid -> scheme.surfaceContainerHigh
            Tone.Accent -> scheme.primaryContainer.copy(alpha = alpha)
        },
        // Surface infers its text colour from the exact scheme colour it was handed, and
        // copy(alpha) is no longer that colour, so it silently falls back to black. On a
        // dark card that is invisible text, which is what "dark mode looks weird" was.
        contentColor = if (tone == Tone.Accent) scheme.onPrimaryContainer else scheme.onSurface,
        content = content,
    )
}

enum class Tone { Raised, Solid, Accent }

@Composable
fun AppRow(
    repository: AppRepository,
    app: LauncherApp,
    onLaunch: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onLaunch, onLongClick = onDetails)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(repository, app)
        Spacer(Modifier.width(14.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Icons are decoded off the main thread and cached; the fallback is the first letter. */
@Composable
fun AppIcon(repository: AppRepository, app: LauncherApp, size: Int = 40) {
    val bitmap by produceState(repository.cachedIcon(app), app.key) {
        if (value == null) value = repository.icon(app)
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = null, modifier = Modifier.size(size.dp))
    } else {
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape((size / 3).dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Labelled(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
