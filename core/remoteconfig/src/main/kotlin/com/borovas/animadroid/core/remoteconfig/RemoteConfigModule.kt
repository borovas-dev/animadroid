package com.borovas.animadroid.core.remoteconfig

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object RemoteConfigModule {
    private fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.0.0"

    @Provides @Singleton
    fun remoteConfig(@ApplicationContext context: Context, client: OkHttpClient): RemoteConfigRepository {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return RemoteConfigRepository(
            client = client,
            dir = File(context.filesDir, "remote-config"),
            bundledJson = {
                RemoteConfigRepository::class.java.classLoader!!.getResourceAsStream("providers.json")!!
                    .use { it.readBytes().decodeToString() }
            },
            publicKey = RemoteConfigKeys.publicKey(),
            allowUnsigned = debuggable || RemoteConfigKeys.ALLOW_UNSIGNED_CONFIG,
            appVersion = versionName(context),
        )
    }

    @Provides @Singleton
    fun updateChecker(@ApplicationContext context: Context, client: OkHttpClient): UpdateChecker =
        UpdateChecker(client, versionName(context), context.cacheDir)
}
