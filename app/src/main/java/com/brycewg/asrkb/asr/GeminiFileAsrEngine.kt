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
import java.util.concurrent.TimeUnit

/**
 * 使用 Google Gemini generateContent 的非流式 ASR 引擎（通过提示词进行转录）。
 */
class GeminiFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration) {

    companion object {
        private const val TAG = "GeminiFileAsrEngine"
        private const val GEM_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val DEFAULT_GEM_PROMPT = "请将以下音频逐字转写为文本，不要输出解释或前后缀。输入语言可能是中文、英文或其他语言"
    }

    // Gemini：官方约 9.5 小时，本地限制为 4 小时
    override val maxRecordDurationMillis: Int = 4 * 60 * 60 * 1000

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.getGeminiApiKeys().isEmpty()) {
            listener.onError(context.getString(R.string.error_missing_gemini_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val wav = pcmToWav(pcm)
            val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
            val apiKeys = prefs.getGeminiApiKeys()
            val apiKey = apiKeys.random()
            val model = prefs.gemModel.ifBlank { Prefs.DEFAULT_GEM_MODEL }
            val basePrompt = prefs.gemPrompt.ifBlank { DEFAULT_GEM_PROMPT }
            // Pro功能：动态拼接个性化热词和上下文信息
            val prompt = try {
                com.brycewg.asrkb.asr.ProAsrHelper.buildPromptWithContext(context, basePrompt)
            } catch (t: Throwable) {
                basePrompt
            }

            val body = buildGeminiRequestBody(b64, prompt, model)
            val req = Request.Builder()
                .url("${GEM_BASE}/models/${model}:generateContent?key=${apiKey}")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val t0 = System.nanoTime()
            val resp = http.newCall(req).execute()
            resp.use { r ->
                val str = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val hint = extractGeminiError(str)
                    val detail = formatHttpDetail(r.message, hint)
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    return
                }
                val text = parseGeminiText(str)
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
     * 构建 Gemini API 请求体
     */
    private fun buildGeminiRequestBody(base64Wav: String, prompt: String, model: String): String {
        val inlineAudio = JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "audio/wav")
                put("data", base64Wav)
            })
        }
        val systemInstruction = JSONObject().apply {
            put("parts", org.json.JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            })
        }
        val user = JSONObject().apply {
            put("role", "user")
            put("parts", org.json.JSONArray().apply {
                put(inlineAudio)
            })
        }
        return JSONObject().apply {
            put("system_instruction", systemInstruction)
            put("contents", org.json.JSONArray().apply { put(user) })
            put("generation_config", JSONObject().apply {
                put("temperature", 0)
                if (prefs.geminiDisableThinking) {
                    // 根据模型类型设置合适的 thinkingBudget
                    val budget = when {
                        model.contains("2.5-pro", ignoreCase = true) -> 128 // Pro 最低 128
                        model.contains("2.5-flash", ignoreCase = true) -> 0  // Flash 可以为 0
                        else -> 0 // 其他情况默认为 0
                    }
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", budget)
                    })
                }
            })
        }.toString()
    }

    /**
     * 从响应体中提取错误信息
     */
    private fun extractGeminiError(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            if (o.has("error")) {
                val e = o.optJSONObject("error")
                val msg = e?.optString("message").orEmpty()
                val status = e?.optString("status").orEmpty()
                listOf(status, msg).filter { it.isNotBlank() }.joinToString(": ")
            } else body.take(200).trim()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Gemini error", t)
            body.take(200).trim()
        }
    }

    /**
     * 从 Gemini 响应中解析转写文本
     */
    private fun parseGeminiText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            val cands = o.optJSONArray("candidates") ?: return ""
            if (cands.length() == 0) return ""
            val cand0 = cands.optJSONObject(0) ?: return ""
            val content = cand0.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            var txt = ""
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val t = p.optString("text").trim()
                if (t.isNotEmpty()) { txt = t; break }
            }
            txt
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Gemini response", t)
            ""
        }
    }
}
