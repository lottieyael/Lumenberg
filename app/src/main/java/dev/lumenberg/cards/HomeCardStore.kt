package dev.lumenberg.cards

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class HomeCard(val id: String, val prompt: String, val text: String, val updatedAt: Long)

class HomeCardStore(private val root: File) {
    private val file = File(root, "cards.json")

    @Synchronized
    fun load(): List<HomeCard> = runCatching {
        if (!file.exists()) emptyList() else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val json = array.getJSONObject(i)
                    HomeCard(json.getString("id"), json.getString("prompt"), json.getString("text"), json.getLong("updatedAt"))
                }.getOrNull()
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun pin(prompt: String, text: String): HomeCard {
        require(prompt.isNotBlank() && text.isNotBlank())
        val card = HomeCard(UUID.randomUUID().toString(), prompt.trim(), text.trim(), System.currentTimeMillis())
        save(load() + card)
        return card
    }

    @Synchronized
    fun update(id: String, text: String) {
        require(text.isNotBlank())
        save(load().map { if (it.id == id) it.copy(text = text.trim(), updatedAt = System.currentTimeMillis()) else it })
    }

    @Synchronized
    fun remove(id: String) = save(load().filterNot { it.id == id })

    private fun save(cards: List<HomeCard>) {
        root.mkdirs()
        val array = JSONArray()
        cards.forEach { array.put(JSONObject().put("id", it.id).put("prompt", it.prompt).put("text", it.text).put("updatedAt", it.updatedAt)) }
        val temp = File(root, "cards.json.tmp")
        temp.writeText(array.toString())
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
