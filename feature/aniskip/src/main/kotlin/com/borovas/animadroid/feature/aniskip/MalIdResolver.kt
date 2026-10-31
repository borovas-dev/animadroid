package com.borovas.animadroid.feature.aniskip

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class PrefixResponse(val categories: List<PrefixCategory> = emptyList())

@Serializable
internal data class PrefixCategory(val type: String = "", val items: List<PrefixItem> = emptyList())

@Serializable
internal data class PrefixItem(val id: Int, val name: String = "")

internal fun normalizeTitle(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/** Fallback title -> MAL id lookup via MyAnimeList's prefix search. Never throws. */
@Singleton
class MalIdResolver(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    @Inject
    constructor(client: OkHttpClient) : this(client, "https://myanimelist.net")

    suspend fun resolve(title: String): Int? {
        val keyword = title.replace(Regex("[^A-Za-z0-9]"), " ").replace(Regex(" +"), " ").trim()
        if (keyword.isEmpty()) return null
        return try {
            pickBest(title, fetch(keyword))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetch(keyword: String): List<PrefixItem> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/').plus("/search/prefix.json").toHttpUrl().newBuilder()
            .addQueryParameter("type", "anime").addQueryParameter("keyword", keyword).build()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            aniSkipJson.decodeFromString<PrefixResponse>(resp.body.string())
                .categories.filter { it.type == "anime" || it.type.isEmpty() }.flatMap { it.items }
        }
    }

    internal fun pickBest(title: String, items: List<PrefixItem>): Int? {
        if (items.isEmpty()) return null
        val t = normalizeTitle(title)
        return (items.firstOrNull { normalizeTitle(it.name) == t }
            ?: items.firstOrNull { val n = normalizeTitle(it.name); n.contains(t) || t.contains(n) }
            ?: items.first()).id
    }
}
