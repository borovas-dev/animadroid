package com.borovas.animadroid.feature.recommend

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RecommendEntryPoint {
    fun okHttpClient(): OkHttpClient
    fun prefs(): RecommendPrefs
    fun engine(): LlmEngine
}

/**
 * Resumable (HTTP Range) foreground download of the Gemma model into a `.part` file in filesDir/models, then SHA-256 + size
 * verification and an atomic rename. Network constraint (Wi-Fi only by default) is set by [ModelRepository].
 * Cancelling keeps the partial file; transient IO errors retry with backoff.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val ep = EntryPointAccessors.fromApplication(context.applicationContext, RecommendEntryPoint::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Downloading on-device model", 0, indeterminate = true)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dir = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val target = File(dir, ModelSpec.FILE_NAME)
        val part = File(dir, ModelSpec.FILE_NAME + ".part")
        if (target.exists() && target.length() == ModelSpec.SIZE_BYTES) return@withContext Result.success()
        try {
            runCatching { setForeground(getForegroundInfo()) }
            download(part)
            ensureActive()
            if (part.length() != ModelSpec.SIZE_BYTES) return@withContext fail(part, "Downloaded file has the wrong size; it was discarded.", keep = false)
            setProgress(Data.Builder().putLong(KEY_BYTES, part.length()).putBoolean(KEY_VERIFYING, true).build())
            runCatching { setForeground(foreground("Verifying model", 100, indeterminate = true)) }
            val sha = sha256(part)
            if (!sha.equals(ModelSpec.SHA256, ignoreCase = true)) return@withContext fail(part, "Checksum mismatch; the file was discarded. Try again.", keep = false)
            target.delete()
            if (!part.renameTo(target)) return@withContext fail(part, "Could not store the model file.", keep = true)
            Result.success()
        } catch (e: AuthFailure) {
            fail(part, e.message.orEmpty(), keep = true)
        } catch (e: IOException) {
            if (runAttemptCount >= MAX_ATTEMPTS) fail(part, "Network error: ${e.message}. Tap download to resume.", keep = true)
            else Result.retry()
        }
    }

    private fun fail(part: File, message: String, keep: Boolean): Result {
        if (!keep) part.delete()
        return Result.failure(Data.Builder().putString(KEY_ERROR, message).build())
    }

    private suspend fun download(part: File) {
        val token = ep.prefs().hfTokenNow()
        var offset = part.takeIf { it.exists() }?.length() ?: 0L
        if (offset > ModelSpec.SIZE_BYTES) { part.delete(); offset = 0 }
        if (offset == ModelSpec.SIZE_BYTES) return
        val req = Request.Builder().url(ModelSpec.URL)
            .apply {
                if (offset > 0) header("Range", "bytes=$offset-")
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.build()
        ep.okHttpClient().newCall(req).execute().use { resp ->
            when {
                resp.code == 416 -> { part.delete(); throw IOException("Server rejected resume; restarting") }
                resp.code == 401 || resp.code == 403 -> throw AuthFailure("Hugging Face refused the download (HTTP ${resp.code}). Add a valid access token in settings.")
                !resp.isSuccessful -> throw IOException("HTTP ${resp.code}")
            }
            val resumed = resp.code == 206
            if (!resumed) { offset = 0 }
            val body = resp.body
            RandomAccessFile(part, "rw").use { raf ->
                if (resumed) raf.seek(offset) else raf.setLength(0)
                var done = offset
                var lastReport = 0L
                val buf = ByteArray(256 * 1024)
                body.byteStream().use { ins ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 700) {
                            lastReport = now
                            setProgress(Data.Builder().putLong(KEY_BYTES, done).build())
                            runCatching { setForeground(foreground("Downloading on-device model", (done * 100 / ModelSpec.SIZE_BYTES).toInt(), false)) }
                        }
                    }
                }
                setProgress(Data.Builder().putLong(KEY_BYTES, done).build())
            }
        }
    }

    private class AuthFailure(message: String) : Exception(message)

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foreground(title: String, percent: Int, indeterminate: Boolean): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_BYTES = "bytes"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "model_download"
        private const val NOTIFICATION_ID = 4711
        private const val MAX_ATTEMPTS = 6
    }
}
