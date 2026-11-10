package com.example.workspacefilemanager.ai

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AIModelDownloader — streams a GGUF file from Hugging Face to app storage.
 *
 * Reliability rules learned the hard way:
 *   • write to `<name>.part` and rename only after the bytes are verified, so a
 *     dropped connection can never leave a half file that looks "downloaded"
 *   • verify the GGUF magic number, so an HTML error page is never accepted
 *   • keep the OkHttp [Call] so Cancel actually stops the transfer instead of
 *     just hiding the progress bar
 *   • report the HTTP status in plain language (404 = wrong filename,
 *     401/403 = gated repo)
 */
class AIModelDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    companion object {
        private const val TAG = "AIModelDownloader"
        private const val BUFFER = 128 * 1024
        private const val PROGRESS_INTERVAL_MS = 200L
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    }

    private val active = ConcurrentHashMap<String, Call>()

    /**
     * Download [model]. Emits progress on [Dispatchers.IO].
     * Call [cancel] with the same model id to abort.
     */
    fun download(model: AIModels.Model, overwrite: Boolean = false): Flow<DownloadEvent> = flow {
        val targetFile = AIModels.resolveLocalFile(context, model)
        targetFile.parentFile?.mkdirs()

        if (!overwrite && targetFile.exists() && targetFile.length() > 0) {
            Log.i(TAG, "Already downloaded: ${targetFile.absolutePath}")
            emit(DownloadEvent.AlreadyExists(targetFile.absolutePath, targetFile.length()))
            return@flow
        }

        // ── Free space check ──────────────────────────────────────────────
        val expected = if (model.sizeBytes > 0) model.sizeBytes else 0L
        if (expected > 0) {
            val stat = StatFs(targetFile.parentFile?.absolutePath ?: context.filesDir.absolutePath)
            val free = stat.availableBytes
            if (free < expected + 32L * 1024 * 1024) {
                emit(
                    DownloadEvent.Error(
                        "Not enough free space: need ${AIModels.formatBytes(expected)}, " +
                            "${AIModels.formatBytes(free)} available."
                    )
                )
                return@flow
            }
        }

        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        if (partFile.exists()) partFile.delete()

        emit(DownloadEvent.Started(model.name, model.downloadUrl, model.sizeLabel))

        val request = Request.Builder()
            .url(model.downloadUrl)
            .header("Accept", "application/octet-stream")
            .get()
            .build()

        val call = client.newCall(request)
        active[model.id] = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadEvent.Error(httpMessage(response.code, response.message)))
                    return@flow
                }

                val body = response.body
                if (body == null) {
                    emit(DownloadEvent.Error("Server returned an empty body."))
                    return@flow
                }

                val declared = body.contentLength()
                val total = if (declared > 0) declared else expected

                var downloaded = 0L
                var lastEmit = 0L

                partFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS || downloaded == total) {
                                lastEmit = now
                                emit(DownloadEvent.Progress(downloaded, total))
                            }
                        }
                    }
                }

                if (downloaded <= 0) {
                    partFile.delete()
                    emit(DownloadEvent.Error("Downloaded 0 bytes — the server sent nothing."))
                    return@flow
                }

                if (total > 0 && downloaded != total) {
                    partFile.delete()
                    emit(
                        DownloadEvent.Error(
                            "Incomplete download: got ${AIModels.formatBytes(downloaded)} " +
                                "of ${AIModels.formatBytes(total)}. Try again."
                        )
                    )
                    return@flow
                }

                // ── Verify it is really a GGUF model ──────────────────────
                if (!hasGgufHeader(partFile)) {
                    val head = readHead(partFile, 120)
                    partFile.delete()
                    emit(
                        DownloadEvent.Error(
                            "That file is not a GGUF model (server said: ${head.ifBlank { "empty" }})."
                        )
                    )
                    return@flow
                }

                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.delete()
                    emit(DownloadEvent.Error("Could not move the finished file into place."))
                    return@flow
                }

                Log.i(TAG, "Download complete: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                emit(DownloadEvent.Complete(targetFile.absolutePath, targetFile.length()))
            }
        } catch (e: IOException) {
            partFile.delete()
            val msg = if (call.isCanceled()) "Download cancelled." else (e.message ?: "Network error")
            emit(DownloadEvent.Error(msg))
        } catch (e: Exception) {
            partFile.delete()
            emit(DownloadEvent.Error(e.message ?: "Download failed"))
        } finally {
            active.remove(model.id)
        }
    }.flowOn(Dispatchers.IO)

    /** Abort an in-flight download. */
    fun cancel(modelId: String) {
        active[modelId]?.cancel()
        active.remove(modelId)
    }

    fun isDownloaded(model: AIModels.Model): Boolean {
        val f = AIModels.resolveLocalFile(context, model)
        return f.exists() && f.length() > 0
    }

    fun localPathIfExists(model: AIModels.Model): String? {
        val f = AIModels.resolveLocalFile(context, model)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    private fun httpMessage(code: Int, message: String): String = when (code) {
        401, 403 -> "Access denied (HTTP $code). This model needs a Hugging Face login."
        404 -> "File not found on the server (HTTP 404)."
        in 500..599 -> "Hugging Face is having trouble (HTTP $code). Try again later."
        else -> "Download failed: HTTP $code $message"
    }

    private fun hasGgufHeader(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = input.read(head, read, 4 - read)
                    if (n <= 0) return false
                    read += n
                }
                head.contentEquals(GGUF_MAGIC)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readHead(file: File, max: Int): String = try {
        file.inputStream().use { it.readNBytes(max) }
            .toString(Charsets.UTF_8)
            .replace(Regex("\\s+"), " ")
            .trim()
    } catch (_: Exception) {
        ""
    }
}

// ── Event types ─────────────────────────────────────────────────────────────

sealed class DownloadEvent {
    data class Started(val modelName: String, val url: String, val sizeLabel: String) : DownloadEvent()

    /** [totalBytes] is 0 when unknown. */
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadEvent() {
        val percent: Int
            get() = if (totalBytes <= 0) -1
            else ((bytesDownloaded * 100) / totalBytes).coerceIn(0L, 100L).toInt()

        val readableProgress: String get() = AIModels.formatBytes(bytesDownloaded)
        val readableTotal: String get() = if (totalBytes <= 0) "?" else AIModels.formatBytes(totalBytes)
    }

    data class Complete(val path: String, val sizeBytes: Long) : DownloadEvent()
    data class Error(val message: String) : DownloadEvent()
    data class AlreadyExists(val path: String, val sizeBytes: Long) : DownloadEvent()
}
