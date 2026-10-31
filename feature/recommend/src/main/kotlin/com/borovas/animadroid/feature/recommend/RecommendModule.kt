package com.borovas.animadroid.feature.recommend

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface RecommendModule {
    @Binds fun engine(impl: LiteRtLlmEngine): LlmEngine
    @Binds fun cache(impl: RecommendPrefs): RecommendationCache
}

/** Entry hook for the app shell: call when the user switches the recommender off. */
object RecommendLifecycle {
    /** Stops any running model download and frees the native engine. Cheap; does not touch the network. */
    fun onDisabled(context: Context) {
        ModelRepository.cancelDownload(context)
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, RecommendEntryPoint::class.java).engine().release() }
    }
}
