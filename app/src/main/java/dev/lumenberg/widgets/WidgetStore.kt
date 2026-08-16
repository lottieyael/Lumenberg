package dev.lumenberg.widgets

import android.content.Context
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** A hosted widget and the height in dp the user settled on. */
data class Panel(val id: Int, val height: Int = DEFAULT_HEIGHT) {
    companion object {
        const val DEFAULT_HEIGHT = 168
        val STEPS = listOf(112, 168, 248, 340)

        /**
         * `AppWidgetProviderInfo.minWidth/minHeight` are documented as dp but the framework
         * stores them in pixels. Converting is the difference between a tidy card and one
         * that eats half the screen on a 3x display.
         */
        fun fitHeight(minHeightPx: Int, metrics: DisplayMetrics): Int {
            if (minHeightPx <= 0) return DEFAULT_HEIGHT
            val dp = (minHeightPx / metrics.density).roundToInt()
            return STEPS.firstOrNull { it >= dp } ?: STEPS.last()
        }
    }
}

/** Ordered, because the user put them in that order. */
class WidgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun load(): List<Panel> = runCatching {
        val array = JSONArray(prefs.getString("panels", "[]"))
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                Panel(it.optInt("id", -1), it.optInt("h", Panel.DEFAULT_HEIGHT))
            }?.takeIf { it.id >= 0 }
        }
    }.getOrDefault(emptyList())

    fun add(id: Int, height: Int = Panel.DEFAULT_HEIGHT) {
        if (load().any { it.id == id }) return
        save(load() + Panel(id, height))
    }

    fun remove(id: Int) = save(load().filterNot { it.id == id })

    fun resize(id: Int, height: Int) =
        save(load().map { if (it.id == id) it.copy(height = height) else it })

    fun move(id: Int, by: Int) {
        val panels = load().toMutableList()
        val from = panels.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val to = (from + by).coerceIn(0, panels.lastIndex)
        panels.add(to, panels.removeAt(from))
        save(panels)
    }

    /** Drops anything the system no longer knows about, so dead cards cannot pile up. */
    fun retain(live: Set<Int>) {
        val kept = load().filter { it.id in live }
        if (kept.size != load().size) save(kept)
    }

    private fun save(panels: List<Panel>) {
        val array = JSONArray()
        panels.forEach { array.put(JSONObject().put("id", it.id).put("h", it.height)) }
        prefs.edit().putString("panels", array.toString()).apply()
    }
}
