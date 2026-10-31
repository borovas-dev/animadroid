package com.borovas.animadroid.core.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.borovas.animadroid.core.model.LibraryRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseBindsModule {
    @Binds
    @Singleton
    abstract fun bindLibrary(impl: RoomLibraryRepository): LibraryRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseProvidesModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AnimadroidDatabase =
        Room.databaseBuilder<AnimadroidDatabase>(context, context.getDatabasePath("animadroid.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
}
