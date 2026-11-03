package com.borovas.animadroid.core.remoteconfig

import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Remotely updatable provider constants (`config/providers.json`).
 *
 * Load order at construction: verified disk cache, otherwise the bundled classpath copy (whichever has the higher
 * `revision` wins). [refresh] then fetches the raw GitHub file with `If-None-Match`, verifies the detached Ed25519
 * signature (`.sig`, base64) over the exact bytes, and persists it. Any failure keeps what is already loaded.
 *
 * [allowUnsigned] (debug builds only) accepts a document whose `.sig` is *missing*; a present-but-wrong signature
 * is always rejected.
 */
class RemoteConfigRepository(
    private val client: OkHttpClient,
    private val dir: File,
    private val bundledJson: () -> String,
    private val publicKey: ByteArray?,
    private val allowUnsigned: Boolean,
    private val appVersion: String,
    private val configUrl: String = RemoteConfigKeys.CONFIG_URL,
    private val signatureUrl: String = "$configUrl.sig",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonFile get() = File(dir, "providers.json")
    private val sigFile get() = File(dir, "providers.json.sig")
    private val etagFile get() = File(dir, "providers.json.etag")

    private val _config = MutableStateFlow(loadInitial())
    val config: StateFlow<RemoteConfig> = _config.asStateFlow()

    /** Config for a provider with null-safe defaults; `enabled` is already false when this app is older than `minAppVersion`. */
    fun forProvider(id: String): ProviderRemoteConfig {
        val p = _config.value.providers[id] ?: return ProviderRemoteConfig.DEFAULT
        val min = p.minAppVersion?.let(SemVer::parse)
        val app = SemVer.parse(appVersion)
        return if (min != null && app != null && app < min) p.copy(enabled = false) else p
    }

    private fun loadInitial(): RemoteConfig {
        val bundled = runCatching { json.decodeFromString<RemoteConfig>(bundledJson()) }.getOrDefault(RemoteConfig())
        val cached = runCatching {
            val text = jsonFile.takeIf { it.exists() }?.readText() ?: return@runCatching null
            val sig = sigFile.takeIf { it.exists() }?.readText()
            if (!verified(text.encodeToByteArray(), sig)) return@runCatching null
            json.decodeFromString<RemoteConfig>(text)
        }.getOrNull()
        return if (cached != null && cached.revision >= bundled.revision) cached else bundled
    }

    private fun verified(body: ByteArray, sigB64: String?): Boolean {
        if (sigB64 == null) return allowUnsigned
        val key = publicKey ?: return false
        val sig = runCatching { Base64.getMimeDecoder().decode(sigB64.trim()) }.getOrNull() ?: return false
        return Ed25519.verify(key, body, sig)
    }

    /** Returns true when a newer document was adopted. Never throws. */
    suspend fun refresh(): Boolean = withContext(io) {
        try {
            dir.mkdirs()
            val req = Request.Builder().url(configUrl).apply {
                etagFile.takeIf { it.exists() && jsonFile.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { header("If-None-Match", it) }
            }.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 304) return@withContext false
                if (!resp.isSuccessful) return@withContext false
                val etag = resp.header("ETag")
                val body = resp.body.bytes()
                val sig = fetchSignature()
                if (!verified(body, sig)) return@withContext false
                val parsed = json.decodeFromString<RemoteConfig>(body.decodeToString())
                if (parsed.revision < _config.value.revision) return@withContext false
                persist(body, sig, etag)
                _config.value = parsed
                true
            }
        } catch (_: Exception) { false }
    }

    private fun fetchSignature(): String? = try {
        client.newCall(Request.Builder().url(signatureUrl).build()).execute().use { r ->
            if (r.isSuccessful) r.body.string() else null
        }
    } catch (_: Exception) { null }

    private fun persist(body: ByteArray, sig: String?, etag: String?) {
        atomicWrite(jsonFile, body)
        if (sig != null) atomicWrite(sigFile, sig.encodeToByteArray()) else sigFile.delete()
        if (etag != null) atomicWrite(etagFile, etag.encodeToByteArray()) else etagFile.delete()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}
