package com.borovas.animadroid.feature.dubbing

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DubbingModule {
    @Binds abstract fun bindDubEngine(impl: SystemTtsDubEngine): DubEngine
}
