package com.borovas.animadroid.core.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.SettingsRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

internal val SettingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        SettingsJson.decodeFromString(AppSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: Exception) {
        throw CorruptionException("Cannot read settings", e)
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(SettingsJson.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}

@Singleton
class DataStoreSettingsRepository internal constructor(
    private val store: DataStore<AppSettings>,
) : SettingsRepository {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        DataStoreFactory.create(
            serializer = AppSettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
            produceFile = { File(context.filesDir, "datastore/settings.json") },
        ),
    )

    override val settings: Flow<AppSettings> get() = store.data

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }

    suspend fun exportJson(): String =
        SettingsJson.encodeToString(AppSettings.serializer(), store.data.first())

    suspend fun importJson(json: String) {
        val imported = SettingsJson.decodeFromString(AppSettings.serializer(), json)
        store.updateData { imported }
    }

    companion object {
        fun forStore(store: DataStore<AppSettings>) = DataStoreSettingsRepository(store)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    @Singleton
    abstract fun bindSettings(impl: DataStoreSettingsRepository): SettingsRepository
}
