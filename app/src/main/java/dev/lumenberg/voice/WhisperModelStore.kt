package dev.lumenberg.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class WhisperModelStore(context: Context) {
    private val root = File(context.filesDir, "voice")
    val model = File(root, MODEL_NAME)
    private val marker = File(root, "$MODEL_NAME.sha256")

    val ready: Boolean
        get() = model.isFile && model.length() == MODEL_BYTES && marker.readTextOrNull() == MODEL_SHA256

    suspend fun download(onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        root.mkdirs()
        if (ready) {
            onProgress(1f)
            return@withContext
        }

        val temp = File(root, "$MODEL_NAME.part")
        temp.delete()
        marker.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Lumenberg")
        }

        try {
            val status = connection.responseCode
            require(status in 200..299) { "Speech model download failed ($status)." }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        onProgress((written.toDouble() / MODEL_BYTES).coerceIn(0.0, 1.0).toFloat())
                    }
                }
            }

            require(written == MODEL_BYTES) {
                "Speech model download was incomplete."
            }
            require(digest.digest().hex() == MODEL_SHA256) {
                "Speech model did not pass its integrity check."
            }

            Files.move(
                temp.toPath(),
                model.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            marker.writeText(MODEL_SHA256)
            onProgress(1f)
        } finally {
            connection.disconnect()
            if (!ready) temp.delete()
        }
    }

    fun remove() {
        model.delete()
        marker.delete()
    }

    companion object {
        const val MODEL_NAME = "ggml-base.bin"
        const val MODEL_BYTES = 147_951_465L
        const val MODEL_SHA256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
        const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin?download=true"
    }
}

internal fun ByteArray.hex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()
