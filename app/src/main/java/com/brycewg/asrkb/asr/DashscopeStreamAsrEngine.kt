package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DashScope 实时流式 ASR（WebSocket + JSON 事件）实现。
 *
 * 协议（与 OpenAI Realtime 风格一致）：
 * - 连接：wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=<model>
 * - 鉴权：Authorization: Bearer <apiKey>; OpenAI-Beta: realtime=v1
 * - 会话：发送 type=session.update，指定 input_audio_format=pcm、sample_rate 等
 * - 音频：200ms 切片，base64 编码后 type=input_audio_buffer.append
 * - 结束：发送 type=input_audio_buffer.commit，等待最终结果
 */
class DashscopeStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "DashscopeStreamAsrEngine"
        private const val DEFAULT_WS_BASE = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val wsReady = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private var audioJob: Job? = null
    private var closeJob: Job? = null
    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val previewBuffer = StringBuilder()

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return
        }
        if (prefs.dashApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_dashscope_key))
            return
        }
        running.set(true)
        wsReady.set(false)
        synchronized(prebufferLock) { prebuffer.clear() }
        openWebSocketAndStartAudio()
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        try { audioJob?.cancel() } catch (t: Throwable) { Log.w(TAG, "Cancel audio job failed", t) }
        audioJob = null

        closeJob?.cancel()
        closeJob = scope.launch(Dispatchers.IO) {
            try { sendCommitIfPossible() } catch (t: Throwable) { Log.w(TAG, "Commit failed on stop", t) }
            var left = 8000L
            while (left > 0 && ws != null) { delay(50); left -= 50 }
            try { ws?.close(1000, "stop") } catch (_: Throwable) {}
            ws = null
            wsReady.set(false)
        }
    }

    private fun openWebSocketAndStartAudio() {
        previewBuffer.setLength(0)
        startCaptureAndSendAudio()

        val apiKey = prefs.dashApiKey
        // 固定使用流式模型：qwen3-asr-flash-realtime
        val modelParam = "qwen3-asr-flash-realtime"
        val url = "$DEFAULT_WS_BASE?model=" + java.net.URLEncoder.encode(modelParam, "UTF-8")

        val req = Request.Builder()
            .url(url)
            .headers(
                Headers.headersOf(
                    "Authorization", "Bearer $apiKey",
                    "OpenAI-Beta", "realtime=v1"
                )
            )
            .build()

        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened, sending session.update")
                try {
                    val sessionUpdate = buildSessionUpdateJson()
                    webSocket.send(sessionUpdate)
                    wsReady.set(true)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to send session.update", t)
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                    stop()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerEvent(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                try { listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")) } catch (_: Throwable) {}
                running.set(false)
                wsReady.set(false)
                ws = null
            }
        })
    }

    private fun startCaptureAndSendAudio() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = 200
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = chunkMillis
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            val vadDetector = if (isVadAutoStopEnabled(context, prefs))
                VadDetector(context, sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
            else null

            val maxFrames = (2000 / chunkMillis).coerceAtLeast(1)

            try {
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get()) return@collect

                    if (vadDetector?.shouldStop(audioChunk, audioChunk.size) == true) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        try { listener.onStopped() } catch (t: Throwable) { Log.e(TAG, "Failed to notify stopped", t) }
                        try { sendCommitIfPossible() } catch (t: Throwable) { Log.w(TAG, "Commit failed on VAD stop", t) }
                        stop()
                        return@collect
                    }

                    if (!wsReady.get()) {
                        synchronized(prebufferLock) {
                            prebuffer.addLast(audioChunk)
                            while (prebuffer.size > maxFrames) prebuffer.removeFirst()
                        }
                    } else {
                        var flushed: Array<ByteArray>? = null
                        synchronized(prebufferLock) {
                            if (prebuffer.isNotEmpty()) {
                                flushed = prebuffer.toTypedArray()
                                prebuffer.clear()
                            }
                        }
                        val socket = ws
                        if (socket != null) {
                            flushed?.forEach { b ->
                                try { socket.send(buildAudioAppendEvent(b)) } catch (t: Throwable) { Log.e(TAG, "Failed to send buffered audio frame", t) }
                            }
                            try { socket.send(buildAudioAppendEvent(audioChunk)) } catch (t: Throwable) { Log.e(TAG, "Failed to send audio frame", t) }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Audio streaming cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio streaming failed: ${t.message}", t)
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                }
            }
        }
    }

    private fun buildSessionUpdateJson(): String {
        val lang = prefs.dashLanguage.trim()
        val session = JSONObject().apply {
            put("modalities", org.json.JSONArray().put("text"))
            put("input_audio_format", "pcm")
            put("sample_rate", sampleRate)
            put("input_audio_transcription", JSONObject().apply { if (lang.isNotEmpty()) put("language", lang) })
            put("turn_detection", JSONObject.NULL)
        }
        val event = JSONObject().apply {
            put("type", "session.update")
            put("session", session)
        }
        return event.toString()
    }

    private fun buildAudioAppendEvent(buf: ByteArray): String {
        val b64 = try {
            Base64.getEncoder().encodeToString(buf)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)
        }
        return JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        }.toString()
    }

    private fun sendCommitIfPossible() {
        val socket = ws ?: return
        try {
            socket.send(JSONObject().apply { put("type", "input_audio_buffer.commit") }.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send commit event", t)
        }
    }

    private fun handleServerEvent(json: String) {
        try {
            val o = JSONObject(json)
            if (o.has("error")) {
                val err = o.optJSONObject("error")?.optString("message") ?: o.optString("error")
                if (err.isNotBlank()) listener.onError(err)
                return
            }
            val type = o.optString("type")
            if (type.contains("delta", true) || type.contains("partial", true)) {
                val delta = o.optJSONObject("delta")
                val txt = when {
                    delta != null && delta.has("output_text") -> delta.optString("output_text")
                    delta != null && delta.has("text") -> delta.optString("text")
                    o.has("text") -> o.optString("text")
                    else -> ""
                }
                if (txt.isNotBlank() && running.get()) {
                    // 语义区分：
                    // - 类型包含 "delta"：服务端倾向于返回增量片段，做“重叠去重 + 追加”
                    // - 类型包含 "partial"：多数实现会返回“当前完整中间文本”，直接替换
                    val isDelta = type.contains("delta", true) && !type.contains("partial", true)
                    if (isDelta) {
                        val merged = mergeWithOverlapDedup(previewBuffer.toString(), txt)
                        previewBuffer.setLength(0)
                        previewBuffer.append(merged)
                    } else {
                        previewBuffer.setLength(0)
                        previewBuffer.append(txt)
                    }
                    try { listener.onPartial(previewBuffer.toString()) } catch (t: Throwable) { Log.e(TAG, "Failed to notify partial", t) }
                }
                return
            }

            val completed = type.contains("completed", true) || type.contains("done", true) ||
                type.equals("response.completed", true) || type.equals("input_audio_transcription.completed", true)
            if (completed || type.isBlank()) {
                val finalText = previewBuffer.toString().trim()
                try { listener.onFinal(finalText) } catch (t: Throwable) { Log.e(TAG, "Failed to notify final", t) }
                running.set(false)
                ws = null
                wsReady.set(false)
                return
            }

            val fallback = o.optString("text").ifBlank { o.optJSONObject("response")?.optString("output_text").orEmpty() }
            if (fallback.isNotBlank() && running.get()) {
                // 无明确类型时，保守采用“重叠去重 + 追加”，避免重复
                val merged = mergeWithOverlapDedup(previewBuffer.toString(), fallback)
                previewBuffer.setLength(0)
                previewBuffer.append(merged)
                listener.onPartial(previewBuffer.toString())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse WS event: $json", t)
        }
    }

    /**
     * 将 stable 与 tail 合并，移除边界处重复的前后缀重叠。
     * 例如：stable="你好世界。", tail="世界。我们来了" => "你好世界。我们来了"。
     */
    private fun mergeWithOverlapDedup(stable: String, tail: String): String {
        if (stable.isEmpty()) return tail
        if (tail.isEmpty()) return stable
        val max = minOf(stable.length, tail.length)
        var k = max
        while (k > 0) {
            if (stable.regionMatches(stable.length - k, tail, 0, k)) {
                return stable + tail.substring(k)
            }
            k--
        }
        return stable + tail
    }
}
