package com.borovas.animadroid.core.remoteconfig

import kotlinx.serialization.Serializable

/** Whole `providers.json` document. [revision] only ever increases; older documents are ignored (anti-rollback). */
@Serializable
data class RemoteConfig(
    val revision: Int = 0,
    val providers: Map<String, ProviderRemoteConfig> = emptyMap(),
)

/**
 * Per-provider overrides. Every field has a safe default, so a provider missing from the JSON (or a config that
 * could not be loaded at all) behaves as "enabled, no overrides".
 */
@Serializable
data class ProviderRemoteConfig(
    val enabled: Boolean = true,
    /** Primary first, then mirrors. */
    val baseUrls: List<String> = emptyList(),
    /** Informational: the site is known to be unreachable from mainland China. */
    val gfwBlocked: Boolean = false,
    /** Providers needing a newer app than this should be treated as disabled; see [RemoteConfigRepository.forProvider]. */
    val minAppVersion: String? = null,
    /** Free-form string constants (server name, XOR key, referer, regex ...). */
    val params: Map<String, String> = emptyMap(),
) {
    val baseUrl: String? get() = baseUrls.firstOrNull()?.trimEnd('/')
    fun baseUrlOr(default: String): String = baseUrl ?: default
    fun mirrorsOr(default: List<String>): List<String> = baseUrls.map { it.trimEnd('/') }.ifEmpty { default }
    fun param(key: String): String? = params[key]
    fun param(key: String, default: String): String = params[key] ?: default

    companion object { val DEFAULT = ProviderRemoteConfig() }
}
