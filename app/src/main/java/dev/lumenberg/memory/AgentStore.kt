package dev.lumenberg.memory

import dev.lumenberg.ai.Turn
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class Memory(
    val id: String,
    val text: String,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val sourcePrompt: String = "",
    val editedByUser: Boolean = false,
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
    fun searchMemories(query: String, limit: Int = 12): List<Memory> {
        val clean = query.trim()
        val source = loadMemories(Int.MAX_VALUE)
        return if (clean.isEmpty()) {
            source.take(limit)
        } else {
            source.filter { it.text.contains(clean, ignoreCase = true) }.take(limit)
        }
    }

    @Synchronized
    fun remember(text: String, pinned: Boolean = false, sourcePrompt: String = ""): Memory {
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
                sourcePrompt = sourcePrompt.ifBlank { memories[existing].sourcePrompt },
            ).also { memories[existing] = it }
        } else {
            Memory(UUID.randomUUID().toString(), clean, pinned, now, now, sourcePrompt).also(memories::add)
        }
        writeMemories(memories)
        return memory
    }

    @Synchronized
    fun edit(id: String, text: String) {
        require(text.isNotBlank()) { "Memory text is empty." }
        writeMemories(readMemories().map {
            if (it.id == id) it.copy(text = text.trim(), updatedAt = System.currentTimeMillis(), editedByUser = true) else it
        })
    }

    @Synchronized
    fun delete(id: String) = writeMemories(readMemories().filterNot { it.id == id })

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
                sourcePrompt = json.optString("sourcePrompt"),
                editedByUser = json.optBoolean("editedByUser", false),
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
                        .put("sourcePrompt", memory.sourcePrompt)
                        .put("editedByUser", memory.editedByUser)
                        .toString(),
                )
                writer.newLine()
            }
        }
        runCatching {
            Files.move(
                temp.toPath(),
                memoriesFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(temp.toPath(), memoriesFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            temp.delete()
            throw it
        }
    }

    private fun readLines(file: File): List<String> = if (file.exists()) {
        file.readLines().filter(String::isNotBlank)
    } else {
        emptyList()
    }
}
