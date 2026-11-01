package com.borovas.animadroid.feature.recommend

import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The model runtime died (native crash) instead of answering. */
class LlmProcessDiedException : Exception("The on-device model crashed on this device. The app is fine; recommendations are unavailable.")

/**
 * LiteRT-LM backed [LlmEngine]. 100% on-device. The native engine runs in a separate process ([LlmWorkerService]) so
 * a native crash cannot take the app down. CPU is the default backend: the GPU delegate aborts on several devices.
 * The worker is unbound after [IDLE_MS] of inactivity or on memory pressure, which also ends its process.
 */
@Singleton
class LiteRtLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelRepository,
) : LlmEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()
    private val pending = HashMap<Int, CancellableContinuation<String>>()
    private val replyTo = Messenger(Handler(Looper.getMainLooper()) { msg -> onReply(msg); true })
    private var worker: Messenger? = null
    private var bound = false
    private var connecting: CancellableContinuation<Messenger>? = null
    private var idleJob: Job? = null
    private var callbacksRegistered = false

    private val callbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) release() }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java") override fun onLowMemory() = release()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val m = Messenger(service)
            worker = m
            connecting?.let { connecting = null; if (it.isActive) it.resume(m) }
        }

        // The :llm process died (native crash) or was killed.
        override fun onServiceDisconnected(name: ComponentName) = onWorkerGone()
        override fun onBindingDied(name: ComponentName) = onWorkerGone()
        override fun onNullBinding(name: ComponentName) = onWorkerGone()
    }

    override suspend fun generate(system: String, prompt: String): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            idleJob?.cancel()
            try {
                val path = models.modelFile().takeIf { it.exists() }?.absolutePath
                    ?: throw IllegalStateException("The on-device model is not installed.")
                val cacheDir = context.cacheDir.resolve("litertlm").apply { mkdirs() }.path
                request(path, cacheDir, system, prompt)
            } finally {
                scheduleIdleRelease()
            }
        }
    }

    private suspend fun request(path: String, cacheDir: String, system: String, prompt: String): String {
        val target = connect()
        val id = ids.incrementAndGet()
        return suspendCancellableCoroutine { cont ->
            main.post {
                pending[id] = cont
                cont.invokeOnCancellation { main.post { pending.remove(id) } }
                val msg = Message.obtain(null, LlmProtocol.MSG_GENERATE, id, 0).apply {
                    replyTo = this@LiteRtLlmEngine.replyTo
                    data = Bundle().apply {
                        putString(LlmProtocol.KEY_MODEL, path)
                        putString(LlmProtocol.KEY_CACHE, cacheDir)
                        putString(LlmProtocol.KEY_SYSTEM, system)
                        putString(LlmProtocol.KEY_PROMPT, prompt)
                        putBoolean(LlmProtocol.KEY_GPU, false)
                    }
                }
                try { target.send(msg) } catch (t: Exception) { pending.remove(id); onWorkerGone(); }
            }
        }
    }

    private suspend fun connect(): Messenger {
        worker?.takeIf { bound }?.let { return it }
        if (!callbacksRegistered) { context.registerComponentCallbacks(callbacks); callbacksRegistered = true }
        return suspendCancellableCoroutine { cont ->
            main.post {
                connecting = cont
                val ok = context.bindService(Intent(context, LlmWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!ok) { connecting = null; cont.resumeWithException(IllegalStateException("Could not start the model process.")) }
                else bound = true
                cont.invokeOnCancellation { main.post { connecting = null } }
            }
        }
    }

    private fun onReply(msg: Message) {
        val cont = pending.remove(msg.arg1) ?: return
        val text = msg.data?.getString(LlmProtocol.KEY_TEXT).orEmpty()
        when (msg.what) {
            LlmProtocol.MSG_RESULT -> cont.resume(text)
            else -> cont.resumeWithException(IllegalStateException("Could not run the model: $text"))
        }
    }

    /** Main thread. The worker process is gone: fail everything in flight and drop the stale binding. */
    private fun onWorkerGone() {
        Log.w(TAG, "model process died")
        val dead = LlmProcessDiedException()
        connecting?.let { connecting = null; if (it.isActive) it.resumeWithException(dead) }
        pending.values.toList().also { pending.clear() }.forEach { if (it.isActive) it.resumeWithException(dead) }
        worker = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch { delay(IDLE_MS); release() }
    }

    override fun release() {
        main.post {
            idleJob?.cancel()
            if (pending.isEmpty()) {
                worker = null
                if (bound) { bound = false; runCatching { context.unbindService(connection) } }
            }
            if (callbacksRegistered) { runCatching { context.unregisterComponentCallbacks(callbacks) }; callbacksRegistered = false }
        }
    }

    private companion object {
        const val TAG = "LiteRtLlmEngine"
        const val IDLE_MS = 2 * 60 * 1000L
    }
}
