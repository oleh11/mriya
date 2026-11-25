package dev.mriya.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Non-2xx answer from the server. Typed so callers (the background worker) can decide between retry and give up. */
class DawarichHttpException(val code: Int) :
    IllegalStateException("Dawarich returned HTTP $code (check the server URL and API key)")

/**
 * Dawarich client. The API key goes in an Authorization: Bearer header so it stays out of URLs
 * (reverse-proxy access logs, crash reports). Legacy `?api_key=` query-parameter fallback on
 * HTTP 401/403 is disabled by default and only used when [allowQueryFallback] is enabled.
 */
class DawarichApi(
    baseUrl: String,
    private val apiKey: String,
    private val allowQueryFallback: Boolean = false,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private var useQuery = false

    data class Page(val points: List<Point>, val totalPages: Int)

    private fun request(path: String, params: Map<String, String>, query: Boolean): Request {
        val url = "$base$path".toHttpUrl().newBuilder().apply {
            if (query) addQueryParameter("api_key", apiKey)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).apply { if (!query) header("Authorization", "Bearer $apiKey") }.build()
    }

    private fun <T> call(path: String, params: Map<String, String> = emptyMap(), parse: (Response) -> T): T {
        var r = client.newCall(request(path, params, useQuery)).execute()
        if (allowQueryFallback && !useQuery && (r.code == 401 || r.code == 403)) {
            r.close(); useQuery = true
            r = client.newCall(request(path, params, true)).execute()
        }
        return r.use {
            if (!it.isSuccessful) throw DawarichHttpException(it.code)
            parse(it)
        }
    }

    /** Points in ascending time order, newer than [startAt] (unix seconds; 0 = all). */
    suspend fun points(startAt: Long, page: Int, perPage: Int = 1000): Page = withContext(Dispatchers.IO) {
        val params = buildMap {
            if (startAt > 0) put("start_at", Instant.ofEpochSecond(startAt).toString()) // ISO-8601, as documented
            put("page", page.toString())
            put("per_page", perPage.toString())
            put("order", "asc")
        }
        call("/api/v1/points", params) { r ->
            val total = r.header("X-Total-Pages")?.toIntOrNull() ?: 1
            Page(json.decodeFromString(ListSerializer(Point.serializer()), r.body?.string().orEmpty()), total)
        }
    }

    suspend fun stats(): DawarichStats = withContext(Dispatchers.IO) {
        call("/api/v1/stats") { r -> json.decodeFromString(DawarichStats.serializer(), r.body?.string().orEmpty()) }
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** One shared client: connection pool and threads are reused across syncs. */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
        }
    }
}
