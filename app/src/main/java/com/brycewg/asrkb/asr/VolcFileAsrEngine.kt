package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 使用火山引擎"recognize/flash" API的非流式ASR引擎。
 * 行为：start()开始录制PCM；stop()完成并上传一个请求；仅调用onFinal。
 */
class VolcFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration) {

    companion object {
        private const val DEFAULT_FILE_RESOURCE = "volc.bigasr.auc_turbo"
        private const val TAG = "VolcFileAsrEngine"
    }

    // 火山引擎非流式：服务端上限 2h，本地稳妥限制为 1h
    override val maxRecordDurationMillis: Int = 60 * 60 * 1000

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val wav = pcmToWav(pcm)
            val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
            val json = buildRequestJson(b64)
            val reqBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(Prefs.DEFAULT_ENDPOINT)
                .addHeader("X-Api-App-Key", prefs.appKey)
                .addHeader("X-Api-Access-Key", prefs.accessKey)
                .addHeader("X-Api-Resource-Id", DEFAULT_FILE_RESOURCE)
                .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                .addHeader("X-Api-Sequence", "-1")
                .post(reqBody)
                .build()
            val t0 = System.nanoTime()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = resp.header("X-Api-Message") ?: resp.message
                    val detail = formatHttpDetail(msg)
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, resp.code, detail)
                    )
                    return
                }
                val bodyStr = resp.body?.string().orEmpty()
                val text = try {
                    val obj = JSONObject(bodyStr)
                    if (obj.has("result")) {
                        obj.getJSONObject("result").optString("text", "")
                    } else ""
                } catch (_: Throwable) { "" }
                if (text.isNotBlank()) {
                    val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
                    listener.onFinal(text)
                } else {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                }
            }
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    /**
     * 构建火山引擎 API 请求体
     */
    private fun buildRequestJson(base64Audio: String): String {
        val user = JSONObject().apply {
            put("uid", prefs.appKey)
        }
        val audio = JSONObject().apply {
            put("data", base64Audio)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", prefs.volcDdcEnabled)

            // Pro功能：动态注入个性化热词和上下文信息
            try {
                val volcContext = com.brycewg.asrkb.asr.ProAsrHelper.buildVolcContext(context)
                if (volcContext != null) {
                    val corpus = JSONObject().apply {
                        put("context", volcContext)
                    }
                    put("corpus", corpus)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to inject Volc context", t)
            }
        }
        return JSONObject().apply {
            put("user", user)
            put("audio", audio)
            put("request", request)
        }.toString()
    }
}
