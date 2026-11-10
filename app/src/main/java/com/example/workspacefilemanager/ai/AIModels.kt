package com.example.workspacefilemanager.ai

import android.content.Context
import java.io.File

/**
 * AIModels — curated catalog of mobile-friendly GGUF models.
 *
 * Every URL below was checked against Hugging Face: the file exists, the repo
 * is not gated, and the reported size is the real content-length. The previous
 * catalog used invented filenames (and gated repos such as microsoft/Phi-3 and
 * google/gemma), so six of eight downloads returned 404/401 — which is why
 * "download" appeared to do nothing.
 *
 * Ordered smallest-first: the small models are the ones that actually feel
 * usable on a phone CPU.
 */
object AIModels {

    private const val HF_RAW = "https://huggingface.co/"

    /** Sub-directory inside the app's external files dir. */
    const val MODELS_DIR = "models"

    /**
     * A downloadable model.
     *
     * @param sizeBytes exact file size, used for progress when the server does
     *                  not send Content-Length and for a free-space check.
     */
    data class Model(
        val name: String,
        val repoId: String,
        val filename: String,
        val sizeBytes: Long,
        val contextTokens: Int = 4096,
        val tags: List<String> = emptyList(),
    ) {
        val downloadUrl: String get() = "$HF_RAW$repoId/resolve/main/$filename"

        /** Human size, e.g. "271 MB" / "1.7 GB". */
        val sizeLabel: String get() = formatBytes(sizeBytes)

        /** Stable id used for download progress bookkeeping. */
        val id: String get() = "$repoId/$filename"
    }

    val available: List<Model> = listOf(
        Model(
            name = "SmolLM2 360M",
            repoId = "bartowski/SmolLM2-360M-Instruct-GGUF",
            filename = "SmolLM2-360M-Instruct-Q4_K_M.gguf",
            sizeBytes = 270_590_880L,
            contextTokens = 2048,
            tags = listOf("Tiny", "Fastest"),
        ),
        Model(
            name = "Qwen2.5 0.5B",
            repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491_400_032L,
            contextTokens = 4096,
            tags = listOf("Small", "Fast"),
        ),
        Model(
            name = "Llama 3.2 1B",
            repoId = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_464L,
            contextTokens = 4096,
            tags = listOf("Balanced"),
        ),
        Model(
            name = "Qwen2.5 1.5B",
            repoId = "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
            filename = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            sizeBytes = 986_048_768L,
            contextTokens = 4096,
            tags = listOf("Balanced", "Multilingual"),
        ),
        Model(
            name = "Gemma 2 2B",
            repoId = "bartowski/gemma-2-2b-it-GGUF",
            filename = "gemma-2-2b-it-Q4_K_M.gguf",
            sizeBytes = 1_708_582_752L,
            contextTokens = 4096,
            tags = listOf("Quality"),
        ),
        Model(
            name = "Phi-3 Mini 4K",
            repoId = "bartowski/Phi-3-mini-4k-instruct-gguf",
            filename = "Phi-3-mini-4k-instruct-Q4_K_M.gguf",
            sizeBytes = 2_393_231_360L,
            contextTokens = 4096,
            tags = listOf("Quality", "Large"),
        ),
    )

    /**
     * Where downloaded models live.
     *
     * `Context.getExternalFilesDir` is used instead of hand-building
     * /storage/emulated/0/Android/data/<pkg>/files: it resolves to the same
     * place but also works when the primary volume is not the default one, and
     * falls back to internal storage when external is unavailable.
     */
    fun modelsDir(context: Context): File {
        val dir = context.getExternalFilesDir(MODELS_DIR)
            ?: File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Absolute path of the models directory, creating it if needed. */
    fun getOrCreateModelsDir(context: Context): String = modelsDir(context).absolutePath

    fun resolveLocalFile(context: Context, model: Model): File =
        File(modelsDir(context), model.filename)

    fun formatBytes(bytes: Long): String = when {
        bytes < 1_024L -> "$bytes B"
        bytes < 1_048_576L -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1_073_741_824L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.1f GB".format(bytes / 1_073_741_824.0)
    }
}
