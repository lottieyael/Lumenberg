package dev.lumenberg.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.lumenberg.ai.Account
import dev.lumenberg.ai.DeviceCode
import dev.lumenberg.ai.Provider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lumenberg.ai.SignIn
import dev.lumenberg.gestures.Gestures
import kotlinx.coroutines.launch

/** Full-screen sheet used for both first run and Settings. One layout, two entry points. */
@Composable
fun Sheet(
    title: String,
    onClose: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
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

/**
 * Connecting an assistant. OpenRouter is first because it is the only one that signs a
 * person in without ever showing them an API key.
 */
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

    // Signing in via the browser lands back here with the account already filled in.
    // Only a *change* closes the sheet, so opening it while connected still lets you switch.
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
            "Pick where your assistant comes from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Provider.entries.forEach { entry ->
            Card(
                Modifier.clickable {
                    failure = null
                    secret = ""
                    device = null
                    chosen = entry
                },
            ) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
            "A ChatGPT Plus or Claude Pro plan will not work here. Those sign-ins exist, but " +
                "they only cover each company's own apps: Anthropic's terms now forbid using " +
                "them anywhere else, and OpenAI's works only inside Codex. OpenRouter is the " +
                "closest honest equivalent, and it bills one account across every model.",
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

    if (provider.signIn == SignIn.OAUTH) {
        Text(
            "Signing in through the browser means you never handle a key. If the browser " +
                "does not bring you back, paste a key instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOAuth, enabled = !busy) { Text("Sign in with browser") }
    }

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
                    Text(
                        code.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
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

        failure?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

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
        label = {
            Text(
                when (provider.signIn) {
                    SignIn.HOST -> "Address of your machine"
                    SignIn.OAUTH -> "Or paste a key from ${provider.label}"
                    else -> "Key from ${provider.label}"
                },
            )
        },
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

    failure?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

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
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
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
    look: Look,
    onLook: (Look) -> Unit,
    onRequestHome: () -> Unit,
    onConnect: () -> Unit,
    onAddWidget: () -> Unit,
    onOpenAccessibility: () -> Unit,
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

    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (session.accounts.isEmpty()) {
                Labelled("No assistant connected", "Lumenberg still launches apps without one.")
                Button(onClick = onConnect) { Text("Connect an assistant") }
            } else {
                Text(
                    "Assistants",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (session.accounts.size > 1) {
                    Text(
                        "Tap one to make it the assistant that answers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                session.accounts.forEachIndexed { index, account ->
                    val current = index == session.active
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { session.use(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (current) Icons.Rounded.Check else Icons.Rounded.Circle,
                            contentDescription = if (current) "Answering" else "Not in use",
                            tint = if (current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(if (current) 20.dp else 10.dp),
                        )
                        Spacer(Modifier.width(if (current) 10.dp else 20.dp))
                        Labelled(
                            account.provider.label,
                            account.model.ifBlank { "No model chosen" },
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = { session.disconnect(index) }) { Text("Remove") }
                    }
                }
                OutlinedButton(onClick = onConnect) { Text("Connect another") }

                if (session.ready) {
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
                }
            }
        }
    }

    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            Choice(
                label = "Theme",
                options = Appearance.entries,
                selected = look.appearance,
                name = { it.label },
                onSelect = { onLook(look.copy(appearance = it)) },
            )
            Choice(
                label = "Colour",
                options = Accent.entries.filter { it.available },
                selected = look.accent,
                name = { it.label },
                swatch = { if (it == Accent.Wallpaper) null else it.onDark },
                onSelect = { onLook(look.copy(accent = it)) },
            )
            Choice(
                label = "Top bar",
                options = Header.entries,
                selected = look.header,
                name = { it.label },
                onSelect = { onLook(look.copy(header = it)) },
            )
            Choice(
                label = "Card background",
                options = Surfaces.entries,
                selected = look.surface,
                name = { it.label },
                onSelect = { onLook(look.copy(surface = it)) },
            )
        }
    }

    Card {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Labelled("Widgets", "Add anything that publishes an Android widget.", Modifier.weight(1f))
            OutlinedButton(onClick = onAddWidget) { Text("Add") }
        }
    }

    GestureSettings(onOpenAccessibility)

    Text(
        "App use is counted on this device only, to order search results. Nothing is uploaded. " +
            "Your assistant sees the text you type and the names of your installed apps.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Swipe navigation. The permission is a real one, so this says what the service can and
 * cannot do rather than hiding it behind a switch.
 */
@Composable
private fun GestureSettings(onOpenAccessibility: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var running by remember { mutableStateOf(Gestures.serviceRunning(context)) }
    var on by remember { mutableStateOf(Gestures.enabled(context)) }
    var edges by remember { mutableStateOf(Gestures.edges(context)) }
    var bottom by remember { mutableStateOf(Gestures.bottom(context)) }

    // The user leaves to Accessibility settings and comes back; re-read on the way in.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) running = Gestures.serviceRunning(context)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Labelled(
                    "Swipe navigation",
                    "For phones that force buttons on third-party launchers.",
                    Modifier.weight(1f),
                )
                Switch(
                    checked = on && running,
                    enabled = running,
                    onCheckedChange = {
                        on = it
                        Gestures.set(context, enabled = it)
                    },
                )
            }

            if (!running) {
                Text(
                    "Swipe in from either side for Back, up from the bottom for Home, up and " +
                        "hold for Recents. Android only allows this through an accessibility " +
                        "service, so Lumenberg has one. It is declared unable to read your " +
                        "screen and is used for nothing else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenAccessibility) { Text("Turn on in Accessibility") }
            } else if (on) {
                Choice(
                    label = "Where",
                    options = listOf(true to "Sides", false to "Bottom"),
                    selected = null,
                    name = { it.second },
                    onSelect = { },
                    checked = { if (it.first) edges else bottom },
                    onToggle = { option ->
                        if (option.first) {
                            edges = !edges
                            Gestures.set(context, edges = edges)
                        } else {
                            bottom = !bottom
                            Gestures.set(context, bottom = bottom)
                        }
                    },
                )
                Text(
                    "Xiaomi hides the button bar only if you grant Lumenberg one permission " +
                        "over ADB. Without it the buttons stay and these gestures sit alongside them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One row of the appearance settings. */
@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T?,
    name: (T) -> String,
    swatch: ((T) -> Color?)? = null,
    onSelect: (T) -> Unit,
    checked: ((T) -> Boolean)? = null,
    onToggle: ((T) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = checked?.invoke(option) ?: (option == selected),
                    onClick = { onToggle?.invoke(option) ?: onSelect(option) },
                    label = { Text(name(option)) },
                    leadingIcon = swatch?.invoke(option)?.let { colour ->
                        {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(colour),
                            )
                        }
                    },
                )
            }
        }
    }
}

/** Long model lists are unusable as a wall of chips, so this shows the top ones and a filter. */
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

/** First run: three screens, each one thing. */
@Composable
fun Onboarding(
    step: Int,
    homeRoleHeld: Boolean,
    aiReady: Boolean,
    onRequestHome: () -> Unit,
    onConnect: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    when (step) {
        0 -> {
            Text(
                "One screen. Your wallpaper, the widgets you actually read, and a bar that " +
                    "launches apps or answers questions.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "No pages to swipe. No folders to maintain. No grid of icons you stopped seeing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onNext) { Text("Start") }
        }
        1 -> {
            Text(
                if (homeRoleHeld) {
                    "Lumenberg is your home screen. Pressing Home brings you back here."
                } else {
                    "Android needs your say-so before Lumenberg can replace your current launcher."
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
                if (aiReady) "Your assistant is connected." else "Connect an assistant, or don't.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Lumenberg is a complete launcher without one. With one, the same bar answers " +
                    "questions and opens apps you describe instead of name.",
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
