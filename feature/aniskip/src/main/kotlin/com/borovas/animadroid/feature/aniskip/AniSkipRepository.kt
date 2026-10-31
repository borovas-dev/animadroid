package dev.borovas.animadroid.feature.aniskip

import dev.borovas.animadroid.core.model.SkipInterval
import dev.borovas.animadroid.core.model.SkipTimes
import dev.borovas.animadroid.core.model.SkipTimesRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class SkipResponse(val found: Boolean = false, val results: List<SkipResult> = emptyList())

@Serializable
internal data class SkipResult(
    val interval: Interval,
    @SerialName("skip_type") val skipType: String,
)

@Serializable
internal data class Interval(
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
)

internal val aniSkipJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal const val USER_AGENT = "Mozilla/5.0 (Android) animadroid"

/** Pure validation, mirrors ani-skip build_options. */
internal fun buildSkipTimes(results: List<SkipResult>): SkipTimes? {
    val op = results.firstOrNull { it.skipType == "op" }?.interval
        ?.takeIf { it.startTime < it.endTime }?.let { SkipInterval(it.startTime, it.endTime) }
    val ed = results.firstOrNull { it.skipType == "ed" }?.interval
        ?.takeIf { it.startTime < it.endTime && (op == null || it.startTime >= op.endSec) }
        ?.let { SkipInterval(it.startTime, it.endTime) }
    return if (op == null && ed == null) null else SkipTimes(op, ed)
}

/** AniSkip API client with an in-memory LRU cache. Never throws; returns null on failure. */
@Singleton
class AniSkipRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val cacheSize: Int,
) : SkipTimesRepository {

    @Inject
    constructor(client: OkHttpClient) : this(client, DEFAULT_BASE_URL, 64)

    /** For tests / custom endpoints. */
    constructor(client: OkHttpClient, baseUrl: String) : this(client, baseUrl, 64)

    private val cache = object : LinkedHashMap<String, SkipTimes?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SkipTimes?>?) = size > cacheSize
    }

    override suspend fun skipTimes(malId: Int, episode: Int): SkipTimes? {
        val key = "$malId:$episode"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        return try {
            val result = fetch(malId, episode)
            synchronized(cache) { cache[key] = result }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetch(malId: Int, episode: Int): SkipTimes? = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/').plus("/v1/skip-times/$malId/$episode").toHttpUrl().newBuilder()
            .addQueryParameter("types", "op").addQueryParameter("types", "ed").build()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@use null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val parsed = aniSkipJson.decodeFromString<SkipResponse>(resp.body.string())
            if (!parsed.found) null else buildSkipTimes(parsed.results)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.aniskip.com"
    }
}
