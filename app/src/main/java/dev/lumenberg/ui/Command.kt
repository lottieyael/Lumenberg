package dev.lumenberg.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.lumenberg.ai.Turn
import dev.lumenberg.core.AppRepository
import dev.lumenberg.core.LauncherApp

/** The bar that is the whole interface: type to launch, press send to ask. */
@Composable
fun CommandBar(
    query: String,
    session: Session,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoice: () -> Unit,
    onApps: () -> Unit,
    onStop: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val sendable = query.isNotBlank()
    // Read here rather than in Home, so a streaming reply recomposes this bar and nothing else.
    val busy = session.busy
    val sendScale by animateFloatAsState(if (sendable || busy) 1f else 0.82f, Motion.Snap, label = "send")

    val glass = LocalSurfaceStyle.current == Surfaces.Glass
    val backdrop = rememberBackdrop(glass)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        color = if (glass) {
            androidx.compose.ui.graphics.Color.Transparent
        } else {
            scheme.surfaceContainerHigh.copy(alpha = LocalSurfaceAlpha.current)
        },
        contentColor = scheme.onSurface,
        shadowElevation = 10.dp,
    ) {
        Row(
            Modifier
                .then(
                    if (glass) {
                        Modifier.glass(
                            backdrop,
                            scheme.surfaceContainerHigh.copy(alpha = LocalSurfaceAlpha.current),
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onApps) {
                Icon(Icons.Rounded.GridView, contentDescription = "All apps")
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        "Ask or launch",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    ),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                )
            }
            Spacer(Modifier.width(4.dp))
            if (!sendable && !busy) {
                IconButton(onClick = onVoice) {
                    Icon(Icons.Rounded.Mic, contentDescription = "Speak")
                }
            }
            FilledIconButton(
                onClick = if (busy) onStop else onSubmit,
                enabled = busy || sendable,
                // Scaling in the graphics layer keeps the bar off the layout pass per frame.
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(44.dp)
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale },
            ) {
                Icon(
                    if (busy) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                    contentDescription = if (busy) "Stop" else "Send",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** App matches, shown the moment a key is pressed. Never waits on the network. */
@Composable
fun Results(
    repository: AppRepository,
    apps: List<LauncherApp>,
    query: String,
    aiReady: Boolean,
    onLaunch: (LauncherApp) -> Unit,
    onDetails: (LauncherApp) -> Unit,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier, tone = Tone.Solid) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
        ) {
            items(apps, key = { it.key }) { app ->
                AppRow(repository, app, onLaunch = { onLaunch(app) }, onDetails = { onDetails(app) })
            }
            if (apps.isEmpty() && query.isNotBlank()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("No app called \"$query\"", fontWeight = FontWeight.Medium)
                        Text(
                            if (aiReady) "Press send to ask instead." else "Connect an assistant in Settings to ask instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (aiReady) {
                            TextButton(onClick = onAsk, modifier = Modifier.padding(top = 4.dp)) {
                                Text("Ask about \"$query\"")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The running conversation. Streams in, scrolls itself, clears on demand. */
@Composable
fun Thread(
    session: Session,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val turns = session.turns
    val streaming = session.streaming
    val error = session.error
    val state = androidx.compose.foundation.lazy.rememberLazyListState()

    // Animating on every token would restart the animation on every token, so it never
    // arrives. Completed turns animate; a live stream is just pinned to the bottom.
    LaunchedEffect(turns.size, error, session.working) {
        state.animateScrollToItem(state.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    LaunchedEffect(streaming != null) {
        if (streaming != null) state.scrollToItem(state.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    Card(modifier, tone = Tone.Solid) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.account.provider.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (session.account.model.isNotBlank()) {
                        Text(
                            session.account.model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (session.accounts.size > 1) {
                    IconButton(onClick = session::cycle) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = "Use another assistant")
                    }
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear conversation")
                }
            }
            LazyColumn(
                state = state,
                modifier = Modifier.heightIn(max = 380.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(turns.size) { i -> Bubble(turns[i]) }
                streaming?.let { text ->
                    item {
                        if (text.isEmpty()) Working("Thinking") else Bubble(Turn("assistant", text))
                    }
                }
                session.working?.let { where ->
                    item { Working(where) }
                }
                error?.let { text ->
                    item {
                        Text(
                            text,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(turn: Turn) {
    val user = turn.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (user) 0.86f else 1f),
            shape = RoundedCornerShape(20.dp),
            color = if (user) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ) {
            Text(
                turn.text,
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Working(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Slides a panel up from the command bar. One transition for every overlay. */
@Composable
fun Panel(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(Motion.Settle) { it / 3 } + fadeIn(Motion.Fade),
        exit = slideOutVertically(Motion.Settle) { it / 3 } + fadeOut(Motion.Fade),
        content = { content() },
    )
}

/** Dims the wallpaper only while a panel is open, so the idle screen stays the user's photo. */
@Composable
fun Scrim(visible: Boolean, onDismiss: () -> Unit) {
    val alpha by animateFloatAsState(if (visible) 0.55f else 0f, Motion.Snap, label = "scrim")
    if (alpha > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = alpha))
                .then(if (visible) Modifier.clickable(onClick = onDismiss) else Modifier),
        )
    }
}

@Composable
fun rememberCommandFocus(): FocusRequester = remember { FocusRequester() }
