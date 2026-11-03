package com.borovas.animadroid

import com.borovas.animadroid.core.sync.SyncConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Tracker client ids come from Gradle properties via BuildConfig; blank means "not configured". */
@Module
@InstallIn(SingletonComponent::class)
object SyncConfigModule {
    @Provides
    fun syncConfig() = SyncConfig(BuildConfig.ANILIST_CLIENT_ID, BuildConfig.MAL_CLIENT_ID)
}
