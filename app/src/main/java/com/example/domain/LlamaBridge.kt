package com.example.domain

import android.util.Log

fun interface LlamaTokenCallback {
    fun onToken(piece: String): Boolean
}

object LlamaBridge {
    private const val TAG = "LlamaBridge"
    private var isLoaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("llama-android")
            isLoaded = true
            Log.i(TAG, "Native library libllama-android.so loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            loadError = e.message
            Log.w(TAG, "Native library libllama-android.so not loaded: ${e.message}")
        } catch (e: Exception) {
            isLoaded = false
            loadError = e.message
            Log.e(TAG, "Failed loading libllama-android.so: ${e.message}")
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded
    fun getLoadError(): String? = loadError

    // JNI Native Methods aligned with llama-jni.h and llama-jni.cpp
    external fun nativeInit(
        modelPath: String,
        nThreads: Int,
        nCtx: Int
    ): Long

    external fun nativeApplyChatTemplate(
        handle: Long,
        systemPrompt: String,
        userHistory: Array<String>,
        assistantHistory: Array<String>,
        userQuery: String
    ): String

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        callback: LlamaTokenCallback?
    ): String

    external fun nativeCancel(handle: Long)

    external fun nativeFree(handle: Long)

    external fun nativeGetModelSizeBytes(handle: Long): Long
}
