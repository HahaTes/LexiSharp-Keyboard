package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 使用阿里云百炼（DashScope）临时上传 + 生成接口的非流式 ASR 引擎。
 */
class DashscopeFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration) {

    companion object {
        private const val TAG = "DashscopeFileAsrEngine"
    }

    // DashScope：官方限制 3 分钟
    override val maxRecordDurationMillis: Int = 3 * 60 * 1000

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.dashApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_dashscope_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val wav = pcmToWav(pcm)
            val tmp = File.createTempFile("asr_dash_", ".wav", context.cacheDir)
            FileOutputStream(tmp).use { it.write(wav) }

            val apiKey = prefs.dashApiKey
            // 固定使用非流式模型：qwen3-asr-flash
            val model = Prefs.DEFAULT_DASH_MODEL

            val policyUrl = "https://dashscope.aliyuncs.com/api/v1/uploads?action=getPolicy&model=" +
                java.net.URLEncoder.encode(model, "UTF-8")
            val policyReq = Request.Builder()
                .url(policyUrl)
                .get()
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            val policyResp = http.newCall(policyReq).execute()
            val policyBody = policyResp.body?.string().orEmpty()
            if (!policyResp.isSuccessful) {
                val detail = formatHttpDetail(policyResp.message)
                listener.onError(
                    context.getString(
                        R.string.error_dash_getpolicy_failed_http,
                        policyResp.code,
                        detail
                    )
                )
                return
            }
            val policy = try { JSONObject(policyBody).optJSONObject("data") } catch (_: Throwable) { null }
            if (policy == null) {
                listener.onError(context.getString(R.string.error_dash_no_data))
                return
            }

            val uploadDir = policy.optString("upload_dir").trim('/', ' ')
            val key = if (uploadDir.isNotEmpty()) {
                "$uploadDir/" + tmp.nameIfExists()
            } else tmp.nameIfExists()
            val ossHost = policy.optString("upload_host")
            if (ossHost.isBlank()) {
                listener.onError(context.getString(R.string.error_dash_missing_upload_host))
                return
            }
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("OSSAccessKeyId", policy.optString("oss_access_key_id"))
                .addFormDataPart("Signature", policy.optString("signature"))
                .addFormDataPart("policy", policy.optString("policy"))
                .apply {
                    if (policy.has("x_oss_object_acl"))
                        addFormDataPart("x-oss-object-acl", policy.optString("x_oss_object_acl"))
                    if (policy.has("x_oss_forbid_overwrite"))
                        addFormDataPart("x-oss-forbid-overwrite", policy.optString("x_oss_forbid_overwrite"))
                    if (policy.has("x_oss_security_token"))
                        addFormDataPart("x-oss-security-token", policy.optString("x_oss_security_token"))
                }
                .addFormDataPart("key", key)
                .addFormDataPart("success_action_status", "200")
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    tmp.asRequestBody("audio/wav".toMediaType())
                )
                .build()
            val ossReq = Request.Builder()
                .url(ossHost)
                .post(multipart)
                .build()
            val ossResp = http.newCall(ossReq).execute()
            if (!ossResp.isSuccessful) {
                val err = ossResp.body?.string().orEmpty().ifBlank { ossResp.message }
                val detail = formatHttpDetail(err)
                listener.onError(
                    context.getString(R.string.error_oss_upload_failed_http, ossResp.code, detail)
                )
                return
            }
            val ossUrl = "oss://$key"

            val asrOptions = JSONObject().apply {
                put("enable_lid", false)
                put("enable_itn", true)
                val lang = prefs.dashLanguage.trim()
                if (lang.isNotEmpty()) put("language", lang)
            }
            val systemMsg = JSONObject().apply {
                val baseCtx = prefs.dashPrompt.trim()
                // Pro功能：动态拼接个性化热词和上下文信息
                val ctx = try {
                    com.brycewg.asrkb.asr.ProAsrHelper.buildPromptWithContext(context, baseCtx)
                } catch (t: Throwable) {
                    baseCtx
                }
                put("text", ctx)
            }
            val userMsg = JSONObject().apply { put("audio", ossUrl) }
            val bodyObj = JSONObject().apply {
                put("model", model)
                put("input", JSONObject().apply {
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", org.json.JSONArray().apply { put(systemMsg) })
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", org.json.JSONArray().apply { put(userMsg) })
                        })
                    })
                })
                put("parameters", JSONObject().apply {
                    put("asr_options", asrOptions)
                })
            }
            val genReq = Request.Builder()
                .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-DashScope-OssResourceResolve", "enable")
                .post(bodyObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val t0 = System.nanoTime()
            val genResp = http.newCall(genReq).execute()
            val genStr = genResp.body?.string().orEmpty()
            if (!genResp.isSuccessful) {
                val detail = formatHttpDetail(genResp.message)
                listener.onError(
                    context.getString(R.string.error_asr_request_failed_http, genResp.code, detail)
                )
                return
            }
            val text = parseDashscopeText(genStr)
            if (text.isNotBlank()) {
                val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
                listener.onFinal(text)
            } else {
                listener.onError(context.getString(R.string.error_asr_empty_result))
            }
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    /**
     * 从 DashScope 响应体中解析转写文本
     */
    private fun parseDashscopeText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            val output = obj.optJSONObject("output") ?: return ""
            val choices = output.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
            val content = msg.optJSONArray("content") ?: return ""
            var txt = ""
            for (i in 0 until content.length()) {
                val it = content.optJSONObject(i) ?: continue
                if (it.has("text")) {
                    txt = it.optString("text").trim()
                    if (txt.isNotEmpty()) break
                }
            }
            txt
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse DashScope response", t)
            ""
        }
    }

    /**
     * 获取文件名的安全方法
     */
    private fun File.nameIfExists(): String {
        return try { name } catch (t: Throwable) {
            Log.e(TAG, "Failed to get file name", t)
            "upload.wav"
        }
    }
}
