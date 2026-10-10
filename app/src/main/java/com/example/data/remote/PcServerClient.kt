package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class PcResult {
    data class Success(val text: String, val model: String) : PcResult()
    data class Error(val message: String) : PcResult()
}

class PcServerClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun testConnection(baseUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) {
            return@withContext Pair(false, "Адрес сервера не указан")
        }

        // Try Ollama /api/tags first
        try {
            val request = Request.Builder()
                .url("$cleanUrl/api/tags")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val count = try {
                        val obj = JSONObject(body)
                        val models = obj.optJSONArray("models")
                        models?.length() ?: 0
                    } catch (e: Exception) {
                        0
                    }
                    return@withContext Pair(true, "Ollama подключен ($count доступных моделей)")
                }
            }
        } catch (_: Exception) {
            // try next endpoint
        }

        // Try OpenAI compatible /v1/models (llama.cpp or Ollama)
        try {
            val request = Request.Builder()
                .url("$cleanUrl/v1/models")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext Pair(true, "Локальный сервер (llama.cpp / API) доступен")
                }
            }
        } catch (_: Exception) {
            // continue
        }

        // Try root
        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 404 || response.code == 403) {
                    return@withContext Pair(true, "Сервер отвечает (код ${response.code})")
                }
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Не удалось подключиться: ${e.localizedMessage ?: "Таймаут или неверный IP"}")
        }

        Pair(false, "Сервер недоступен")
    }

    suspend fun generateResponse(
        baseUrl: String,
        model: String,
        prompt: String,
        history: List<Pair<String, String>>
    ): PcResult = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) {
            return@withContext PcResult.Error("Адрес сервера не задан")
        }

        // Try 1: Ollama native /api/chat or /api/generate
        try {
            val messagesArray = JSONArray()
            // System prompt
            val sysObj = JSONObject().apply {
                put("role", "system")
                put("content", "Ты — персональный русскоязычный голосовой ассистент. Отвечай кратко, емко, дружелюбно и по существу на русском языке. Ответы должны быть удобны для восприятия на слух.")
            }
            messagesArray.put(sysObj)

            // Last 6 turns of history
            history.takeLast(6).forEach { (role, content) ->
                val msgObj = JSONObject().apply {
                    put("role", if (role == "user") "user" else "assistant")
                    put("content", content)
                }
                messagesArray.put(msgObj)
            }

            // Current prompt
            messagesArray.put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })

            val chatPayload = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
                put("stream", false)
            }

            val request = Request.Builder()
                .url("$cleanUrl/api/chat")
                .post(chatPayload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val content = json.optJSONObject("message")?.optString("content")?.trim()
                    if (!content.isNullOrBlank()) {
                        return@withContext PcResult.Success(content, model)
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to OpenAI compatible endpoint below
        }

        // Try 2: OpenAI compatible /v1/chat/completions (llama.cpp server / LocalAI / Ollama v1)
        try {
            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", "Ты — персональный русскоязычный голосовой ассистент. Отвечай кратко, емко, дружелюбно на русском языке.")
            })
            history.takeLast(6).forEach { (role, content) ->
                messagesArray.put(JSONObject().apply {
                    put("role", if (role == "user") "user" else "assistant")
                    put("content", content)
                })
            }
            messagesArray.put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
                put("max_tokens", 512)
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url("$cleanUrl/v1/chat/completions")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val messageObj = choices.getJSONObject(0).optJSONObject("message")
                        val content = messageObj?.optString("content")?.trim()
                        if (!content.isNullOrBlank()) {
                            return@withContext PcResult.Success(content, model)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext PcResult.Error("Ошибка ПК сервера: ${e.localizedMessage ?: "Таймаут соединения"}")
        }

        PcResult.Error("Сервер вернул пустой ответ или недоступен")
    }
}
