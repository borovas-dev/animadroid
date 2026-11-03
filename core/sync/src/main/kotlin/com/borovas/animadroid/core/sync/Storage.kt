package com.borovas.animadroid.core.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Tokens(
    val access: String,
    val refresh: String? = null,
    /** Epoch ms; 0 = unknown. */
    val expiresAt: Long = 0,
    val username: String? = null,
)

interface TokenStore {
    fun get(service: SyncService): Tokens?
    fun put(service: SyncService, tokens: Tokens)
    fun clear(service: SyncService)
}

class InMemoryTokenStore : TokenStore {
    private val map = HashMap<SyncService, Tokens>()
    @Synchronized override fun get(service: SyncService) = map[service]
    @Synchronized override fun put(service: SyncService, tokens: Tokens) { map[service] = tokens }
    @Synchronized override fun clear(service: SyncService) { map.remove(service) }
}

/**
 * Tokens are AES-GCM encrypted with a non-exportable Android Keystore key and kept in a private SharedPreferences file
 * ("sync_secure"), which is neither part of the settings JSON nor of the app's JSON backups. If the key is gone
 * (e.g. restored device) decryption fails and the service simply reads as disconnected.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("sync_secure", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    override fun get(service: SyncService): Tokens? = runCatching {
        val raw = Base64.decode(prefs.getString(service.name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, IV_LEN)))
        json.decodeFromString(Tokens.serializer(), String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8))
    }.getOrNull()

    override fun put(service: SyncService, tokens: Tokens) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(json.encodeToString(Tokens.serializer(), tokens).toByteArray(Charsets.UTF_8))
        prefs.edit().putString(service.name, Base64.encodeToString(cipher.iv + enc, Base64.NO_WRAP)).apply()
    }

    override fun clear(service: SyncService) { prefs.edit().remove(service.name).apply() }

    private companion object {
        const val ALIAS = "animadroid_sync"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

/** One show with the furthest episode watched, still to be delivered to [services]. Keyed by MAL id so updates coalesce. */
@Serializable
data class PendingUpdate(
    val malId: Int,
    val episode: Int,
    val total: Int? = null,
    val services: Set<SyncService>,
)

interface QueueStore {
    fun load(): List<PendingUpdate>
    fun save(items: List<PendingUpdate>)
}

class InMemoryQueueStore(var items: List<PendingUpdate> = emptyList()) : QueueStore {
    override fun load() = items
    override fun save(items: List<PendingUpdate>) { this.items = items }
}

/** JSON file in filesDir; written atomically. Contains no secrets. */
class FileQueueStore(private val file: File) : QueueStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = kotlinx.serialization.builtins.ListSerializer(PendingUpdate.serializer())
    override fun load(): List<PendingUpdate> =
        runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())

    override fun save(items: List<PendingUpdate>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ser, items))
        tmp.renameTo(file)
    }
}
