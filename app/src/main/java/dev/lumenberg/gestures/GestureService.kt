package dev.lumenberg.gestures

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Gesture navigation for systems that refuse to give it to third-party launchers,
 * Xiaomi's being the one people actually hit.
 *
 * This is an accessibility service for one reason: `performGlobalAction` is the only way
 * to press Back or open Recents without root. It declares that it does not retrieve window
 * content, so it cannot read what is on screen, and it listens for no events.
 *
 * The strips are `TYPE_ACCESSIBILITY_OVERLAY` windows, which a service may add without the
 * draw-over-other-apps permission, so enabling the service is the whole of the setup.
 */
class GestureService : AccessibilityService() {

    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val strips = mutableListOf<View>()
    private val watch = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> sync() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = true
        Gestures.prefs(this).registerOnSharedPreferenceChangeListener(watch)
        sync()
    }

    override fun onDestroy() {
        live = false
        Gestures.prefs(this).unregisterOnSharedPreferenceChangeListener(watch)
        clear()
        super.onDestroy()
    }

    companion object {
        /** Set while Android has this service bound. Same process as the launcher. */
        @Volatile
        var live: Boolean = false
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** Adds or removes strips so the screen matches the settings. */
    private fun sync() {
        clear()
        if (!Gestures.enabled(this)) return
        if (Gestures.edges(this)) {
            add(Edge.Left)
            add(Edge.Right)
        }
        if (Gestures.bottom(this)) add(Edge.Bottom)
    }

    private fun clear() {
        strips.forEach { runCatching { windows.removeView(it) } }
        strips.clear()
    }

    private fun add(edge: Edge) {
        val density = resources.displayMetrics.density
        val thickness = ((if (edge == Edge.Bottom) 18 else 14) * density).toInt()
        val params = WindowManager.LayoutParams(
            if (edge == Edge.Bottom) WindowManager.LayoutParams.MATCH_PARENT else thickness,
            if (edge == Edge.Bottom) thickness else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (edge) {
                Edge.Left -> Gravity.START or Gravity.CENTER_VERTICAL
                Edge.Right -> Gravity.END or Gravity.CENTER_VERTICAL
                Edge.Bottom -> Gravity.BOTTOM
            }
            // Edge strips cover the middle of the screen only, so a swipe from the very
            // top or bottom corner still reaches the app underneath.
            if (edge != Edge.Bottom) height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        }

        val view = View(this).apply {
            setOnTouchListener(Swipe(edge, (24 * density)))
        }
        runCatching { windows.addView(view, params) }
            .onSuccess { strips += view }
    }

    private enum class Edge { Left, Right, Bottom }

    /**
     * A swipe inward from a side is Back. A swipe up from the bottom is Home, unless it is
     * held there, which is Recents. Holding is how every gesture system distinguishes the
     * two, so it is what people already have in their fingers.
     */
    private inner class Swipe(private val edge: Edge, private val threshold: Float) :
        View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var crossedAt = 0L

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    crossedAt = 0L
                }

                MotionEvent.ACTION_MOVE -> {
                    if (crossedAt != 0L) return true
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val travelled = when (edge) {
                        Edge.Left -> dx > threshold && abs(dx) > abs(dy)
                        Edge.Right -> -dx > threshold && abs(dx) > abs(dy)
                        Edge.Bottom -> -dy > threshold
                    }
                    if (travelled) {
                        crossedAt = SystemClock.uptimeMillis()
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (edge != Edge.Bottom) perform(GLOBAL_ACTION_BACK)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (edge == Edge.Bottom && crossedAt != 0L) {
                        val held = SystemClock.uptimeMillis() - crossedAt
                        perform(if (held > 260) GLOBAL_ACTION_RECENTS else GLOBAL_ACTION_HOME)
                    }
                    crossedAt = 0L
                }

                MotionEvent.ACTION_CANCEL -> crossedAt = 0L
            }
            return true
        }
    }

    private fun perform(action: Int) {
        runCatching { performGlobalAction(action) }
    }
}
