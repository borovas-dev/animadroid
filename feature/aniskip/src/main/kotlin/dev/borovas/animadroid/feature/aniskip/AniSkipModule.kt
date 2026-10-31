package dev.borovas.animadroid.feature.aniskip

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.borovas.animadroid.core.model.SkipTimesRepository
import javax.inject.Singleton

/** Requires an [okhttp3.OkHttpClient] binding (from :core:network). */
@Module
@InstallIn(SingletonComponent::class)
abstract class AniSkipModule {
    @Binds
    @Singleton
    abstract fun bindSkipTimes(impl: AniSkipRepository): SkipTimesRepository
}
