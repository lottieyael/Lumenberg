package dev.lumenberg.memory

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object AgentBackup {
    const val VERSION = 1
    private const val MANIFEST = "manifest.json"
    private val FILES = listOf("profile.json", "memories.jsonl", "conversation.jsonl", "avatar", "cards.json")

    fun export(root: File, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(
                JSONObject()
                    .put("format", "lumenberg-agent")
                    .put("version", VERSION)
                    .toString()
                    .toByteArray(),
            )
            zip.closeEntry()

            FILES.forEach { name ->
                val file = File(root, name)
                if (!file.isFile) return@forEach
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun import(root: File, input: InputStream) {
        val parent = root.parentFile ?: error("Agent storage has no parent directory.")
        val temp = File(parent, "agent-import-${UUID.randomUUID()}")
        temp.mkdirs()
        var version: Int? = null

        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    when (entry.name) {
                        MANIFEST -> {
                            val manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                            require(manifest.optString("format") == "lumenberg-agent") {
                                "This is not a Lumenberg agent backup."
                            }
                            version = manifest.optInt("version", -1)
                        }
                        in FILES -> File(temp, entry.name).outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }

            require(version == VERSION) {
                "This backup uses an unsupported Lumenberg format."
            }

            root.mkdirs()
            FILES.forEach { name ->
                val incoming = File(temp, name)
                val target = File(root, name)
                if (!incoming.exists()) {
                    target.delete()
                    return@forEach
                }
                Files.move(
                    incoming.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }
}
