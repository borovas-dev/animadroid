package com.borovas.animadroid.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SyncEntryPoint { fun sync(): SyncRepository }

/** Retries the queue once the device is online. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java).sync()
        repo.syncNow()
        return if (repo.state.value.pending > 0 && repo.state.value.services.values.any { it.connected }) Result.retry() else Result.success()
    }
}

internal class WorkSyncScheduler(private val context: Context) : SyncScheduler {
    override fun scheduleRetry() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork("tracker-sync", ExistingWorkPolicy.KEEP, req) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun syncRepository(@ApplicationContext context: Context, config: SyncConfig, client: OkHttpClient): SyncRepository {
        // The shared client has an HTTP cache; list state must never be served stale.
        val http = client.newBuilder().cache(null).build()
        return SyncRepositoryImpl(
            config, AniListClient(http), MalClient(http),
            KeystoreTokenStore(context), FileQueueStore(File(context.filesDir, "sync_queue.json")),
            WorkSyncScheduler(context), CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
