#ifndef LLAMA_JNI_H
#define LLAMA_JNI_H

#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <memory>
#include <android/log.h>

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// Forward declarations of genuine llama.cpp types
struct llama_model;
struct llama_context;

/**
 * Thread-safe wrapper around a genuine llama_model and llama_context session.
 */
struct LlamaSession {
    llama_model   * model{nullptr};
    llama_context * ctx{nullptr};

    std::string model_path;
    int n_threads{4};
    int n_ctx{2048};
    int n_batch{512};

    uint64_t model_weights_bytes{0};

    std::atomic<bool> cancel_requested{false};
    std::atomic<bool> is_generating{false};
    std::atomic<bool> is_closing{false};

    // Serializes generation and model lifecycle changes
    std::mutex op_mutex;

    ~LlamaSession();
};

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL
Java_com_example_domain_LlamaBridge_nativeInit(
    JNIEnv *env,
    jobject thiz,
    jstring model_path,
    jint n_threads,
    jint n_ctx
);

JNIEXPORT jstring JNICALL
Java_com_example_domain_LlamaBridge_nativeApplyChatTemplate(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jstring system_prompt,
    jobjectArray user_history,
    jobjectArray assistant_history,
    jstring user_query
);

JNIEXPORT jstring JNICALL
Java_com_example_domain_LlamaBridge_nativeGenerate(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jstring prompt,
    jint max_tokens,
    jobject token_callback
);

JNIEXPORT void JNICALL
Java_com_example_domain_LlamaBridge_nativeCancel(
    JNIEnv *env,
    jobject thiz,
    jlong handle
);

JNIEXPORT void JNICALL
Java_com_example_domain_LlamaBridge_nativeFree(
    JNIEnv *env,
    jobject thiz,
    jlong handle
);

JNIEXPORT jlong JNICALL
Java_com_example_domain_LlamaBridge_nativeGetModelSizeBytes(
    JNIEnv *env,
    jobject thiz,
    jlong handle
);

#ifdef __cplusplus
}
#endif

#endif // LLAMA_JNI_H
