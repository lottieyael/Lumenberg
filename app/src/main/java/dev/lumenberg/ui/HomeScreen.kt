package dev.lumenberg.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.lumenberg.ai.AiClient
import dev.lumenberg.ai.AiSettings
import dev.lumenberg.core.AppRepository
import dev.lumenberg.core.LauncherApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    appWidgetHost: AppWidgetHost,
    widgetIds: List<Int>,
    aiSettings: AiSettings,
    homeRoleHeld: Boolean,
    onboardingDone: Boolean,
    voiceText: String?,
    onVoiceConsumed: () -> Unit,
    onRequestHomeRole: () -> Unit,
    onAddWidget: () -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onStartVoice: () -> Unit,
    onSaveAiSettings: (AiSettings) -> Unit,
    onFinishOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { AppRepository(context) }
    val aiClient = remember { AiClient() }
    var apps by remember { mutableStateOf(emptyList<LauncherApp>()) }
    var query by remember { mutableStateOf("") }
    var showApps by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val conversation = remember { mutableStateListOf<AiClient.Message>() }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        apps = repository.loadApps()
    }

    LaunchedEffect(voiceText) {
        if (!voiceText.isNullOrBlank()) {
            query = voiceText
            onVoiceConsumed()
        }
    }

    val ranked = remember(query, apps) { repository.ranked(query, apps).take(8) }

    fun launch(app: LauncherApp) {
        repository.launch(app)
        apps = repository.loadApps()
        query = ""
        showApps = false
    }

    fun submit() {
        val text = query.trim()
        if (text.isEmpty()) return

        val exact = ranked.firstOrNull { it.label.equals(text, ignoreCase = true) }
        if (exact != null) {
            launch(exact)
            return
        }

        if (!aiSettings.configured) {
            showSettings = true
            return
        }

        val nextConversation = conversation.toList() + AiClient.Message("user", text)
        conversation += AiClient.Message("user", text)
        query = ""
        sending = true
        error = null
        aiClient.send(aiSettings, nextConversation) { result ->
            sending = false
            result.onSuccess { answer ->
                conversation += AiClient.Message("assistant", answer)
            }.onFailure { failure ->
                error = failure.message ?: "AI request failed"
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 126.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Header(
                    onSettings = { showSettings = true },
                    onAddWidget = onAddWidget,
                )
            }

            if (!homeRoleHeld) {
                item {
                    SurfaceCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Home, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Lumenberg is not your Home app", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Set it as default to make Home return here.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedButton(onClick = onRequestHomeRole) { Text("Set") }
                        }
                    }
                }
            }

            if (widgetIds.isEmpty()) {
                item {
                    SurfaceCard(onClick = onAddWidget) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledIconButton(onClick = onAddWidget) {
                                Icon(Icons.Rounded.Add, contentDescription = "Add widget")
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Build your glance surface", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Calendar, tasks, weather, music — actual Android widgets, not launcher clones.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            } else {
                items(widgetIds, key = { it }) { id ->
                    WidgetCard(
                        host = appWidgetHost,
                        appWidgetId = id,
                        onRemove = { onRemoveWidget(id) },
                    )
                }
            }

            if (conversation.isNotEmpty() || sending || error != null) {
                item {
                    Text(
                        "THREAD",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(conversation) { message ->
                    ConversationBubble(message)
                }
                if (sending) {
                    item {
                        SurfaceCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Thinking…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                error?.let { text ->
                    item {
                        SurfaceCard {
                            Text(text, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showApps || query.isNotBlank(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 92.dp),
        ) {
            AppResults(
                apps = if (query.isBlank()) repository.ranked("", apps).take(12) else ranked,
                query = query,
                onLaunch = ::launch,
            )
        }

        CommandBar(
            query = query,
            onQuery = { query = it },
            onSubmit = ::submit,
            onVoice = onStartVoice,
            onApps = { showApps = !showApps },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(18.dp),
        )
    }

    if (showSettings) {
        AiSettingsDialog(
            initial = aiSettings,
            onDismiss = { showSettings = false },
            onSave = {
                onSaveAiSettings(it)
                showSettings = false
            },
        )
    }

    if (!onboardingDone && !showSettings) {
        OnboardingDialog(
            homeRoleHeld = homeRoleHeld,
            aiConfigured = aiSettings.configured,
            onRequestHomeRole = onRequestHomeRole,
            onConfigureAi = { showSettings = true },
            onAddWidget = onAddWidget,
            onFinish = onFinishOnboarding,
        )
    }
}

@Composable
private fun Header(onSettings: () -> Unit, onAddWidget: () -> Unit) {
    val date = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Lumenberg", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(date, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onAddWidget) {
            Icon(Icons.Rounded.Add, contentDescription = "Add widget")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun SurfaceCard(
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(28.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun WidgetCard(
    host: AppWidgetHost,
    appWidgetId: Int,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val provider = remember(appWidgetId) { manager.getAppWidgetInfo(appWidgetId) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (provider == null) {
                Box(Modifier.padding(20.dp)) {
                    Text("Widget unavailable")
                }
            } else {
                AndroidView(
                    factory = {
                        host.createView(context, appWidgetId, provider).apply {
                            setAppWidget(appWidgetId, provider)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                )
            }
            FilledIconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(34.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove widget", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AppResults(
    apps: List<LauncherApp>,
    query: String,
    onLaunch: (LauncherApp) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
    ) {
        LazyColumn(
            modifier = Modifier.height(330.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            item {
                Text(
                    if (query.isBlank()) "Apps" else "Launch",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(apps, key = { it.component.flattenToString() }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onLaunch(app) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app)
                    Spacer(Modifier.width(12.dp))
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (apps.isEmpty() && query.isNotBlank()) {
                item {
                    Text(
                        "No app match — Enter sends this to your AI.",
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: LauncherApp) {
    val context = LocalContext.current
    val bitmap = remember(app.component) {
        runCatching {
            val drawable = context.packageManager.getActivityIcon(app.component)
            drawableToBitmap(drawable).asImageBitmap()
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(38.dp))
    } else {
        Surface(Modifier.size(38.dp), shape = CircleShape) {
            Box(contentAlignment = Alignment.Center) {
                Text(app.label.take(1).uppercase())
            }
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
    }
}

@Composable
private fun ConversationBubble(message: AiClient.Message) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.84f else 1f),
            shape = RoundedCornerShape(24.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            },
        ) {
            Text(message.content, Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun CommandBar(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoice: () -> Unit,
    onApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier.padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onApps) {
                Icon(Icons.Rounded.Apps, contentDescription = "Apps")
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask or launch…") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            )
            IconButton(onClick = onVoice) {
                Icon(Icons.Rounded.Mic, contentDescription = "Speak")
            }
            FilledIconButton(onClick = onSubmit) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun AiSettingsDialog(
    initial: AiSettings,
    onDismiss: () -> Unit,
    onSave: (AiSettings) -> Unit,
) {
    var endpoint by remember(initial) { mutableStateOf(initial.endpoint) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var key by remember(initial) { mutableStateOf(initial.apiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Lumenberg speaks the OpenAI-compatible chat/completions protocol. Use a hosted provider or a proxy you control.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint") },
                    placeholder = { Text("https://…/v1/chat/completions") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    placeholder = { Text("your-model") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "v0 stores this credential in private app preferences. Backups are disabled, but this is not hardened secret storage.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(AiSettings(endpoint, model, key)) }) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OnboardingDialog(
    homeRoleHeld: Boolean,
    aiConfigured: Boolean,
    onRequestHomeRole: () -> Unit,
    onConfigureAi: () -> Unit,
    onAddWidget: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val title = when (step) {
        0 -> "Your phone has three jobs"
        1 -> "Make Lumenberg home"
        2 -> "Put intelligence at the bottom"
        else -> "Make the screen useful at a glance"
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    0 -> {
                        Text("Ask. Glance. Launch.")
                        Text("No pages. No folders. No permanent icon grid. Your widgets stay visible; everything else comes through one command surface.")
                    }
                    1 -> {
                        Text(if (homeRoleHeld) "Lumenberg is already your Home app." else "Android needs your permission before Lumenberg can replace the current launcher.")
                        if (!homeRoleHeld) Button(onClick = onRequestHomeRole) { Text("Choose Lumenberg") }
                    }
                    2 -> {
                        Text(if (aiConfigured) "AI is connected." else "Connect any OpenAI-compatible endpoint. You can also skip this and use Lumenberg purely as a launcher.")
                        if (!aiConfigured) OutlinedButton(onClick = onConfigureAi) { Text("Configure AI") }
                    }
                    else -> {
                        Text("Add the widgets you actually glance at. Lumenberg hosts the real widget supplied by each app.")
                        OutlinedButton(onClick = onAddWidget) { Text("Add widget") }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (step < 3) step++ else onFinish()
            }) {
                Text(if (step < 3) "Next" else "Enter Lumenberg")
            }
        },
        dismissButton = if (step > 0) {
            { OutlinedButton(onClick = { step-- }) { Text("Back") } }
        } else null,
    )
}
