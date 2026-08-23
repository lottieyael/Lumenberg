package dev.lumenberg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lumenberg.device.DeviceAccessState
import dev.lumenberg.device.DeviceCapability

@Composable
fun DeviceAccessOnboarding(
    state: DeviceAccessState,
    onRequest: (DeviceCapability) -> Unit,
    onNext: () -> Unit,
) {
    Text(
        "Choose what your agent can understand from the phone.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Nothing here is required. Lumenberg checks these sources when building current context or when you ask for them.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    DeviceAccessPanel(state = state, onRequest = onRequest)
    Button(onClick = onNext) { Text("Next") }
}

@Composable
fun DeviceAccessPanel(
    state: DeviceAccessState,
    onRequest: (DeviceCapability) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Device access", style = MaterialTheme.typography.titleMedium)
            Text(
                "These let your agent understand the phone without opening apps. Each one is optional and can be changed later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Context used for an answer is sent to the model provider you connected. Lumenberg does not upload these sources anywhere else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccessRow(
                "Notifications",
                "Read active notifications and control media sessions.",
                DeviceCapability.NOTIFICATIONS,
                state,
                onRequest,
            )
            AccessRow(
                "Calendar",
                "See upcoming events. This does not grant calendar writes.",
                DeviceCapability.CALENDAR,
                state,
                onRequest,
            )
            AccessRow(
                "Contacts",
                "Find saved people and phone numbers when you ask.",
                DeviceCapability.CONTACTS,
                state,
                onRequest,
            )
            AccessRow(
                "App usage",
                "See which apps you use and for how long.",
                DeviceCapability.USAGE,
                state,
                onRequest,
            )
            AccessRow(
                "Location",
                "Use the phone's last known foreground location. No background location access.",
                DeviceCapability.LOCATION,
                state,
                onRequest,
            )
        }
    }
}

@Composable
private fun AccessRow(
    title: String,
    detail: String,
    capability: DeviceCapability,
    state: DeviceAccessState,
    onRequest: (DeviceCapability) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Labelled(title, detail, Modifier.weight(1f))
        OutlinedButton(
            onClick = { onRequest(capability) },
            enabled = !state.has(capability),
        ) {
            Text(if (state.has(capability)) "Allowed" else "Allow")
        }
    }
}
