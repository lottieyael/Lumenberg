package dev.lumenberg.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** One thing on screen the model is allowed to refer to. */
data class Element(
    val ref: Int,
    val label: String,
    val kind: String,
    val bounds: Rect,
    val editable: Boolean,
)

/**
 * A screen, reduced to the few dozen things that matter.
 *
 * The raw accessibility tree is thousands of nodes and most of it is layout. Sending that
 * verbatim would cost a fortune per step and bury the model. This keeps what a person
 * would look at: things you can press, things you can type into, and the text that tells
 * you where you are.
 */
class Screen(val app: String, val elements: List<Element>, val text: List<String>) {

    /** The compact form the model actually reads. */
    fun describe(): String = buildString {
        append("app: ").append(app).append('\n')
        if (text.isNotEmpty()) {
            append("text: ").append(text.joinToString(" | ").take(1200)).append('\n')
        }
        append("controls:\n")
        elements.forEach { element ->
            append("  [").append(element.ref).append("] ")
            append(element.kind)
            if (element.label.isNotBlank()) append(" \"").append(element.label).append('"')
            append('\n')
        }
    }

    companion object {
        private const val MAX_ELEMENTS = 60
        private const val MAX_TEXT = 40
        private const val MAX_DEPTH = 40

        fun of(root: AccessibilityNodeInfo?): Screen {
            if (root == null) return Screen("unknown", emptyList(), emptyList())
            val elements = mutableListOf<Element>()
            val text = mutableListOf<String>()
            var ref = 0

            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                // Only the control list is capped. Aborting the whole walk here meant a
                // screen with 60 buttons above the fold reported no text at all, which is
                // exactly where the answer usually is.
                if (node == null || depth > MAX_DEPTH) return
                if (!node.isVisibleToUser) return

                if (node.isPassword) {
                    for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
                    return
                }
                val label = listOfNotNull(
                    node.text?.toString()?.takeIf { it.isNotBlank() },
                    node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                ).firstOrNull().orEmpty().trim().take(80)

                val actionable = node.isClickable || node.isEditable || node.isCheckable ||
                    node.isLongClickable
                if (actionable) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.width() > 0 && bounds.height() > 0 && elements.size < MAX_ELEMENTS) {
                        elements += Element(
                            ref = ref++,
                            label = label.ifBlank { node.hintText?.toString().orEmpty().take(80) },
                            kind = when {
                                node.isEditable -> "field"
                                node.isCheckable -> "toggle"
                                else -> "button"
                            },
                            bounds = bounds,
                            editable = node.isEditable,
                        )
                    }
                } else if (label.isNotBlank() && text.size < MAX_TEXT && !node.isPassword) {
                    text += label
                }

                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }

            walk(root, 0)
            return Screen(root.packageName?.toString().orEmpty(), elements, text)
        }
    }
}
