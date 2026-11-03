package com.borovas.animadroid.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** AniList GraphQL (https://docs.anilist.co). Implicit grant tokens last a year and cannot be refreshed. */
class AniListClient(
    private val http: OkHttpClient,
    private val endpoint: String = "https://graphql.anilist.co",
) {
    data class Entry(val mediaId: Int, val episodes: Int?, val progress: Int?)

    companion object {
        const val AUTH_BASE = "https://anilist.co/api/v2/oauth/authorize"
        fun authorizeUrl(clientId: String) = "$AUTH_BASE?client_id=$clientId&response_type=token"

        internal const val VIEWER = "query { Viewer { name } }"
        internal const val LOOKUP =
            "query (\$idMal: Int) { Media(idMal: \$idMal, type: ANIME) { id episodes mediaListEntry { progress status } } }"
        internal const val SAVE =
            "mutation (\$mediaId: Int, \$progress: Int, \$status: MediaListStatus) { " +
                "SaveMediaListEntry(mediaId: \$mediaId, progress: \$progress, status: \$status) { id progress status } }"
    }

    private suspend fun gql(token: String, query: String, vars: JsonObject = JsonObject(emptyMap())): JsonObject {
        val payload = buildJsonObject { put("query", query); put("variables", vars) }
        val req = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val root = Json.parseToJsonElement(http.call(req)).jsonObject
        val errors = root["errors"]
        if (errors != null && errors !is JsonNull) throw SyncHttpException(400, errors.toString().take(200))
        return root["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    suspend fun viewerName(token: String): String? =
        gql(token, VIEWER)["Viewer"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

    /** Maps a MAL id to the AniList media and the current list progress; null when AniList has no such show. */
    suspend fun lookup(token: String, malId: Int): Entry? {
        val data = try {
            gql(token, LOOKUP, buildJsonObject { put("idMal", malId) })
        } catch (e: SyncHttpException) {
            if (e.notFound) return null else throw e
        }
        val media = data["Media"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        val id = media["id"]?.jsonPrimitive?.intOrNull ?: return null
        val progress = media["mediaListEntry"]?.takeIf { it !is JsonNull }?.jsonObject?.get("progress")?.jsonPrimitive?.intOrNull
        return Entry(id, media["episodes"]?.jsonPrimitive?.intOrNull, progress)
    }

    suspend fun save(token: String, mediaId: Int, progress: Int, completed: Boolean) {
        gql(
            token, SAVE,
            buildJsonObject {
                put("mediaId", mediaId); put("progress", progress)
                put("status", if (completed) "COMPLETED" else "CURRENT")
            },
        )
    }
}
