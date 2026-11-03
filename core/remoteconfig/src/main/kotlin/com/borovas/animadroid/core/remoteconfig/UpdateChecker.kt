package com.borovas.animadroid.core.remoteconfig

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val changelog: String, val apkUrl: String, val pageUrl: String) : UpdateState
    /** [progress] 0..1, or -1 when the size is unknown. */
    data class Downloading(val available: Available, val progress: Float) : UpdateState
    data class ReadyToInstall(val available: Available, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Serializable
internal data class GitHubAsset(val name: String = "", @SerialName("browser_download_url") val url: String = "")

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

private val releaseJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

private fun GitHubRelease.toAvailable(currentVersion: String, includePrereleases: Boolean): UpdateState.Available? {
    if (draft || (prerelease && !includePrereleases) || !isNewerVersion(tag, currentVersion)) return null
    val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
    return UpdateState.Available(tag.trim().removePrefix("v"), body.orEmpty().trim(), apk.url, htmlUrl)
}

/** Parses a `releases/latest` response; returns [UpdateState.Available] only for a newer release that ships an APK. */
internal fun parseRelease(body: String, currentVersion: String, includePrereleases: Boolean = false): UpdateState =
    releaseJson.decodeFromString<GitHubRelease>(body).toAvailable(currentVersion, includePrereleases) ?: UpdateState.UpToDate

/** Parses a `releases` list: the highest version (by semver, not by publish order) that is newer than ours and ships an APK. */
internal fun parseReleaseList(body: String, currentVersion: String, includePrereleases: Boolean): UpdateState =
    releaseJson.decodeFromString<List<GitHubRelease>>(body)
        .mapNotNull { r -> r.toAvailable(currentVersion, includePrereleases) }
        .maxWithOrNull(compareBy { SemVer.parse(it.version) ?: SemVer(listOf(0)) })
        ?: UpdateState.UpToDate

class UpdateChecker(
    private val client: OkHttpClient,
    private val currentVersion: String,
    private val cacheDir: File,
    private val repo: String = RemoteConfigKeys.GITHUB_REPO,
    private val apiBase: String = "https://api.github.com",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val updatesDir get() = File(cacheDir, "updates")

    /** [includePrereleases] also offers GitHub "pre-release" builds (e.g. `0.3.0-alpha1`); the user opts in via settings. */
    suspend fun check(includePrereleases: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = withContext(io) {
            try {
                val path = if (includePrereleases) "releases?per_page=30" else "releases/latest"
                val req = Request.Builder().url("$apiBase/repos/$repo/$path")
                    .header("Accept", "application/vnd.github+json").build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> UpdateState.UpToDate // no releases yet
                        !resp.isSuccessful -> UpdateState.Failed("HTTP ${resp.code}")
                        else -> resp.body.string().let { b ->
                            if (includePrereleases) parseReleaseList(b, currentVersion, true) else parseRelease(b, currentVersion)
                        }
                    }
                }
            } catch (e: Exception) { UpdateState.Failed(e.message ?: "error") }
        }
    }

    suspend fun download() {
        val a = (_state.value as? UpdateState.Available) ?: return
        _state.value = UpdateState.Downloading(a, -1f)
        _state.value = withContext(io) {
            try {
                updatesDir.deleteRecursively(); updatesDir.mkdirs()
                val out = File(updatesDir, "animadroid-${a.version}.apk")
                val part = File(updatesDir, out.name + ".part")
                client.newCall(Request.Builder().url(a.apkUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext UpdateState.Failed("HTTP ${resp.code}")
                    val total = resp.body.contentLength()
                    var done = 0L
                    resp.body.byteStream().use { input ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                o.write(buf, 0, n); done += n
                                if (total > 0) _state.value = UpdateState.Downloading(a, done.toFloat() / total)
                            }
                        }
                    }
                }
                part.renameTo(out)
                UpdateState.ReadyToInstall(a, out)
            } catch (e: Exception) { UpdateState.Failed(e.message ?: "error") }
        }
    }

    /** Hides the banner for this process (e.g. user tapped "Later"). */
    fun dismiss() { _state.value = UpdateState.Idle }
}
