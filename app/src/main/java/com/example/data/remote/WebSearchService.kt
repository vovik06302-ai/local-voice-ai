package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SearchResultItem(
    val title: String,
    val url: String,
    val snippet: String
)

sealed class SearchResponse {
    data class Success(val query: String, val results: List<SearchResultItem>) : SearchResponse()
    data class Empty(val query: String) : SearchResponse()
    data class Error(val message: String) : SearchResponse()
}

class WebSearchService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun search(query: String, customApiKey: String = ""): SearchResponse = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext SearchResponse.Error("Поисковый запрос пуст")
        }

        if (!isOnline()) {
            return@withContext SearchResponse.Error("Нет подключения к интернету. Проверьте соединение.")
        }

        // 1. If user provided a Tavily API key in settings, use reliable structured Tavily API
        if (customApiKey.isNotBlank()) {
            try {
                val tavilyResult = searchWithTavily(cleanQuery, customApiKey)
                if (tavilyResult is SearchResponse.Success && tavilyResult.results.isNotEmpty()) {
                    return@withContext tavilyResult
                }
            } catch (_: Exception) {
                // fallback to free search
            }
        }

        // 2. Free Web Search: DuckDuckGo HTML parser
        try {
            val ddgResults = searchDuckDuckGoHtml(cleanQuery)
            if (ddgResults.isNotEmpty()) {
                return@withContext SearchResponse.Success(cleanQuery, ddgResults)
            }
        } catch (_: Exception) {
            // fallback to SearXNG
        }

        // 3. Free Web Search Fallback: SearXNG JSON API
        try {
            val searxResults = searchSearxNg(cleanQuery)
            if (searxResults.isNotEmpty()) {
                return@withContext SearchResponse.Success(cleanQuery, searxResults)
            }
        } catch (e: Exception) {
            return@withContext SearchResponse.Error("Ошибка поискового сервиса: ${e.localizedMessage ?: "Таймаут или сбой соединения"}")
        }

        SearchResponse.Empty(cleanQuery)
    }

    private fun searchDuckDuckGoHtml(query: String): List<SearchResultItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string() ?: return emptyList()

            val results = mutableListOf<SearchResultItem>()

            // Pattern for DuckDuckGo HTML results
            val blockRegex = Pattern.compile(
                """<div[^>]*class="[^"]*result[^"]*"[^>]*>([\s\S]*?)</div>\s*</div>""",
                Pattern.CASE_INSENSITIVE
            )
            val matcher = blockRegex.matcher(html)

            val titleUrlRegex = Pattern.compile(
                """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
                Pattern.CASE_INSENSITIVE
            )
            val altTitleRegex = Pattern.compile(
                """<a[^>]*class="[^"]*result__url[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
                Pattern.CASE_INSENSITIVE
            )
            val snippetRegex = Pattern.compile(
                """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)</a>""",
                Pattern.CASE_INSENSITIVE
            )

            // General parser for links and snippets in DDG
            val generalLinkRegex = Pattern.compile(
                """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
                Pattern.CASE_INSENSITIVE
            )

            val generalMatcher = generalLinkRegex.matcher(html)
            while (generalMatcher.find() && results.size < 5) {
                val rawHref = generalMatcher.group(1) ?: ""
                val rawTitle = cleanHtml(generalMatcher.group(2) ?: "")

                var realUrl = rawHref
                if (rawHref.contains("uddg=")) {
                    val encodedTarget = rawHref.substringAfter("uddg=").substringBefore("&")
                    realUrl = try { URLDecoder.decode(encodedTarget, "UTF-8") } catch (_: Exception) { rawHref }
                } else if (rawHref.startsWith("//")) {
                    realUrl = "https:$rawHref"
                }

                // Look for snippet following this item
                val tail = html.substring(generalMatcher.end())
                val snipM = snippetRegex.matcher(tail)
                var snippet = ""
                if (snipM.find() && snipM.start() < 500) {
                    snippet = cleanHtml(snipM.group(1) ?: "")
                }

                if (rawTitle.isNotBlank() && realUrl.startsWith("http")) {
                    results.add(
                        SearchResultItem(
                            title = rawTitle,
                            url = realUrl,
                            snippet = if (snippet.isNotBlank()) snippet else "Описание страницы не предоставлено."
                        )
                    )
                }
            }

            return results
        }
    }

    private fun searchSearxNg(query: String): List<SearchResultItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val endpoints = listOf(
            "https://searx.be/search?q=$encoded&format=json&language=ru",
            "https://search.ononoki.org/search?q=$encoded&format=json&language=ru"
        )

        for (endpoint in endpoints) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "VoiceAI-Android/1.1 (Mobile; Android)")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val resultsArray = json.optJSONArray("results") ?: return@use
                        val items = mutableListOf<SearchResultItem>()
                        for (i in 0 until minOf(resultsArray.length(), 5)) {
                            val obj = resultsArray.getJSONObject(i)
                            val title = cleanHtml(obj.optString("title", ""))
                            val url = obj.optString("url", "")
                            val content = cleanHtml(obj.optString("content", ""))
                            if (title.isNotBlank() && url.startsWith("http")) {
                                items.add(SearchResultItem(title, url, content))
                            }
                        }
                        if (items.isNotEmpty()) return items
                    }
                }
            } catch (_: Exception) {
                // try next endpoint
            }
        }
        return emptyList()
    }

    private fun searchWithTavily(query: String, apiKey: String): SearchResponse {
        val payload = JSONObject().apply {
            put("query", query)
            put("api_key", apiKey.trim())
            put("max_results", 5)
            put("search_depth", "basic")
        }

        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SearchResponse.Error("Ошибка Tavily API: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return SearchResponse.Error("Пустой ответ от Tavily")
            val json = JSONObject(body)
            val resultsArray = json.optJSONArray("results") ?: return SearchResponse.Empty(query)

            val items = mutableListOf<SearchResultItem>()
            for (i in 0 until resultsArray.length()) {
                val obj = resultsArray.getJSONObject(i)
                val title = cleanHtml(obj.optString("title", ""))
                val url = obj.optString("url", "")
                val content = cleanHtml(obj.optString("content", ""))
                if (title.isNotBlank() && url.isNotBlank()) {
                    items.add(SearchResultItem(title, url, content))
                }
            }

            return if (items.isNotEmpty()) {
                SearchResponse.Success(query, items)
            } else {
                SearchResponse.Empty(query)
            }
        }
    }

    private fun cleanHtml(text: String): String {
        return text.replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}
