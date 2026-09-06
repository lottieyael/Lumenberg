package dev.lumenberg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lumenberg.widgets.Panel
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WidgetSizeDialog(
    panel: Panel,
    onDismiss: () -> Unit,
    onSave: (Panel) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var draft by remember(panel) { mutableStateOf(panel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Widget layout") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow {
                    TextButton(onClick = { draft = draft.copy(span = 2, square = true) }) { Text("Square") }
                    TextButton(onClick = { draft = draft.copy(span = 4, height = 168, square = false) }) { Text("Wide") }
                    TextButton(onClick = { draft = draft.copy(span = 2, height = 340, square = false) }) { Text("Tall") }
                }
                Text("Width: ${draft.span * 25}%")
                Slider(
                    value = draft.span.toFloat(),
                    onValueChange = { draft = draft.copy(span = it.roundToInt()) },
                    valueRange = 1f..4f,
                    steps = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.square, onCheckedChange = { draft = draft.copy(square = it) })
                    Text("Keep square")
                }
                if (!draft.square) {
                    Text("Height: ${draft.height} dp")
                    Slider(
                        value = draft.height.toFloat(),
                        onValueChange = { draft = draft.copy(height = it.roundToInt()) },
                        valueRange = 80f..640f,
                    )
                }
                Text("Smaller widgets share a row. Some apps only support certain widget sizes.")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { onMove(-1) }) { Text("Move up") }
                    TextButton(onClick = { onMove(1) }) { Text("Move down") }
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
