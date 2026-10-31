package com.borovas.animadroid.feature.recommend

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the model file in `filesDir/models`. Nothing here touches the network or WorkManager until a user action
 * (download) or until [state] is collected (only the Settings section and the recommender UI do, and only when the
 * recommender is enabled).
 */
@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: RecommendPrefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val importing = MutableStateFlow<ImportSnapshot?>(null)
    private val importError = MutableStateFlow<String?>(null)
    private val fileTick = MutableStateFlow(0)
    private var importJob: Job? = null

    private val dir get() = File(context.filesDir, "models").apply { mkdirs() }
    fun modelFile() = File(dir, ModelSpec.FILE_NAME)
    internal fun partialFile() = File(dir, ModelSpec.FILE_NAME + ".part")

    val state: Flow<ModelState> by lazy {
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos -> infos.firstOrNull()?.toSnapshot() }
        combine(work, importing, importError, fileTick) { w, imp, err, _ ->
            reduceModelState(w, imp, err, modelFile().takeIf { it.exists() }?.length())
        }.distinctUntilChanged()
    }

    /** Empty list means OK to start. */
    fun checkRequirements(): List<RequirementProblem> {
        val am = context.getSystemService(ActivityManager::class.java)
        val total = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        val free = StatFs(dir.path).availableBytes
        return evaluateRequirements(total, free, partialFile().takeIf { it.exists() }?.length() ?: 0)
    }

    /** Returns requirement problems (nothing is enqueued then). */
    suspend fun startDownload(): List<RequirementProblem> {
        val problems = checkRequirements()
        if (problems.isNotEmpty()) return problems
        importError.value = null
        val wifiOnly = prefs.wifiOnlyNow()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        return emptyList()
    }

    /** Cancels the download but keeps the partial file so it can be resumed. */
    fun cancelDownload() {
        importJob?.cancel()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        importJob?.cancel()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).pruneWork()
        modelFile().delete(); partialFile().delete()
        importError.value = null
        fileTick.value++
    }

    /** Copies a user-picked `.litertlm` file (e.g. downloaded in a browser) into app storage. */
    fun importFrom(uri: Uri) {
        importJob?.cancel()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        importError.value = null
        importJob = scope.launch {
            val tmp = partialFile()
            try {
                val total = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null } ?: 0L
                if (total in 1 until ModelSpec.MIN_IMPORT_BYTES) fail("That file is too small to be the model.");
                val free = StatFs(dir.path).availableBytes
                if (total > 0 && free < total + ModelSpec.FREE_SPACE_MARGIN_BYTES) {
                    fail("Not enough free storage to import: ${formatBytes(total)} needed, ${formatBytes(free)} available.")
                }
                importing.value = ImportSnapshot(0, total)
                var copied = 0L
                var lastEmit = 0L
                val input = context.contentResolver.openInputStream(uri) ?: fail("Could not open the selected file.")
                tmp.delete()
                input.use { ins -> tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); copied += n
                        if (copied - lastEmit > 8_000_000) { lastEmit = copied; importing.value = ImportSnapshot(copied, total) }
                    }
                } }
                if (copied < ModelSpec.MIN_IMPORT_BYTES) fail("That file is too small to be the model.")
                modelFile().delete()
                if (!tmp.renameTo(modelFile())) fail("Could not store the model file.")
            } catch (c: kotlinx.coroutines.CancellationException) {
                tmp.delete(); throw c
            } catch (t: Throwable) {
                tmp.delete()
                importError.value = (t as? ImportFailure)?.message ?: "Import failed: ${t.message}"
            } finally {
                importing.value = null
                fileTick.value++
            }
        }
    }

    private class ImportFailure(message: String) : Exception(message)
    private fun fail(message: String): Nothing = throw ImportFailure(message)

    private fun WorkInfo.toSnapshot() = DownloadSnapshot(
        phase = when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> WorkPhase.ENQUEUED
            WorkInfo.State.RUNNING -> WorkPhase.RUNNING
            WorkInfo.State.SUCCEEDED -> WorkPhase.SUCCEEDED
            WorkInfo.State.FAILED -> WorkPhase.FAILED
            WorkInfo.State.CANCELLED -> WorkPhase.CANCELLED
        },
        bytes = progress.getLong(ModelDownloadWorker.KEY_BYTES, partialFile().takeIf { it.exists() }?.length() ?: 0),
        total = ModelSpec.SIZE_BYTES,
        verifying = progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
        error = outputData.getString(ModelDownloadWorker.KEY_ERROR),
    )

    companion object {
        const val WORK_NAME = "gemma-model-download"

        /** Cancels any in-flight download without constructing the repository (used when the feature is switched off). */
        fun cancelDownload(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
