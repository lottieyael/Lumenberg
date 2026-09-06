package dev.lumenberg.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.lumenberg.memory.Verbosity
import dev.lumenberg.memory.Warmth

@Composable
fun ProfileEditor(
    session: Session,
    onPickAvatar: () -> Unit,
    onExport: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
) {
    val profile = session.profile
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    val bitmap = remember(profile.hasAvatar, session.avatarFile.lastModified()) {
        if (profile.hasAvatar) BitmapFactory.decodeFile(session.avatarFile.absolutePath) else null
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Your agent", style = MaterialTheme.typography.titleMedium)

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Agent avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(CircleShape),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickAvatar) {
                    Text(if (profile.hasAvatar) "Change photo" else "Choose photo")
                }
                if (profile.hasAvatar) {
                    OutlinedButton(onClick = session::clearAvatar) { Text("Remove") }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Agent name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { session.updateProfile(profile.copy(name = name)) },
                enabled = name.trim().isNotEmpty() && name.trim() != profile.name,
            ) {
                Text("Save name")
            }

            Text("Reply length", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Verbosity.entries.forEach { value ->
                    FilterChip(
                        selected = profile.verbosity == value,
                        onClick = { session.updateProfile(profile.copy(verbosity = value)) },
                        label = {
                            Text(
                                when (value) {
                                    Verbosity.SHORT -> "Concise"
                                    Verbosity.BALANCED -> "Balanced"
                                    Verbosity.DETAILED -> "Detailed"
                                },
                            )
                        },
                    )
                }
            }

            Text("Tone", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Warmth.entries.forEach { value ->
                    FilterChip(
                        selected = profile.warmth == value,
                        onClick = { session.updateProfile(profile.copy(warmth = value)) },
                        label = {
                            Text(
                                when (value) {
                                    Warmth.COOL -> "Reserved"
                                    Warmth.NEUTRAL -> "Neutral"
                                    Warmth.WARM -> "Warm"
                                },
                            )
                        },
                    )
                }
            }

            if (onExport != null || onImport != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Move the agent without making a Lumenberg account. The backup includes identity, memory, and conversation history, but never API keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onExport?.let { OutlinedButton(onClick = it) { Text("Export") } }
                    onImport?.let { OutlinedButton(onClick = it) { Text("Import") } }
                }
            }
        }
    }
}
