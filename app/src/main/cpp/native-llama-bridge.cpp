/**
 * native-llama-bridge.cpp
 *
 * JNI bridge between Kotlin OfflineAIEngine and llama.cpp.
 *
 * Fixes over the previous version:
 *   • thread count derives from the device instead of a hard-coded 2
 *   • KV cache is cleared before every request (it used to grow forever,
 *     making the 3rd answer dramatically slower than the 1st)
 *   • prompt is decoded in n_batch-sized chunks (a single llama_decode with
 *     more than n_batch tokens fails, which silently killed long prompts)
 *   • tokens are streamed to Kotlin as they are sampled, so the UI can show
 *     text immediately instead of waiting for the whole answer
 *   • generation is cancellable from Kotlin
 *   • the model's own chat template is applied (Phi-3 tokens on a Qwen model
 *     produced garbage and repeated text)
 *   • tokeniser buffer is sized by asking llama.cpp instead of guessing
 */

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <cstring>
#include <cmath>
#include <mutex>
#include <thread>
#include <vector>

#include "llama.h"

#define TAG          "WorkspaceAI"
#define LOGI(...)    __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...)    __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...)    __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// ── Mobile-safe limits ────────────────────────────────────────────────────
static constexpr int  MAX_CONTEXT_TOKENS = 4096;
static constexpr int  MAX_NEW_TOKENS     = 512;
static constexpr int  BATCH_SIZE         = 512;
static constexpr int  JNI_FLUSH_TOKENS   = 1;    // emit every token for live text

// ── Global state ──────────────────────────────────────────────────────────
// g_gen_mutex serialises generation and also guards load/free so the model
// can never be freed mid-decode. Cancel deliberately does NOT take this lock.
static std::mutex          g_gen_mutex;
static std::mutex          g_state_mutex;
static struct llama_model*   g_model = nullptr;
static struct llama_context* g_ctx   = nullptr;
static std::string           g_loaded_path;
static std::atomic<bool>     g_cancel{false};
static std::atomic<bool>     g_backend_ready{false};

static int device_threads() {
    unsigned cores = std::thread::hardware_concurrency();
    if (cores == 0) cores = 4;
    // Leave headroom for the UI thread and the OS; phones throttle hard at 100%.
    int t = (int)cores - 2;
    if (t < 2) t = 2;
    if (t > 6) t = 6;
    return t;
}

/**
 * True when [s] ends on a complete UTF-8 character.
 *
 * Token pieces can split a multi-byte character in half; passing that to
 * NewStringUTF would replace or drop those bytes, so we hold the fragment until
 * the next token completes it.
 */
static bool utf8_complete(const std::string& s) {
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = (unsigned char)s[i];
        size_t len = 1;
        if (c >= 0xF0)      len = 4;
        else if (c >= 0xE0) len = 3;
        else if (c >= 0xC0) len = 2;
        if (i + len > s.size()) return false;
        i += len;
    }
    return true;
}

// ── Load progress ─────────────────────────────────────────────────────────
struct LoadProgress {
    JNIEnv*   env;
    jobject   callback;
    jmethodID method;
    int       last_percent;
};

static bool load_progress_cb(float progress, void* user_data) {
    auto* p = static_cast<LoadProgress*>(user_data);
    if (p->callback && p->method) {
        int pct = (int)(progress * 100.0f);
        if (pct != p->last_percent) {
            p->last_percent = pct;
            p->env->CallVoidMethod(p->callback, p->method, pct);
            if (p->env->ExceptionCheck()) p->env->ExceptionClear();
        }
    }
    return !g_cancel.load();   // cancelling the load returns false → abort
}

// ── Helpers ───────────────────────────────────────────────────────────────
static std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(js, chars);
    return s;
}

static jstring std_to_jstring(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

/**
 * Decode [count] tokens taken from index 0 of [tokens], placing them at
 * positions pos_base..pos_base+count-1.
 *
 * token index and position MUST be kept separate: when generating, the buffer
 * holds a single token that belongs at position N, so reusing the position as
 * an index read past the end of the vector (garbage token id → decode -1 →
 * generation stopped after one token).
 */
static bool decode_chunked(struct llama_context* ctx,
                           const std::vector<llama_token>& tokens,
                           int pos_base, int count) {
    for (int off = 0; off < count; off += BATCH_SIZE) {
        const int n = std::min(BATCH_SIZE, count - off);
        llama_batch batch = llama_batch_init(n, 0, 1);
        for (int j = 0; j < n; ++j) {
            const int idx = off + j;
            batch.token[j]    = tokens[idx];
            batch.pos[j]      = pos_base + idx;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            // Only the final token needs logits.
            batch.logits[j]   = (idx == count - 1) ? 1 : 0;
        }
        batch.n_tokens = n;
        const int rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            LOGE("llama_decode failed (rc=%d) at offset %d", rc, off);
            return false;
        }
    }
    return true;
}

/** Build a prompt with the model's own chat template when it has one. */
static std::string build_prompt(const std::string& system, const std::string& user) {
    const char* tmpl = g_model ? llama_model_chat_template(g_model, nullptr) : nullptr;

    if (tmpl != nullptr) {
        llama_chat_message msgs[2];
        int n_msgs = 0;
        if (!system.empty()) {
            msgs[n_msgs].role    = "system";
            msgs[n_msgs].content = system.c_str();
            ++n_msgs;
        }
        msgs[n_msgs].role    = "user";
        msgs[n_msgs].content = user.c_str();
        ++n_msgs;

        // Ask for the required size first, then allocate.
        int need = llama_chat_apply_template(tmpl, msgs, n_msgs, true, nullptr, 0);
        if (need > 0) {
            std::vector<char> buf((size_t)need + 64);
            int written = llama_chat_apply_template(tmpl, msgs, n_msgs, true,
                                                    buf.data(), (int32_t)buf.size());
            if (written > 0) {
                return std::string(buf.data(), (size_t)written);
            }
        }
        LOGW("Chat template present but could not be applied; using fallback");
    }

    // Fallback for raw completion models.
    std::string p;
    if (!system.empty()) p += system + "\n\n";
    p += user;
    p += "\n";
    return p;
}

// ── JNI Exports ───────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_workspacefilemanager_ai_OfflineAIEngine_loadModel(
        JNIEnv* env, jobject /* obj */, jstring jpath, jobject jcallback) {

    std::lock_guard<std::mutex> gen_lock(g_gen_mutex);
    std::lock_guard<std::mutex> lock(g_state_mutex);

    std::string path = jstring_to_std(env, jpath);

    if (g_model && g_ctx && g_loaded_path == path) {
        LOGI("Model already loaded: %s", path.c_str());
        return JNI_TRUE;
    }

    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_loaded_path.clear();

    if (!g_backend_ready.exchange(true)) {
        llama_backend_init();
    }

    LOGI("Loading model from: %s", path.c_str());

    struct llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU-only on Android
    mparams.load_mode    = LLAMA_LOAD_MODE_MMAP;

    // Report real load progress to the UI (0-100%) instead of a blind spinner.
    LoadProgress progress{env, jcallback, nullptr, -1};
    if (jcallback) {
        jclass cb_class = env->GetObjectClass(jcallback);
        progress.method = env->GetMethodID(cb_class, "onProgress", "(I)V");
        env->DeleteLocalRef(cb_class);
        if (!progress.method) {
            LOGW("Callback has no onProgress(Int) method");
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        mparams.progress_callback = load_progress_cb;
        mparams.progress_callback_user_data = &progress;
    }

    g_cancel.store(false);
    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) {
        LOGE("Failed to load model from %s", path.c_str());
        return JNI_FALSE;
    }

    const int threads = device_threads();

    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = MAX_CONTEXT_TOKENS;
    cparams.n_batch         = BATCH_SIZE;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_loaded_path = path;
    g_cancel.store(false);
    LOGI("Model loaded. Context: %d tokens, threads: %d",
         MAX_CONTEXT_TOKENS, threads);
    return JNI_TRUE;
}

/**
 * generateStream(system: String, user: String, maxTokens: Int, callback: AIStreamCallback): String
 *
 * Streams partial text through callback.onToken(String) and returns the full
 * answer. Returns the partial answer if cancelled.
 */
JNIEXPORT jstring JNICALL
Java_com_example_workspacefilemanager_ai_OfflineAIEngine_generateStream(
        JNIEnv* env, jobject /* obj */, jstring jsystem, jstring juser,
        jint jmax_tokens, jobject jcallback) {

    std::lock_guard<std::mutex> gen_lock(g_gen_mutex);

    struct llama_model*   model = nullptr;
    struct llama_context* ctx   = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        model = g_model;
        ctx   = g_ctx;
    }

    if (!model || !ctx) {
        LOGE("generateStream called but model is not loaded");
        return std_to_jstring(env, "[AI Error] Model not loaded. Load a model first.");
    }

    g_cancel.store(false);

    // Callback lookup: Kotlin fun interface AIStreamCallback { fun onToken(text: String) }
    jmethodID on_token = nullptr;
    if (jcallback) {
        jclass cb_class = env->GetObjectClass(jcallback);
        on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cb_class);
        if (!on_token) {
            LOGW("Callback has no onToken(String) method; streaming disabled");
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    const std::string system = jstring_to_std(env, jsystem);
    const std::string user   = jstring_to_std(env, juser);
    if (user.empty()) {
        return std_to_jstring(env, "[AI Error] Empty prompt.");
    }

    std::string prompt = build_prompt(system, user);

    const struct llama_vocab* vocab = llama_model_get_vocab(model);
    const int n_vocab = llama_vocab_n_tokens(vocab);

    const int max_new = (jmax_tokens > 0)
        ? std::min((int)jmax_tokens, MAX_NEW_TOKENS)
        : MAX_NEW_TOKENS;

    // ── Tokenise (ask llama.cpp for the exact size first) ─────────────────
    int n_tokens = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                                   nullptr, 0, /*add_special=*/true,
                                   /*parse_special=*/true);
    if (n_tokens <= 0) {
        LOGE("Tokenisation failed to size prompt");
        return std_to_jstring(env, "[AI Error] Tokenisation failed.");
    }
    std::vector<llama_token> tokens((size_t)n_tokens);
    int got = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                             tokens.data(), (int)tokens.size(),
                             /*add_special=*/true, /*parse_special=*/true);
    if (got < 0) {
        LOGE("Tokenisation failed, got=%d", got);
        return std_to_jstring(env, "[AI Error] Tokenisation failed.");
    }
    tokens.resize((size_t)got);
    n_tokens = got;

    const int context_budget = MAX_CONTEXT_TOKENS - max_new;
    if (n_tokens > context_budget) {
        LOGW("Prompt too long (%d tokens), keeping the tail.", n_tokens);
        tokens.erase(tokens.begin(), tokens.begin() + (n_tokens - context_budget));
        n_tokens = context_budget;
    }

    // ── Reset KV cache: without this every request grew the old context ───
    llama_memory_clear(llama_get_memory(ctx), true);

    if (!decode_chunked(ctx, tokens, 0, n_tokens)) {
        return std_to_jstring(env, "[AI Error] Prompt decoding failed (out of memory?).");
    }

    // ── Sampler chain ─────────────────────────────────────────────────────
    struct llama_sampler* sampler = llama_sampler_chain_init(
            llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler,
        llama_sampler_init_penalties(n_vocab, 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    output.reserve(1024);
    std::string pending;
    int emitted = 0;
    int n_pos = n_tokens;

    for (int i = 0; i < max_new; ++i) {
        if (g_cancel.load()) {
            LOGI("Generation cancelled after %d tokens", i);
            break;
        }

        llama_token new_token = llama_sampler_sample(sampler, ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("EOS reached after %d tokens", i);
            break;
        }

        char buf[256] = {};
        int len = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (len > 0) {
            output.append(buf, (size_t)len);
            pending.append(buf, (size_t)len);
            ++emitted;
        }

        // Flush live text (only on a character boundary)
        if (on_token && emitted >= JNI_FLUSH_TOKENS && utf8_complete(pending)) {
            jstring piece = env->NewStringUTF(pending.c_str());
            if (piece) {
                env->CallVoidMethod(jcallback, on_token, piece);
                env->DeleteLocalRef(piece);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            pending.clear();
            emitted = 0;
        }

        std::vector<llama_token> one{new_token};
        if (!decode_chunked(ctx, one, n_pos, 1)) {
            break;
        }
        ++n_pos;

        if (n_pos >= MAX_CONTEXT_TOKENS - 1) {
            LOGW("Context window exhausted");
            break;
        }
    }

    if (on_token && !pending.empty()) {
        jstring piece = env->NewStringUTF(pending.c_str());
        if (piece) {
            env->CallVoidMethod(jcallback, on_token, piece);
            env->DeleteLocalRef(piece);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    llama_sampler_free(sampler);

    LOGI("Generated %zu chars", output.size());
    return std_to_jstring(env, output);
}

/** cancelGeneration(): Unit — safe to call from the UI thread mid-generation. */
JNIEXPORT void JNICALL
Java_com_example_workspacefilemanager_ai_OfflineAIEngine_cancelGeneration(
        JNIEnv* /* env */, jobject /* obj */) {
    g_cancel.store(true);
    LOGI("Cancellation requested");
}

JNIEXPORT void JNICALL
Java_com_example_workspacefilemanager_ai_OfflineAIEngine_freeModel(
        JNIEnv* /* env */, jobject /* obj */) {

    std::lock_guard<std::mutex> gen_lock(g_gen_mutex);
    std::lock_guard<std::mutex> lock(g_state_mutex);

    g_cancel.store(true);
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_loaded_path.clear();
    LOGI("Model freed");
}

JNIEXPORT jboolean JNICALL
Java_com_example_workspacefilemanager_ai_OfflineAIEngine_isModelLoaded(
        JNIEnv* /* env */, jobject /* obj */) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

/** recommendedThreads(): Int — exposed for the loading UI. */
JNIEXPORT jint JNICALL
Java_com_example_workspacefilemanager_ai_OfflineAIEngine_recommendedThreads(
        JNIEnv* /* env */, jobject /* obj */) {
    return device_threads();
}

} // extern "C"
