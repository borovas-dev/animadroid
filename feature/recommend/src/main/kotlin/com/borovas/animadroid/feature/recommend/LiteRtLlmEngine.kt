package com.borovas.animadroid.feature.recommend

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM backed [LlmEngine]. 100% on-device. The native engine is created lazily on first use (off the main
 * thread), tries the GPU backend first and falls back to CPU, and is closed after [IDLE_MS] of inactivity or when the
 * system signals memory pressure.
 */
@Singleton
class LiteRtLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelRepository,
) : LlmEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var idleJob: Job? = null
    private var callbacksRegistered = false

    private val callbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) release() }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java") override fun onLowMemory() = release()
    }

    override suspend fun generate(system: String, prompt: String): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            idleJob?.cancel()
            try {
                val e = ensureLoaded()
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                )
                e.createConversation(config).use { conv ->
                    val msg = conv.sendMessage(prompt)
                    msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                }
            } finally {
                scheduleIdleRelease()
            }
        }
    }

    private fun ensureLoaded(): Engine {
        engine?.let { return it }
        val path = models.modelFile().takeIf { it.exists() }?.absolutePath
            ?: throw IllegalStateException("The on-device model is not installed.")
        if (!callbacksRegistered) { context.registerComponentCallbacks(callbacks); callbacksRegistered = true }
        val cacheDir = context.cacheDir.resolve("litertlm").apply { mkdirs() }.path
        var lastError: Throwable? = null
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            try {
                val e = Engine(EngineConfig(modelPath = path, backend = backend, cacheDir = cacheDir))
                try {
                    e.initialize()
                } catch (t: Throwable) { runCatching { e.close() }; throw t }
                Log.i(TAG, "Engine ready on ${backend::class.simpleName}")
                engine = e
                return e
            } catch (t: Throwable) {
                Log.w(TAG, "Backend ${backend::class.simpleName} failed: ${t.message}")
                lastError = t
            }
        }
        throw IllegalStateException("Could not load the model: ${lastError?.message}", lastError)
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch { delay(IDLE_MS); release() }
    }

    override fun release() {
        scope.launch {
            mutex.withLock {
                idleJob?.cancel()
                runCatching { engine?.close() }
                engine = null
                if (callbacksRegistered) { runCatching { context.unregisterComponentCallbacks(callbacks) }; callbacksRegistered = false }
            }
        }
    }

    private companion object {
        const val TAG = "LiteRtLlmEngine"
        const val IDLE_MS = 2 * 60 * 1000L
    }
}
