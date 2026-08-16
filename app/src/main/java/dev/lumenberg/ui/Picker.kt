package dev.lumenberg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumenberg.widgets.Offer
import dev.lumenberg.widgets.catalog

/** Lumenberg's own widget list: what each one looks like, grouped by the app it comes from. */
@Composable
fun Picker(onChoose: (Offer) -> Unit) {
    val context = LocalContext.current
    val offers by produceState<List<Offer>?>(null) { value = catalog(context) }
    var filter by remember { mutableStateOf("") }

    val all = offers
    if (all == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Looking for widgets", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    if (all.isEmpty()) {
        Text(
            "None of your apps publish a widget yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (all.size > 8) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search") },
            shape = RoundedCornerShape(16.dp),
        )
    }

    val shown = remember(filter, all) {
        if (filter.isBlank()) all else all.filter {
            it.app.contains(filter, true) || it.name.contains(filter, true)
        }
    }

    shown.forEach { offer ->
        Card(Modifier.clickable { onChoose(offer) }) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    offer.preview?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Fit)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        offer.name,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        offer.app,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    Spacer(Modifier.heightIn(min = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Long-press a widget on the home screen to resize, reorder or remove it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
