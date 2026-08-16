package dev.lumenberg.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What happened when the agent tried to press something. */
sealed interface TapResult {
    data object Done : TapResult

    /** The control is no longer there. */
    data object Gone : TapResult

    /** Something else is in its place now, so nothing was pressed. */
    data class Changed(val nowReads: String) : TapResult
}

/**
 * The hands and eyes. Reads the current screen and presses things on it.
 *
 * Separate from the gesture service on purpose: that one is declared unable to read the
 * screen and should stay that way. Driving apps genuinely needs to read the screen, so it
 * is a second service the user enables separately and can turn off on its own.
 */
class AgentService : AccessibilityService() {

    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var asking: android.view.View? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
    }

    override fun onDestroy() {
        dismissAsk()
        live = null
        super.onDestroy()
    }

    /** What is on screen right now. */
    fun screen(): Screen = Screen.of(rootInActiveWindow)

    /**
     * Presses a control, by finding it again first.
     *
     * Not by replaying the coordinates captured when the screen was read: a network round
     * trip and possibly a confirmation happened since, and anything that reflowed the
     * screen in that window would put a different button under those pixels. The node is
     * located again and its label checked, so a stale reference fails instead of pressing
     * whatever moved into place.
     */
    suspend fun tap(element: Element): TapResult {
        val node = locate(element) ?: return TapResult.Gone
        val label = labelOf(node)
        if (label.isNotBlank() && element.label.isNotBlank() && label != element.label) {
            return TapResult.Changed(label)
        }
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
            clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!clicked) return TapResult.Gone
        delay(SETTLE)
        return TapResult.Done
    }

    /** What the control looks like right now, for a confirmation the user can trust. */
    fun labelNow(element: Element): String? = locate(element)?.let(::labelOf)

    private fun locate(element: Element): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // Same place and same kind is the strong match; same label is the fallback for a
        // list that shifted by a few pixels.
        return find(root) { boundsOf(it) == element.bounds && it.isEditable == element.editable }
            ?: find(root) { labelOf(it) == element.label && element.label.isNotBlank() }
    }

    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node?.parent
        var hops = 0
        while (current != null && hops++ < 6) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun labelOf(node: AccessibilityNodeInfo): String = listOfNotNull(
        node.text?.toString()?.takeIf { it.isNotBlank() },
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
    ).firstOrNull().orEmpty().trim().take(80)

    /**
     * Types into a field. Uses the accessibility set-text action rather than synthesising
     * key presses, so it works whichever keyboard is installed.
     *
     * Exact bounds equality failed on a one-pixel settle or the keyboard resizing the
     * window, so the focused field and then any field are accepted in turn.
     */
    fun type(element: Element, value: String): Boolean {
        val node = locate(element)?.takeIf { it.isEditable }
            ?: find(rootInActiveWindow) { it.isEditable && it.isFocused }
            ?: find(rootInActiveWindow) { it.isEditable }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Presses the keyboard's go or search key, which setting text alone never does. */
    fun enter(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false
        val node = find(rootInActiveWindow) { it.isEditable && it.isFocused }
            ?: find(rootInActiveWindow) { it.isEditable }
            ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    suspend fun scroll(down: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val from = if (down) metrics.heightPixels * 0.7f else metrics.heightPixels * 0.35f
        val to = if (down) metrics.heightPixels * 0.35f else metrics.heightPixels * 0.7f
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        return gesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
                .build(),
        )
    }

    /**
     * Asks the user something over whatever app is in front.
     *
     * The launcher is in the background for the whole run, so a prompt drawn in its own
     * window is a prompt nobody sees. An accessibility overlay is the one surface that is
     * guaranteed to be on top of the app the agent is driving.
     */
    suspend fun confirm(question: String): Boolean = suspendCancellableCoroutine { continuation ->
        val view = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xF21B1B1B.toInt())
            setPadding(48, 40, 48, 40)
            addView(
                android.widget.TextView(context).apply {
                    text = question
                    setTextColor(0xFFF2F2F2.toInt())
                    textSize = 17f
                },
            )
            addView(
                android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, 32, 0, 0)
                    addView(button("Yes") { finish(continuation, true) })
                    addView(button("Stop") { finish(continuation, false) })
                },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = android.view.Gravity.BOTTOM }

        asking = view
        runCatching { windows.addView(view, params) }
            .onFailure { if (continuation.isActive) continuation.resume(false) }
        continuation.invokeOnCancellation { dismissAsk() }
    }

    private fun finish(
        continuation: kotlinx.coroutines.CancellableContinuation<Boolean>,
        allowed: Boolean,
    ) {
        dismissAsk()
        if (continuation.isActive) continuation.resume(allowed)
    }

    fun dismissAsk() {
        asking?.let { view -> runCatching { windows.removeView(view) } }
        asking = null
    }

    private fun button(label: String, onClick: () -> Unit) =
        android.widget.Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private suspend fun gesture(description: GestureDescription): Boolean {
        val ok = suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            if (!dispatchGesture(description, callback, null) && continuation.isActive) {
                continuation.resume(false)
            }
        }
        // Screens animate. Reading one mid-transition is how an agent loses its place.
        delay(SETTLE)
        return ok
    }

    private fun find(
        node: AccessibilityNodeInfo?,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            find(node.getChild(i), match)?.let { return it }
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo) =
        android.graphics.Rect().also(node::getBoundsInScreen)

    companion object {
        private const val SETTLE = 550L

        @Volatile
        var live: AgentService? = null
            private set

        val running: Boolean get() = live != null
    }
}
