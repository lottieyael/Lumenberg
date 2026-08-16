package dev.lumenberg.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.lumenberg.core.AppRepository
import dev.lumenberg.core.LauncherApp
import dev.lumenberg.widgets.Panel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Home(
    repository: AppRepository,
    session: Session,
    host: AppWidgetHost,
    panels: List<Panel>,
    apps: List<LauncherApp>,
    enabled: Boolean,
    homeRoleHeld: Boolean,
    voice: String?,
    onVoiceUsed: () -> Unit,
    onStartVoice: () -> Unit,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onAddWidget: () -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onResizeWidget: (Int, Int) -> Unit,
    onMoveWidget: (Int, Int) -> Unit,
    onRequestHome: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var drawer by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val focus = rememberCommandFocus()

    val searching = query.isNotBlank() || drawer
    val results = remember(query, apps, drawer) {
        repository.ranked(query, apps, if (drawer && query.isBlank()) apps.size else 8)
    }

    LaunchedEffect(voice) {
        if (!voice.isNullOrBlank()) {
            query = voice
            onVoiceUsed()
        }
    }

    // Opening the drawer should put the cursor where the user is about to type.
    LaunchedEffect(drawer) {
        if (drawer) runCatching { focus.requestFocus() }
    }

    fun open(app: LauncherApp) {
        if (repository.launch(app)) {
            query = ""
            drawer = false
        }
    }

    fun ask(text: String = query) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val names = repository.ranked("", apps, 40).map { it.label }
        session.ask(trimmed, names) { wanted ->
            // Models paraphrase ("Google Chrome" for "Chrome"), so fall back to the same
            // ranking the search box uses rather than demanding an exact label.
            val target = apps.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
                ?: repository.ranked(wanted, apps, 1).firstOrNull()
            target != null && repository.launch(target)
        }
        query = ""
        drawer = false
    }

    fun submit() {
        val text = query.trim()
        if (text.isEmpty()) return
        // Typing an app's name and pressing Go launches it; anything else is a question.
        val exact = results.firstOrNull { it.label.equals(text, ignoreCase = true) }
        when {
            exact != null -> open(exact)
            session.ready -> ask(text)
            else -> onConnect()
        }
    }

    BackHandler(enabled = enabled && (searching || session.running || editing)) {
        when {
            editing -> editing = false
            searching -> { query = ""; drawer = false }
            else -> session.clear()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            Crown(
                editing = editing,
                onSettings = onSettings,
                onAddWidget = onAddWidget,
                onDoneEditing = { editing = false },
            )

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(Motion.Gap),
            ) {
                if (!homeRoleHeld) {
                    item {
                        Card {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Labelled(
                                    "Lumenberg is not your home screen",
                                    "Pressing Home still opens your old launcher.",
                                    Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                FilledTonalButton(onClick = onRequestHome) { Text("Choose") }
                            }
                        }
                    }
                }

                if (panels.isEmpty()) {
                    item {
                        Card(Modifier.combinedClickable(onClick = onAddWidget)) {
                            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilledIconButton(onClick = onAddWidget) {
                                    Icon(Icons.Rounded.Add, contentDescription = "Add a widget")
                                }
                                Spacer(Modifier.width(14.dp))
                                Labelled(
                                    "Put something worth glancing at here",
                                    "Calendar, weather, music, notes. Real widgets from the apps themselves.",
                                )
                            }
                        }
                    }
                } else {
                    items(panels, key = { it.id }) { panel ->
                        key(panel.id) {
                            WidgetPanel(
                                host = host,
                                panel = panel,
                                editing = editing,
                                onEdit = { editing = true },
                                onRemove = { onRemoveWidget(panel.id) },
                                onResize = { onResizeWidget(panel.id, it) },
                                onMove = { onMoveWidget(panel.id, it) },
                            )
                        }
                    }
                }
            }

        }

        // Above the wallpaper content, below the command surface, so tapping the dimmed
        // area actually dismisses instead of landing on an invisible Column.
        Scrim(visible = searching) { query = ""; drawer = false }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(Motion.Gap),
            ) {
                Panel(visible = session.running && !searching) {
                    Thread(session = session, onClear = session::clear)
                }
                Panel(visible = searching) {
                    Results(
                        repository = repository,
                        apps = results,
                        query = query,
                        aiReady = session.ready,
                        onLaunch = ::open,
                        onDetails = repository::openInfo,
                        onAsk = { ask() },
                    )
                }
                CommandBar(
                    query = query,
                    session = session,
                    onQuery = { query = it },
                    onSubmit = ::submit,
                    onVoice = onStartVoice,
                    onApps = { drawer = !drawer; query = "" },
                    onStop = session::stop,
                    focusRequester = focus,
                )
            }
        }
    }
}

/**
 * Follows the system's own minute tick, so the date is right after midnight without the
 * launcher waking up on a timer of its own.
 */
@Composable
private fun rememberClock(pattern: String): String {
    val context = LocalContext.current
    val format = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    var text by remember { mutableStateOf(format.format(Date())) }
    DisposableEffect(format) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                text = format.format(Date())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return text
}

/** Date and the two controls that are not the command bar. */
@Composable
private fun Crown(
    editing: Boolean,
    onSettings: () -> Unit,
    onAddWidget: () -> Unit,
    onDoneEditing: () -> Unit,
) {
    val today = rememberClock("EEEE d MMMM")
    // Everything Lumenberg draws sits on a card. Text straight on a wallpaper is a
    // contrast bug waiting for someone's photo, and no shadow reliably fixes it.
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)) {
        Row(
            Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    today,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (editing) {
                    Text(
                        "Arranging widgets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (editing) {
                TextButton(onClick = onDoneEditing) { Text("Done") }
            } else {
                IconButton(onClick = onAddWidget) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add a widget")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }
        }
    }
}

/**
 * Hosts one real Android widget. Long-press turns on the arrange controls, which is where
 * every launcher has put them for fifteen years.
 */
@Composable
private fun WidgetPanel(
    host: AppWidgetHost,
    panel: Panel,
    editing: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onResize: (Int) -> Unit,
    onMove: (Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val info = remember(panel.id) { manager.getAppWidgetInfo(panel.id) }
    val height by animateDpAsState(panel.height.dp, label = "widget")

    Card {
        Column {
            // A grab strip, because wrapping the widget itself in a click handler would
            // swallow every tap the widget's own app expects to receive.
            Grabber(editing = editing, onEdit = onEdit)

            Box(Modifier.fillMaxWidth()) {
                if (info == null) {
                    Column(Modifier.padding(18.dp)) {
                        Labelled("This widget's app is gone", "Remove it to tidy up.")
                        TextButton(onClick = onRemove) { Text("Remove") }
                    }
                } else {
                    // The size handshake is an IPC to system_server plus a broadcast to the
                    // widget's app, so it fires when the size settles, never per frame.
                    var told by remember(panel.id) { mutableStateOf(0 to 0) }
                    AndroidView(
                        factory = { host.createView(context, panel.id, info) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height),
                        update = { view ->
                            val widthDp = with(density) { view.width.toDp().value.toInt() }
                            if (widthDp > 0 && (widthDp to panel.height) != told) {
                                told = widthDp to panel.height
                                val size = SizeF(widthDp.toFloat(), panel.height.toFloat())
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    view.updateAppWidgetSize(Bundle(), listOf(size))
                                } else {
                                    // Bundle.EMPTY is immutable; updateAppWidgetSize writes into it.
                                    @Suppress("DEPRECATION")
                                    view.updateAppWidgetSize(
                                        Bundle(), widthDp, panel.height, widthDp, panel.height,
                                    )
                                }
                            }
                        },
                    )
                }

                Arrange(
                    editing = editing,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    onRemove = onRemove,
                    onMove = onMove,
                    onResize = {
                        onResize(Panel.STEPS.firstOrNull { it > panel.height } ?: Panel.STEPS.first())
                    },
                )
            }
        }
    }
}

/** The controls that appear over a widget once the user is arranging. */
@Composable
private fun Arrange(
    editing: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onResize: () -> Unit,
) {
    AnimatedVisibility(
        visible = editing,
        modifier = modifier,
        enter = fadeIn(Motion.Fade),
        exit = fadeOut(Motion.Fade),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(Icons.Rounded.KeyboardArrowUp, "Move up") { onMove(-1) }
            Chip(Icons.Rounded.KeyboardArrowDown, "Move down") { onMove(1) }
            Chip(Icons.Rounded.UnfoldMore, "Resize", onResize)
            Chip(Icons.Rounded.Close, "Remove", onRemove)
        }
    }
}

/** The only part of a widget card that belongs to Lumenberg rather than to the widget. */
@Composable
private fun Grabber(editing: Boolean, onEdit: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .combinedClickable(onClick = onEdit, onLongClick = onEdit, enabled = !editing),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 30.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun Chip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}
