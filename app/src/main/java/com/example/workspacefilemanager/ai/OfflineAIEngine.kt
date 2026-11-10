package com.example.workspacefilemanager.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OfflineAIEngine
 *
 * Kotlin wrapper around the llama.cpp JNI bridge.
 *
 * All inference runs on [Dispatchers.Default]; the UI thread is never blocked.
 * Text is streamed back token by token through [generate], so the answer appears
 * while it is being produced instead of after the model has finished.
 *
 * Usage:
 *   OfflineAIEngine.loadModel(path)
 *   OfflineAIEngine.generate(system = "You are helpful.", user = "Hi") { chunk -> ... }
 *   OfflineAIEngine.cancelGeneration()          // stops the loop inside native code
 *   OfflineAIEngine.release()
 */
object OfflineAIEngine {

    private const val TAG = "OfflineAIEngine"

    /** Default cap on the answer length, in tokens. */
    const val DEFAULT_MAX_TOKENS = 320

    /** Receives incremental pieces of the answer. Called on a background thread. */
    fun interface AIStreamCallback {
        fun onToken(text: String)
    }

    /** Receives model-loading progress, 0..100. Called on a background thread. */
    fun interface AIProgressCallback {
        fun onProgress(percent: Int)
    }

    // ── JNI declarations ─────────────────────────────────────────────────
    external fun loadModel(path: String, callback: AIProgressCallback?): Boolean
    external fun generateStream(
        system: String,
        user: String,
        maxTokens: Int,
        callback: AIStreamCallback,
    ): String
    external fun cancelGeneration()
    external fun freeModel()
    external fun isModelLoaded(): Boolean
    external fun recommendedThreads(): Int

    // ── Library loading ───────────────────────────────────────────────────
    init {
        try {
            System.loadLibrary("workspaceai")   // libworkspaceai.so
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

    /** True when the .so is present and callable. */
    val available: Boolean = try {
        isModelLoaded()
        true
    } catch (_: Throwable) {
        false
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Load a GGUF model. Returns true on success.
     * Heavy: a 1 GB model can take several seconds even with mmap.
     */
    suspend fun load(path: String, onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.Default) {
        Log.i(TAG, "Loading model: $path")
        try {
            val ok = loadModel(path, AIProgressCallback { percent -> onProgress(percent) })
            if (ok) Log.i(TAG, "Model loaded") else Log.e(TAG, "loadModel returned false")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during loadModel: ${e.message}")
            false
        }
    }

    /**
     * Generate an answer for [user], streaming pieces to [onToken].
     *
     * @return the full answer, or a partial answer when [cancelGeneration] was called.
     */
    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        onToken: (String) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        if (!safeIsModelLoaded()) {
            return@withContext "[AI Error] No model loaded."
        }
        try {
            generateStream(system, user, maxTokens, AIStreamCallback { piece -> onToken(piece) })
        } catch (e: Throwable) {
            Log.e(TAG, "generateStream threw: ${e.message}")
            "[AI Error] ${e.message}"
        }
    }

    /** Stop the in-flight generation. Safe from any thread. */
    fun cancel() {
        try {
            cancelGeneration()
        } catch (e: Throwable) {
            Log.e(TAG, "cancelGeneration failed: ${e.message}")
        }
    }

    /** Free native memory. */
    suspend fun release() = withContext(Dispatchers.Default) {
        try { freeModel() } catch (_: Throwable) {}
        Log.i(TAG, "Native model released")
    }

    /** Cores the bridge will use for inference. */
    fun threads(): Int = try {
        recommendedThreads()
    } catch (_: Throwable) {
        4
    }

    private fun safeIsModelLoaded(): Boolean = try {
        isModelLoaded()
    } catch (_: Throwable) {
        false
    }
}
