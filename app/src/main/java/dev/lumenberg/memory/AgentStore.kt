package dev.lumenberg.memory

import dev.lumenberg.ai.Turn
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Memory(
    val id: String,
    val text: String,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

class AgentStore(private val root: File) {
    private val turnsFile = File(root, "conversation.jsonl")
    private val memoriesFile = File(root, "memories.jsonl")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun loadTurns(limit: Int = 40): List<Turn> = readLines(turnsFile).mapNotNull { line ->
        runCatching {
            val json = JSONObject(line)
            Turn(json.getString("role"), json.getString("text"))
        }.getOrNull()
    }.takeLast(limit)

    @Synchronized
    fun appendTurn(turn: Turn) {
        root.mkdirs()
        val json = JSONObject()
            .put("role", turn.role)
            .put("text", turn.text)
            .put("at", System.currentTimeMillis())
        turnsFile.appendText(json.toString() + "\n")
    }

    @Synchronized
    fun clearTurns() {
        turnsFile.delete()
    }

    @Synchronized
    fun loadMemories(limit: Int = 32): List<Memory> = readMemories()
        .sortedWith(compareByDescending<Memory> { it.pinned }.thenByDescending { it.updatedAt })
        .take(limit)

    @Synchronized
    fun remember(text: String, pinned: Boolean = false): Memory {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Memory text is empty." }
        val now = System.currentTimeMillis()
        val memories = readMemories().toMutableList()
        val existing = memories.indexOfFirst { it.text.equals(clean, ignoreCase = true) }
        val memory = if (existing >= 0) {
            memories[existing].copy(
                text = clean,
                pinned = memories[existing].pinned || pinned,
                updatedAt = now,
            ).also { memories[existing] = it }
        } else {
            Memory(UUID.randomUUID().toString(), clean, pinned, now, now).also(memories::add)
        }
        writeMemories(memories)
        return memory
    }

    @Synchronized
    fun forget(query: String): Int {
        val clean = query.trim()
        if (clean.isEmpty()) return 0
        val before = readMemories()
        val after = before.filterNot { it.text.contains(clean, ignoreCase = true) }
        if (after.size != before.size) writeMemories(after)
        return before.size - after.size
    }

    @Synchronized
    fun memoryContext(limit: Int = 24): String = loadMemories(limit)
        .joinToString("\n") { "- ${it.text}" }

    private fun readMemories(): List<Memory> = readLines(memoriesFile).mapNotNull { line ->
        runCatching {
            val json = JSONObject(line)
            Memory(
                id = json.getString("id"),
                text = json.getString("text"),
                pinned = json.optBoolean("pinned", false),
                createdAt = json.optLong("createdAt", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
            )
        }.getOrNull()
    }

    private fun writeMemories(memories: List<Memory>) {
        root.mkdirs()
        val temp = File(root, "memories.jsonl.tmp")
        temp.bufferedWriter().use { writer ->
            memories.forEach { memory ->
                writer.append(
                    JSONObject()
                        .put("id", memory.id)
                        .put("text", memory.text)
                        .put("pinned", memory.pinned)
                        .put("createdAt", memory.createdAt)
                        .put("updatedAt", memory.updatedAt)
                        .toString(),
                )
                writer.newLine()
            }
        }
        if (memoriesFile.exists() && !memoriesFile.delete()) {
            temp.delete()
            error("Could not replace the memory file.")
        }
        if (!temp.renameTo(memoriesFile)) {
            error("Could not save the memory file.")
        }
    }

    private fun readLines(file: File): List<String> = if (file.exists()) {
        file.readLines().filter(String::isNotBlank)
    } else {
        emptyList()
    }
}
