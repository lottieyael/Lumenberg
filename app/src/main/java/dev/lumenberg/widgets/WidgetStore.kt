package dev.lumenberg.widgets

import android.content.Context

class WidgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun load(): List<Int> = prefs.getStringSet("ids", emptySet())
        .orEmpty()
        .mapNotNull(String::toIntOrNull)
        .sorted()

    fun add(id: Int) {
        val next = load().toMutableSet().apply { add(id) }
        save(next)
    }

    fun remove(id: Int) {
        val next = load().toMutableSet().apply { remove(id) }
        save(next)
    }

    private fun save(ids: Collection<Int>) {
        prefs.edit().putStringSet("ids", ids.map(Int::toString).toSet()).apply()
    }
}
