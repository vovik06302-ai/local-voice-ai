#include "llama-jni.h"
#include "llama.h"

#include <fstream>
#include <sstream>
#include <chrono>
#include <thread>
#include <vector>
#include <string>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <memory>

// Global session registry to protect sessions against use-after-free and race conditions
static std::mutex g_sessions_mutex;
static std::unordered_map<jlong, std::shared_ptr<LlamaSession>> g_active_sessions;
static std::atomic<jlong> g_next_handle{1000};

// Helper: retrieve shared session safely under global registry lock
static std::shared_ptr<LlamaSession> get_session(jlong handle) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_active_sessions.find(handle);
    if (it != g_active_sessions.end()) {
        auto session = it->second;
        if (!session->is_closing.load(std::memory_order_acquire)) {
            return session;
        }
    }
    return nullptr;
}

LlamaSession::~LlamaSession() {
    LOGI("Cleaning up LlamaSession: freeing ctx=%p and model=%p", ctx, model);
    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }
    if (model) {
        llama_free_model(model);
        model = nullptr;
    }
}

// UTF-8 stream validator/accumulator helper
// Accumulates incomplete multibyte UTF-8 sequences to avoid splitting Russian characters
// or multi-byte emoji between callback chunks.
struct Utf8Buffer {
    std::string accumulated;

    // Checks how many trailing bytes in data form an incomplete UTF-8 prefix
    static size_t trailing_incomplete_bytes(const std::string &s) {
        if (s.empty()) return 0;
        size_t len = s.length();
        for (size_t i = 0; i < 4 && i < len; ++i) {
            unsigned char c = static_cast<unsigned char>(s[len - 1 - i]);
            // If ASCII single byte (0xxxxxxx), no incomplete sequence
            if ((c & 0x80) == 0) {
                return (i == 0) ? 0 : 0;
            }
            // If continuation byte (10xxxxxx), continue searching left for lead byte
            if ((c & 0xC0) == 0x80) {
                continue;
            }
            // Leading bytes:
            size_t expected_total = 0;
            if ((c & 0xE0) == 0xC0) expected_total = 2;       // 110xxxxx
            else if ((c & 0xF0) == 0xE0) expected_total = 3;  // 1110xxxx
            else if ((c & 0xF8) == 0xF0) expected_total = 4;  // 11110xxx
            else return 0; // Invalid byte, do not treat as lead

            size_t actual_bytes = i + 1;
            if (actual_bytes < expected_total) {
                return actual_bytes; // These trailing bytes are incomplete
            } else {
                return 0; // Sequence is complete
            }
        }
        return 0;
    }

    // Appends new raw bytes from token piece, returns ready complete valid UTF-8 substring
    std::string push(const std::string &piece) {
        accumulated += piece;
        size_t incomplete = trailing_incomplete_bytes(accumulated);
        if (incomplete == 0) {
            std::string ready = accumulated;
            accumulated.clear();
            return ready;
        } else if (accumulated.length() > incomplete) {
            size_t ready_len = accumulated.length() - incomplete;
            std::string ready = accumulated.substr(0, ready_len);
            accumulated = accumulated.substr(ready_len);
            return ready;
        }
        return ""; // Entire buffer is still an incomplete sequence
    }

    // Flushes whatever remains at the end of generation
    std::string flush() {
        std::string ready = accumulated;
        accumulated.clear();
        return ready;
    }
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_domain_LlamaBridge_nativeInit(
    JNIEnv *env,
    jobject /* thiz */,
    jstring model_path_jstr,
    jint n_threads,
    jint n_ctx
) {
    if (!model_path_jstr) {
        LOGE("nativeInit: model_path_jstr is null");
        return -1;
    }

    const char *path_cstr = env->GetStringUTFChars(model_path_jstr, nullptr);
    std::string path(path_cstr);
    env->ReleaseStringUTFChars(model_path_jstr, path_cstr);

    // Initialize llama backend once
    static std::once_flag backend_init_flag;
    std::call_once(backend_init_flag, []() {
        llama_backend_init();
        LOGI("llama_backend_init executed successfully");
    });

    // 1. Model parameters
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU inference on Android

    LOGI("Loading llama model from: %s", path.c_str());
    llama_model *model = llama_load_model_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("llama_load_model_from_file failed for path: %s", path.c_str());
        return -2;
    }

    // 2. Context parameters
    llama_context_params cparams = llama_context_default_params();
    int actual_ctx = n_ctx > 0 ? n_ctx : 2048;
    int actual_threads = n_threads > 0 ? n_threads : 4;
    int batch_size = 512;

    cparams.n_ctx = actual_ctx;
    cparams.n_batch = batch_size;
    cparams.n_ubatch = batch_size;
    cparams.n_threads = actual_threads;
    cparams.n_threads_batch = actual_threads;
    cparams.no_perf = false;

    llama_context *ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        LOGE("llama_new_context_with_model failed");
        llama_free_model(model);
        return -3;
    }

    auto session = std::make_shared<LlamaSession>();
    session->model = model;
    session->ctx = ctx;
    session->model_path = path;
    session->n_threads = actual_threads;
    session->n_ctx = actual_ctx;
    session->n_batch = batch_size;
    session->model_weights_bytes = llama_model_size(model);
    session->cancel_requested.store(false);
    session->is_generating.store(false);
    session->is_closing.store(false);

    jlong handle = g_next_handle.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        g_active_sessions[handle] = session;
    }

    LOGI("LlamaSession initialized successfully: handle=%lld, weights=%llu bytes, ctx=%d, threads=%d",
         (long long)handle, (unsigned long long)session->model_weights_bytes, actual_ctx, actual_threads);

    return handle;
}

JNIEXPORT jstring JNICALL
Java_com_example_domain_LlamaBridge_nativeApplyChatTemplate(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle,
    jstring system_prompt_jstr,
    jobjectArray user_history,
    jobjectArray assistant_history,
    jstring user_query_jstr
) {
    auto session = get_session(handle);

    // Build chat messages
    std::vector<llama_chat_message> messages;
    std::vector<std::string> strings_storage; // Keep alive during C API call

    if (system_prompt_jstr) {
        const char *sys_cstr = env->GetStringUTFChars(system_prompt_jstr, nullptr);
        strings_storage.emplace_back(sys_cstr);
        env->ReleaseStringUTFChars(system_prompt_jstr, sys_cstr);
        messages.push_back({"system", strings_storage.back().c_str()});
    }

    if (user_history && assistant_history) {
        jsize u_len = env->GetArrayLength(user_history);
        jsize a_len = env->GetArrayLength(assistant_history);
        jsize min_len = u_len < a_len ? u_len : a_len;
        for (jsize i = 0; i < min_len; ++i) {
            auto u_obj = (jstring)env->GetObjectArrayElement(user_history, i);
            auto a_obj = (jstring)env->GetObjectArrayElement(assistant_history, i);
            if (u_obj) {
                const char *u_c = env->GetStringUTFChars(u_obj, nullptr);
                strings_storage.emplace_back(u_c);
                env->ReleaseStringUTFChars(u_obj, u_c);
                env->DeleteLocalRef(u_obj);
                messages.push_back({"user", strings_storage.back().c_str()});
            }
            if (a_obj) {
                const char *a_c = env->GetStringUTFChars(a_obj, nullptr);
                strings_storage.emplace_back(a_c);
                env->ReleaseStringUTFChars(a_obj, a_c);
                env->DeleteLocalRef(a_obj);
                messages.push_back({"assistant", strings_storage.back().c_str()});
            }
        }
    }

    if (user_query_jstr) {
        const char *q_cstr = env->GetStringUTFChars(user_query_jstr, nullptr);
        strings_storage.emplace_back(q_cstr);
        env->ReleaseStringUTFChars(user_query_jstr, q_cstr);
        messages.push_back({"user", strings_storage.back().c_str()});
    }

    // Try native llama_chat_apply_template if model is loaded and template exists
    if (session && session->model) {
        std::vector<char> buf(4096);
        int32_t res = llama_chat_apply_template(session->model, nullptr, messages.data(), messages.size(), true, buf.data(), buf.size());
        if (res > (int32_t)buf.size()) {
            buf.resize(res + 16);
            res = llama_chat_apply_template(session->model, nullptr, messages.data(), messages.size(), true, buf.data(), buf.size());
        }
        if (res >= 0) {
            return env->NewStringUTF(std::string(buf.data(), res).c_str());
        }
    }

    // Standard ChatML fallback formatting if no embedded template in GGUF
    std::ostringstream ss;
    for (const auto &msg : messages) {
        ss << "<|im_start|>" << msg.role << "\n" << msg.content << "<|im_end|>\n";
    }
    ss << "<|im_start|>assistant\n";
    return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_domain_LlamaBridge_nativeGenerate(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle,
    jstring prompt_jstr,
    jint max_tokens,
    jobject token_callback
) {
    auto session = get_session(handle);
    if (!session || !session->model || !session->ctx) {
        return env->NewStringUTF("Ошибка: сессия Llama не инициализирована или освобождена.");
    }

    // Lock session operation to prevent concurrent generation or free
    std::unique_lock<std::mutex> op_lock(session->op_mutex);
    if (session->is_closing.load(std::memory_order_acquire)) {
        return env->NewStringUTF("Ошибка: сессия закрывается.");
    }

    session->is_generating.store(true, std::memory_order_release);
    session->cancel_requested.store(false, std::memory_order_release);

    struct GenerationGuard {
        std::atomic<bool> &flag;
        ~GenerationGuard() { flag.store(false, std::memory_order_release); }
    } guard{session->is_generating};

    // Extract prompt string
    if (!prompt_jstr) {
        return env->NewStringUTF("Ошибка: пустой запрос.");
    }
    const char *p_cstr = env->GetStringUTFChars(prompt_jstr, nullptr);
    std::string prompt(p_cstr);
    env->ReleaseStringUTFChars(prompt_jstr, p_cstr);

    // Setup Java callback if provided
    jclass callback_class = nullptr;
    jmethodID on_token_mid = nullptr;
    if (token_callback) {
        callback_class = env->GetObjectClass(token_callback);
        if (callback_class) {
            on_token_mid = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)Z");
        }
    }

    // 1. Tokenize prompt
    int n_tokens_alloc = -llama_tokenize(session->model, prompt.c_str(), prompt.length(), nullptr, 0, true, true);
    if (n_tokens_alloc <= 0) {
        return env->NewStringUTF("Ошибка токенизации промпта.");
    }
    std::vector<llama_token> prompt_tokens(n_tokens_alloc);
    int32_t n_prompt = llama_tokenize(session->model, prompt.c_str(), prompt.length(), prompt_tokens.data(), prompt_tokens.size(), true, true);
    if (n_prompt < 0) {
        return env->NewStringUTF("Ошибка токенизации промпта: буфер недостаточен.");
    }
    prompt_tokens.resize(n_prompt);

    // Context bounds check
    const int32_t n_ctx = llama_n_ctx(session->ctx);
    if (n_prompt >= n_ctx - 4) {
        std::ostringstream err_ss;
        err_ss << "Ошибка: длина контекста (" << n_prompt << " токенов) превышает лимит модели (" << n_ctx << " токенов). Сократите запрос.";
        return env->NewStringUTF(err_ss.str().c_str());
    }

    // 2. Initialize Sampler (greedy with temperature 0.7 and top_p 0.9)
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(time(nullptr))));

    // Helper: dynamic token_to_piece with reallocation
    auto token_to_string = [&](llama_token tok) -> std::string {
        std::vector<char> piece_buf(64);
        int32_t n = llama_token_to_piece(session->model, tok, piece_buf.data(), piece_buf.size(), 0, false);
        if (n < 0) {
            // Negative value represents required buffer size
            int32_t req_size = -n + 8;
            piece_buf.resize(req_size);
            n = llama_token_to_piece(session->model, tok, piece_buf.data(), piece_buf.size(), 0, false);
        }
        if (n > 0) {
            return std::string(piece_buf.data(), n);
        }
        return "";
    };

    // 3. Process prompt in chunks of n_batch
    const int32_t n_batch = llama_n_batch(session->ctx);
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        if (session->cancel_requested.load(std::memory_order_relaxed) || session->is_closing.load(std::memory_order_relaxed)) {
            llama_sampler_free(smpl);
            return env->NewStringUTF("[Генерация отменена]");
        }
        int32_t cur_batch = std::min(n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(&prompt_tokens[i], cur_batch, i, 0);
        if (llama_decode(session->ctx, batch) != 0) {
            llama_sampler_free(smpl);
            return env->NewStringUTF("Ошибка llama_decode при обработке промпта.");
        }
    }

    // 4. Generation loop
    int32_t max_predict = max_tokens > 0 ? max_tokens : 512;
    int32_t n_cur = n_prompt;
    int32_t n_decode = 0;
    std::string full_response = "";
    Utf8Buffer utf8_buf;

    while (n_decode < max_predict && n_cur < n_ctx - 1) {
        if (session->cancel_requested.load(std::memory_order_relaxed) || session->is_closing.load(std::memory_order_relaxed)) {
            LOGI("Generation cancelled by user signal");
            break;
        }

        // Sample next token
        llama_token new_token_id = llama_sampler_sample(smpl, session->ctx, -1);
        llama_sampler_accept(smpl, new_token_id);

        // Check end of generation
        if (llama_token_is_eog(session->model, new_token_id)) {
            break;
        }

        std::string piece = token_to_string(new_token_id);
        std::string ready_utf8 = utf8_buf.push(piece);

        if (!ready_utf8.empty()) {
            full_response += ready_utf8;
            if (token_callback && on_token_mid) {
                jstring piece_jstr = env->NewStringUTF(ready_utf8.c_str());
                jboolean continue_gen = env->CallBooleanMethod(token_callback, on_token_mid, piece_jstr);
                env->DeleteLocalRef(piece_jstr);

                if (env->ExceptionCheck()) {
                    LOGE("Exception thrown in token callback, stopping generation");
                    env->ExceptionClear();
                    break;
                }
                if (!continue_gen) {
                    LOGI("Callback requested generation halt");
                    break;
                }
            }
        }

        // Decode sampled token
        llama_batch next_batch = llama_batch_get_one(&new_token_id, 1, n_cur, 0);
        if (llama_decode(session->ctx, next_batch) != 0) {
            LOGE("llama_decode error during autoregressive generation");
            break;
        }

        n_cur++;
        n_decode++;
    }

    // Flush any remaining accumulated UTF-8 bytes
    std::string remaining = utf8_buf.flush();
    if (!remaining.empty()) {
        full_response += remaining;
        if (token_callback && on_token_mid) {
            jstring piece_jstr = env->NewStringUTF(remaining.c_str());
            env->CallBooleanMethod(token_callback, on_token_mid, piece_jstr);
            env->DeleteLocalRef(piece_jstr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }

    llama_sampler_free(smpl);

    LOGI("Generation finished: decoded %d tokens, response length: %zu chars", n_decode, full_response.length());
    return env->NewStringUTF(full_response.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_domain_LlamaBridge_nativeCancel(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto session = get_session(handle);
    if (session) {
        session->cancel_requested.store(true, std::memory_order_release);
        LOGI("nativeCancel: set cancel_requested for handle %lld", (long long)handle);
    }
}

JNIEXPORT void JNICALL
Java_com_example_domain_LlamaBridge_nativeFree(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    std::shared_ptr<LlamaSession> session_to_free;

    {
        // 1. Remove from global registry atomically under registry lock
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        auto it = g_active_sessions.find(handle);
        if (it != g_active_sessions.end()) {
            session_to_free = it->second;
            // Mark session as closing so get_session will immediately reject any concurrent calls
            session_to_free->is_closing.store(true, std::memory_order_release);
            session_to_free->cancel_requested.store(true, std::memory_order_release);
            g_active_sessions.erase(it);
        }
    }

    if (!session_to_free) {
        LOGW("nativeFree: session %lld already freed or not found", (long long)handle);
        return;
    }

    // 2. Wait for any active generation to finish safely before freeing model/context
    {
        std::lock_guard<std::mutex> op_lock(session_to_free->op_mutex);
        LOGI("Acquired op_mutex for session %lld: freeing resources", (long long)handle);
        // The shared_ptr destructor will safely invoke ~LlamaSession when session_to_free goes out of scope,
        // which calls llama_free(ctx) and llama_free_model(model) with zero mutex deadlock or dangling pointers.
    }

    LOGI("nativeFree: completed for handle %lld", (long long)handle);
}

JNIEXPORT jlong JNICALL
Java_com_example_domain_LlamaBridge_nativeGetModelSizeBytes(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto session = get_session(handle);
    if (session) {
        return static_cast<jlong>(session->model_weights_bytes);
    }
    return 0;
}

} // extern "C"
