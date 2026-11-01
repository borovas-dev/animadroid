package com.borovas.animadroid.feature.recommend

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * Hosts the native LiteRT-LM engine in its own process (`:llm`). A native abort or segfault in the model runtime
 * then kills only this process; the app sees a binder death and reports an error instead of crashing.
 * The process exits when the last client unbinds, which also returns all of the model's native memory.
 */
class LlmWorkerService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private var engine: Engine? = null
    private var loadedPath: String? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("llm-worker").also { it.start() }
        messenger = Messenger(Handler(thread.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        runCatching { engine?.close() }
        engine = null
        // Free native memory for sure; nothing else lives in this process.
        Process.killProcess(Process.myPid())
    }

    private fun handle(msg: Message) {
        if (msg.what != LlmProtocol.MSG_GENERATE) return
        val reply = msg.replyTo ?: return
        val data = msg.data
        val out = Message.obtain(null, LlmProtocol.MSG_RESULT, msg.arg1, 0)
        try {
            out.data = Bundle().apply { putString(LlmProtocol.KEY_TEXT, generate(data)) }
        } catch (t: Throwable) {
            Log.w(TAG, "generate failed", t)
            out.what = LlmProtocol.MSG_ERROR
            out.data = Bundle().apply { putString(LlmProtocol.KEY_TEXT, t.message ?: t::class.java.simpleName) }
        }
        runCatching { reply.send(out) }
    }

    private fun generate(data: Bundle): String {
        val e = ensureLoaded(data.getString(LlmProtocol.KEY_MODEL)!!, data.getString(LlmProtocol.KEY_CACHE)!!, data.getBoolean(LlmProtocol.KEY_GPU))
        val config = ConversationConfig(
            systemInstruction = Contents.of(data.getString(LlmProtocol.KEY_SYSTEM)!!),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
        )
        return e.createConversation(config).use { conv ->
            val msg = conv.sendMessage(data.getString(LlmProtocol.KEY_PROMPT)!!)
            msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    private fun ensureLoaded(path: String, cacheDir: String, gpu: Boolean): Engine {
        val key = "$path|$gpu"
        engine?.let { if (loadedPath == key) return it else { runCatching { it.close() }; engine = null } }
        val backend: Backend = if (gpu) Backend.GPU() else Backend.CPU()
        val e = Engine(EngineConfig(modelPath = path, backend = backend, cacheDir = cacheDir))
        try { e.initialize() } catch (t: Throwable) { runCatching { e.close() }; throw t }
        Log.i(TAG, "Engine ready on ${backend::class.simpleName}")
        engine = e
        loadedPath = key
        return e
    }

    private companion object { const val TAG = "LlmWorkerService" }
}

internal object LlmProtocol {
    const val MSG_GENERATE = 1
    const val MSG_RESULT = 2
    const val MSG_ERROR = 3
    const val KEY_MODEL = "model"
    const val KEY_CACHE = "cache"
    const val KEY_SYSTEM = "system"
    const val KEY_PROMPT = "prompt"
    const val KEY_GPU = "gpu"
    const val KEY_TEXT = "text"
}
