package dev.lumenberg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.lumenberg.ai.Account
import dev.lumenberg.ai.DeviceCode
import dev.lumenberg.ai.Provider
import dev.lumenberg.ai.SignIn
import kotlinx.coroutines.launch

@Composable
fun Sheet(
    title: String,
    onClose: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun Connect(
    session: Session,
    signingIn: Boolean,
    signInError: String?,
    onOAuth: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDone: () -> Unit,
) {
    var chosen by remember { mutableStateOf<Provider?>(null) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf<DeviceCode?>(null) }
    val scope = rememberCoroutineScope()

    val alreadyConnected = remember { session.ready }
    LaunchedEffect(session.ready) { if (session.ready && !alreadyConnected) onDone() }

    val provider = chosen
    if (provider == null) {
        if (signingIn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Finishing sign-in", style = MaterialTheme.typography.bodyMedium)
            }
        }
        signInError?.let {
            Card(tone = Tone.Solid) {
                Text(
                    it,
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            "Pick where your agent's model comes from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Provider.entries.forEach { entry ->
            Card(
                Modifier.clickable {
                    failure = null
                    secret = ""
                    device = null
                    when (entry.signIn) {
                        SignIn.OAUTH -> onOAuth()
                        else -> chosen = entry
                    }
                },
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.label, fontWeight = FontWeight.SemiBold)
                            if (entry.signIn == SignIn.OAUTH || entry.signIn == SignIn.DEVICE) {
                                Spacer(Modifier.width(8.dp))
                                Badge("no key needed")
                            }
                        }
                        Text(
                            entry.tagline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                }
            }
        }
        Text(
            "A ChatGPT Plus or Claude Pro plan will not work here. Those subscriptions do not grant third-party API access. OpenRouter is the simplest one-account option if you do not want to manage provider keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(provider.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        provider.tagline,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (provider.signIn == SignIn.DEVICE) {
        val code = device
        if (code == null) {
            Text(
                "GitHub will show you a box to type a short code into. Any device will do.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(tone = Tone.Accent) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Type this code on GitHub", style = MaterialTheme.typography.labelLarge)
                    Text(code.userCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(code.verificationUri, style = MaterialTheme.typography.bodySmall)
                }
            }
            AssistChip(
                onClick = { onOpenUrl(code.verificationUri) },
                label = { Text("Open GitHub") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, Modifier.size(16.dp))
                },
            )
        }

        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { chosen = null; device = null }, enabled = !busy) { Text("Back") }
            Button(
                onClick = {
                    busy = true
                    failure = null
                    scope.launch {
                        failure = session.signInWithGitHub { device = it }
                        busy = false
                        if (failure == null) onDone()
                    }
                },
                enabled = !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "Waiting for GitHub" else "Start sign-in")
            }
        }
        return
    }

    OutlinedTextField(
        value = secret,
        onValueChange = { secret = it; failure = null },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !busy,
        label = { Text(if (provider.signIn == SignIn.HOST) "Address of your machine" else "Key from ${provider.label}") },
        placeholder = { Text(if (provider.signIn == SignIn.HOST) "192.168.1.20" else "paste it here") },
        visualTransformation = if (provider.signIn == SignIn.HOST) {
            androidx.compose.ui.text.input.VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        shape = RoundedCornerShape(16.dp),
    )

    provider.keyUrl?.let { url ->
        AssistChip(
            onClick = { onOpenUrl(url) },
            label = { Text("Where do I get a key?") },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, Modifier.size(16.dp)) },
        )
    }

    failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { chosen = null }, enabled = !busy) { Text("Back") }
        Button(
            onClick = {
                busy = true
                failure = null
                scope.launch {
                    val candidate = if (provider.signIn == SignIn.HOST) {
                        Account(provider = provider, host = dev.lumenberg.ai.normalizeHost(secret))
                    } else {
                        Account(provider = provider, credential = secret.trim())
                    }
                    failure = session.connect(candidate)
                    busy = false
                    if (failure == null) onDone()
                }
            },
            enabled = !busy && secret.isNotBlank(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (busy) "Checking" else "Connect")
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun Settings(
    session: Session,
    homeRoleHeld: Boolean,
    dynamicColour: Boolean,
    onDynamicColour: (Boolean) -> Unit,
    onRequestHome: () -> Unit,
    onConnect: () -> Unit,
    onAddWidget: () -> Unit,
    onPickAvatar: () -> Unit,
    onExportAgent: () -> Unit,
    onImportAgent: () -> Unit,
) {
    LaunchedEffect(Unit) { session.loadModels() }

    if (!homeRoleHeld) {
        Card(tone = Tone.Accent) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Labelled(
                    "Lumenberg is not your home screen",
                    "Set it as default so Home comes back here.",
                    Modifier.weight(1f),
                )
                Button(onClick = onRequestHome) { Text("Set") }
            }
        }
    }

    ProfileEditor(
        session = session,
        onPickAvatar = onPickAvatar,
        onExport = onExportAgent,
        onImport = onImportAgent,
    )

    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (session.ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Labelled(session.account.provider.label, "Connected", Modifier.weight(1f))
                    TextButton(onClick = session::signOut) { Text("Sign out") }
                }
                Text("Model", style = MaterialTheme.typography.labelLarge)
                if (session.models.isEmpty()) {
                    Text(
                        session.account.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ModelPicker(session)
                }
            } else {
                Labelled("No model connected", "The launcher still works without one.")
                Button(onClick = onConnect) { Text("Connect a model") }
            }
        }
    }

    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Labelled("Wallpaper colours", "Match the palette to your wallpaper.", Modifier.weight(1f))
                Switch(checked = dynamicColour, onCheckedChange = onDynamicColour)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Labelled("Widgets", "Add anything that publishes an Android widget.", Modifier.weight(1f))
                OutlinedButton(onClick = onAddWidget) { Text("Add") }
            }
        }
    }

    Text(
        "Agent memory and conversation history stay on this phone unless you export them. API credentials are stored separately and are never included in an agent backup.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ModelPicker(session: Session) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(filter, session.models, session.account.model) {
        val matches = session.models.filter { it.contains(filter, ignoreCase = true) }
        (listOf(session.account.model) + matches).distinct().take(if (filter.isBlank()) 8 else 20)
    }

    if (session.models.size > 8) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search models") },
            shape = RoundedCornerShape(16.dp),
        )
    }
    Box(Modifier.heightIn(max = 260.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.filter { it.isNotBlank() }.forEach { model ->
                FilterChip(
                    selected = model == session.account.model,
                    onClick = { session.useModel(model) },
                    label = { Text(model, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
fun Onboarding(
    session: Session,
    step: Int,
    homeRoleHeld: Boolean,
    aiReady: Boolean,
    onRequestHome: () -> Unit,
    onConnect: () -> Unit,
    onPickAvatar: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    when (step) {
        0 -> {
            Text(
                "Lumenberg is a home screen built around one personal agent. Apps are still there when you want them, but you can increasingly ask for outcomes instead of operating the phone yourself.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Your agent, memory, and settings live on the phone and can be exported later. You do not need a Lumenberg account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onNext) { Text("Set up my agent") }
        }
        1 -> {
            Text(
                "Make it yours. You can change all of this later.",
                style = MaterialTheme.typography.bodyLarge,
            )
            ProfileEditor(session = session, onPickAvatar = onPickAvatar)
            Button(onClick = onNext) { Text("Next") }
        }
        2 -> {
            Text(
                if (homeRoleHeld) {
                    "Lumenberg is your home screen. Pressing Home brings you back to the same agent."
                } else {
                    "Android needs your permission before Lumenberg can become the home screen."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!homeRoleHeld) Button(onClick = onRequestHome) { Text("Choose Lumenberg") }
                OutlinedButton(onClick = onNext) { Text(if (homeRoleHeld) "Next" else "Later") }
            }
        }
        else -> {
            Text(
                if (aiReady) "Your model is connected." else "Pick the model that powers your agent.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Lumenberg is BYOK. OpenRouter, direct provider keys, Copilot, and local models stay available, and your agent data is separate from the provider you choose.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!aiReady) Button(onClick = onConnect) { Text("Connect") }
                Button(onClick = onSkip) { Text(if (aiReady) "Done" else "Skip for now") }
            }
        }
    }
}
