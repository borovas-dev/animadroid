package com.borovas.animadroid.core.sync

import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** MyAnimeList API v2 (https://myanimelist.net/apiconfig/references/api/v2). */
class MalClient(
    private val http: OkHttpClient,
    private val apiBase: String = "https://api.myanimelist.net/v2",
    private val authBase: String = "https://myanimelist.net/v1/oauth2",
) {
    @Serializable
    data class Token(val access_token: String, val refresh_token: String? = null, val expires_in: Long = 3600)

    data class Entry(val episodes: Int?, val watched: Int?)

    private val json = Json { ignoreUnknownKeys = true }

    fun authorizeUrl(clientId: String, challenge: String, state: String, redirectUri: String): String =
        "$authBase/authorize?response_type=code&client_id=${enc(clientId)}&code_challenge=${enc(challenge)}" +
            "&code_challenge_method=plain&state=${enc(state)}&redirect_uri=${enc(redirectUri)}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun exchange(clientId: String, code: String, verifier: String, redirectUri: String): Token =
        token(
            FormBody.Builder().add("client_id", clientId).add("grant_type", "authorization_code")
                .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirectUri).build(),
        )

    suspend fun refresh(clientId: String, refreshToken: String): Token =
        token(FormBody.Builder().add("client_id", clientId).add("grant_type", "refresh_token").add("refresh_token", refreshToken).build())

    private suspend fun token(body: FormBody): Token =
        json.decodeFromString(Token.serializer(), http.call(Request.Builder().url("$authBase/token").post(body).build()))

    suspend fun userName(token: String): String? =
        json.parseToJsonElement(http.call(get(token, "$apiBase/users/@me"))).jsonObject["name"]?.jsonPrimitive?.contentOrNull

    suspend fun entry(token: String, malId: Int): Entry? {
        val root: JsonObject = try {
            json.parseToJsonElement(http.call(get(token, "$apiBase/anime/$malId?fields=num_episodes,my_list_status"))).jsonObject
        } catch (e: SyncHttpException) {
            if (e.notFound) return null else throw e
        }
        val total = root["num_episodes"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        val watched = (root["my_list_status"] as? JsonObject)?.get("num_episodes_watched")?.jsonPrimitive?.intOrNull
        return Entry(total, watched)
    }

    suspend fun update(token: String, malId: Int, watched: Int, completed: Boolean) {
        val body = FormBody.Builder()
            .add("status", if (completed) "completed" else "watching")
            .add("num_watched_episodes", watched.toString()).build()
        http.call(Request.Builder().url("$apiBase/anime/$malId/my_list_status").header("Authorization", "Bearer $token").patch(body).build())
    }

    private fun get(token: String, url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
}
