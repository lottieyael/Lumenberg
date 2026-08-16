package dev.lumenberg.gestures

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager

/**
 * The user's gesture settings, and the two questions the UI needs to answer: has the
 * service been enabled, and does the user want it running.
 */
object Gestures {
    private const val PREFS = "gestures"
    const val KEY_ENABLED = "enabled"
    private const val KEY_EDGES = "edges"
    private const val KEY_BOTTOM = "bottom"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun edges(context: Context) = prefs(context).getBoolean(KEY_EDGES, true)
    fun bottom(context: Context) = prefs(context).getBoolean(KEY_BOTTOM, true)

    fun set(context: Context, enabled: Boolean = enabled(context), edges: Boolean = edges(context), bottom: Boolean = bottom(context)) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_EDGES, edges)
            .putBoolean(KEY_BOTTOM, bottom)
            .apply()
    }

    /**
     * Whether our service is actually running.
     *
     * Not by parsing `ENABLED_ACCESSIBILITY_SERVICES`, which is the usual recipe and reads
     * back null on some builds even while the service is bound. The service reports itself,
     * and AccessibilityManager is the cross-check.
     */
    fun serviceRunning(context: Context): Boolean {
        if (GestureService.live) return true
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
        }.getOrDefault(false)
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
