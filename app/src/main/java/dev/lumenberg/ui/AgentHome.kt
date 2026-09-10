package dev.lumenberg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lumenberg.cards.HomeCard
import dev.lumenberg.memory.Memory
import java.text.DateFormat
import java.util.Date

private fun timestamp(at: Long): String = if (at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)) else "Unknown date"

@Composable
fun PinnedAnswerCard(card: HomeCard, session: Session) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.prompt, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Text(card.text, maxLines = if (expanded) Int.MAX_VALUE else 8)
            Text("Updated ${timestamp(card.updatedAt)}", style = MaterialTheme.typography.labelSmall)
            session.cardErrors[card.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(enabled = card.id !in session.refreshingCards, onClick = { session.refreshCard(card) }) {
                    Text(if (card.id in session.refreshingCards) "Refreshing…" else "Refresh")
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "More") }
                TextButton(onClick = { session.removeCard(card.id) }) { Text("Unpin") }
            }
        }
    }
}

@Composable
fun MemoryManager(session: Session) {
    var showing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    TextButton(onClick = { showing = true }) { Text("Memory · ${session.memories.size} facts") }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text("What I remember") },
            text = {
                Column {
                    Text("Deleting a fact removes it from durable memory. Conversation history is kept separately.")
                    if (session.busy) Text("Finish or stop the current request before editing.")
                    LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (session.memories.isEmpty()) item { Text("No saved memories yet.") }
                        items(session.memories, key = { it.id }) { memory ->
                            Column {
                                Text(memory.text)
                                Text("Saved ${timestamp(memory.createdAt)}", style = MaterialTheme.typography.labelSmall)
                                TextButton(enabled = !session.busy, onClick = { editing = memory }) { Text("Why? / Edit") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showing = false }) { Text("Done") } },
        )
    }
    editing?.let { memory ->
        var text by remember(memory.id) { mutableStateOf(memory.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Why I remember this") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(if (memory.sourcePrompt.isBlank()) "This older memory has no recorded source." else "Saved by the assistant during your request:")
                    }
                    if (memory.sourcePrompt.isNotBlank()) item { Text(memory.sourcePrompt) }
                    if (memory.editedByUser) item { Text("You edited this fact on ${timestamp(memory.updatedAt)}.") }
                    item {
                        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Remembered fact") }, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        TextButton(enabled = !session.busy, onClick = { session.deleteMemory(memory.id); editing = null }) { Text("Delete this fact") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && !session.busy, onClick = { session.editMemory(memory.id, text); editing = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
