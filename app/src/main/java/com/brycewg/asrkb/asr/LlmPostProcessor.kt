package com.brycewg.asrkb.asr

import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 格式的 ASR 文本后处理器，用于文本清理和 AI 编辑。
 * 使用与 Chat Completions 兼容的 API，并在存在简单字段时回退使用。
 */
class LlmPostProcessor(private val client: OkHttpClient? = null) {
  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  companion object {
    private const val TAG = "LlmPostProcessor"
    private const val DEFAULT_TIMEOUT_SECONDS = 30L
  }

  /**
   * LLM 测试结果
   */
  data class LlmTestResult(
    val ok: Boolean,
    val httpCode: Int? = null,
    val message: String? = null,
    val contentPreview: String? = null
  )

  /**
   * 统一的底层调用结果
   */
  private data class RawCallResult(
    val ok: Boolean,
    val httpCode: Int? = null,
    val text: String? = null,
    val error: String? = null
  )

  /**
   * 标准化的上层处理结果，用于向调用方传递是否成功以及返回文本。
   */
  data class LlmProcessResult(
    val ok: Boolean,
    val text: String,
    val errorMessage: String? = null,
    val httpCode: Int? = null
  )

  /**
   * LLM 请求配置
   */
  private data class LlmRequestConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val temperature: Double
  )

  /**
   * 从 Prefs 获取活动的 LLM 配置
   */
  private fun getActiveConfig(prefs: Prefs): LlmRequestConfig {
    val active = prefs.getActiveLlmProvider()
    return LlmRequestConfig(
      apiKey = active?.apiKey ?: prefs.llmApiKey,
      endpoint = active?.endpoint ?: prefs.llmEndpoint,
      model = active?.model ?: prefs.llmModel,
      temperature = (active?.temperature ?: prefs.llmTemperature).toDouble()
    )
  }

  /**
   * 解析 URL，自动添加 /chat/completions 后缀
   */
  private fun resolveUrl(base: String): String {
    val raw = base.trim()
    if (raw.isEmpty()) return Prefs.DEFAULT_LLM_ENDPOINT.trimEnd('/') + "/chat/completions"
    val b = raw.trimEnd('/')
    // 要求用户填写完整 URL（包含 http/https），不再自动补全协议
    val hasScheme = b.startsWith("http://", true) || b.startsWith("https://", true)
    if (!hasScheme) throw IllegalArgumentException("Endpoint must start with http:// or https://")

    // 如果已直接指向 chat/completions 或 responses，则原样使用
    if (b.endsWith("/chat/completions")) return b

    // 若以 /v1 结尾，自动补全到 chat/completions
    if (b.endsWith("/v1", true)) return "$b/chat/completions"

    // 其他情况：要求至少包含 /v1 或已是完整的 chat 路径
    if (b.contains("/v1/")) return b // 允许自定义 /v1/* 完整路径

    throw IllegalArgumentException("Endpoint must include /v1 or end with /chat/completions")
  }

  /**
   * 构建标准的 OpenAI Chat Completions 请求
   *
   * @param config LLM 配置
   * @param messages 消息列表（JSONArray）
   * @return 构建好的 Request 对象
   */
  private fun buildRequest(
    config: LlmRequestConfig,
    messages: JSONArray
  ): Request {
    val url = resolveUrl(config.endpoint)

    val reqJson = JSONObject().apply {
      put("model", config.model)
      put("temperature", config.temperature)
      put("messages", messages)
    }.toString()

    val body = reqJson.toRequestBody(jsonMedia)
    val builder = Request.Builder()
      .url(url)
      .addHeader("Content-Type", "application/json")
      .post(body)

    if (config.apiKey.isNotBlank()) {
      builder.addHeader("Authorization", "Bearer ${config.apiKey}")
    }

    return builder.build()
  }

  /**
   * 获取或创建 OkHttpClient
   */
  private fun getHttpClient(): OkHttpClient {
    return client ?: OkHttpClient.Builder()
      .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .build()
  }

  /**
   * 从响应 JSON 中提取文本内容
   *
   * 支持标准 OpenAI 格式和自定义 output_text 字段
   *
   * @param responseJson 响应的 JSON 字符串
   * @param fallback 提取失败时的回退文本
   * @return 提取的文本或 fallback
   */
  private fun extractTextFromResponse(responseJson: String, fallback: String): String {
    return try {
      val obj = JSONObject(responseJson)
      when {
        obj.has("choices") -> {
          val choices = obj.getJSONArray("choices")
          if (choices.length() > 0) {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            msg?.optString("content")?.ifBlank { fallback } ?: fallback
          } else fallback
        }
        obj.has("output_text") -> obj.optString("output_text", fallback)
        else -> fallback
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to extract text from response", t)
      fallback
    }
  }

  /**
   * 复用的底层 Chat 调用：构建请求、执行并解析文本。
   * 需确保在非主线程调用。
   */
  private fun performChat(config: LlmRequestConfig, messages: JSONArray): RawCallResult {
    val req = try {
      buildRequest(config, messages)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to build request", t)
      return RawCallResult(false, error = "Build request failed: ${t.message}")
    }

    val http = getHttpClient()
    val resp = try {
      http.newCall(req).execute()
    } catch (t: Throwable) {
      Log.e(TAG, "HTTP request failed", t)
      return RawCallResult(false, error = t.message ?: "Network error")
    }

    if (!resp.isSuccessful) {
      val code = resp.code
      val err = try { resp.body?.string() } catch (_: Throwable) { null } finally { resp.close() }
      return RawCallResult(false, httpCode = code, error = err?.take(256) ?: "HTTP $code")
    }

    val text = try {
      val body = resp.body?.string()
      if (body == null) {
        Log.w(TAG, "Response body is null")
        return RawCallResult(false, error = "Empty body")
      }
      val extracted = extractTextFromResponse(body, "")
      if (extracted.isBlank()) {
        return RawCallResult(false, error = "Empty result")
      }
      extracted
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to parse response", t)
      return RawCallResult(false, error = t.message ?: "Parse error")
    } finally {
      try {
        resp.close()
      } catch (closeErr: Throwable) {
        Log.w(TAG, "Close response failed", closeErr)
      }
    }

    return RawCallResult(true, text = text)
  }

  /**
   * 带一次自动重试的调用。
   */
  private suspend fun performChatWithRetry(
    config: LlmRequestConfig,
    messages: JSONArray,
    maxRetry: Int = 1
  ): RawCallResult {
    var attempt = 0
    var last: RawCallResult
    while (true) {
      attempt++
      last = performChat(config, messages)
      if (last.ok) return last
      if (attempt > maxRetry) return last
      Log.w(TAG, "performChat failed (attempt=$attempt), will retry once: ${last.httpCode ?: ""} ${last.error ?: ""}")
      try {
        kotlinx.coroutines.delay(350)
      } catch (t: Throwable) {
        Log.w(TAG, "Retry delay interrupted", t)
      }
    }
  }

  /**
   * 测试 LLM 调用是否可用：发送最简单 Prompt，看是否有返回内容。
   * 不改变任何业务状态，仅用于连通性自检/配置校验。
   */
  suspend fun testConnectivity(prefs: Prefs): LlmTestResult = withContext(Dispatchers.IO) {
    // 基础必填校验（endpoint / model）
    val active = getActiveConfig(prefs)
    if (active.endpoint.isBlank() || active.model.isBlank()) {
      return@withContext LlmTestResult(
        ok = false,
        message = "Missing endpoint or model"
      )
    }

    val messages = JSONArray().apply {
      put(JSONObject().apply {
        put("role", "user")
        put("content", "say `hi`")
      })
    }

    val result = performChat(active, messages)
    if (result.ok) {
      return@withContext LlmTestResult(true, contentPreview = result.text?.take(120))
    } else {
      return@withContext LlmTestResult(false, httpCode = result.httpCode, message = result.error)
    }
  }

    /**
   * 与 process 等价，但返回是否成功及错误信息，便于 UI 反馈。
   */
  suspend fun processWithStatus(input: String, prefs: Prefs): LlmProcessResult = withContext(Dispatchers.IO) {
    if (input.isBlank()) {
      Log.d(TAG, "Input is blank, skipping processing")
      return@withContext LlmProcessResult(ok = true, text = input)
    }

    val config = getActiveConfig(prefs)
    val prompt = prefs.activePromptContent.ifBlank { Prefs.DEFAULT_LLM_PROMPT }

    val messages = JSONArray().apply {
      put(JSONObject().apply {
        put("role", "system")
        put("content", prompt)
      })
      put(JSONObject().apply {
        put("role", "user")
        put("content", input)
      })
    }

    val result = performChatWithRetry(config, messages)
    if (!result.ok) {
      if (result.httpCode != null) {
        Log.w(TAG, "LLM process() failed: HTTP ${result.httpCode}, ${result.error}")
      } else {
        Log.w(TAG, "LLM process() failed: ${result.error}")
      }
      return@withContext LlmProcessResult(false, text = input, errorMessage = result.error, httpCode = result.httpCode)
    }

    val text = result.text ?: input
    Log.d(TAG, "Text processing completed, output length: ${text.length}")
    return@withContext LlmProcessResult(true, text = text)
  }

  /**
   * 与 editText 等价，但返回是否成功及错误信息，便于 UI 反馈。
   */
  suspend fun editTextWithStatus(original: String, instruction: String, prefs: Prefs): LlmProcessResult = withContext(Dispatchers.IO) {
    if (original.isBlank() || instruction.isBlank()) {
      Log.d(TAG, "Original or instruction is blank, skipping edit")
      return@withContext LlmProcessResult(true, text = original)
    }

    val config = getActiveConfig(prefs)

    val systemPrompt = """
      你是一个精确的中文文本编辑助手。你的任务是根据"编辑指令"对"原文"进行最小必要修改。
      规则：
      - 只输出最终结果文本，不要输出任何解释、前后缀或引号。
      - 如指令含糊、矛盾或不可执行，原样返回原文。
      - 不要编造内容；除非指令明确要求，否则不要增删信息、不要改变语气与长度。
      - 保留原有段落、换行、空白与标点格式（除非指令要求变更）。
      - 保持语言/文字风格与原文一致；中文按原文简繁体维持不变。
      - 涉及脱敏时，仅将需脱敏片段替换为『[REDACTED]』，其余保持不变。
      - Output must be ONLY the edited text.

      示例（仅用于学习风格，不要照搬示例文本）：
      1) 指令：将口语化改为书面语；保留含义
         原文：我今天有点事儿，可能晚点到，你们先开始别等我
         输出：我今天有事，可能会晚到，请先开始，无需等待我。
      2) 指令：纠正错别字
         原文：这个方案挺好得，就是数据那块需要再核实一下
         输出：这个方案挺好的，就是数据那块需要再核实一下。
      3) 指令：把 comet 更改为 Kotlin
         原文：最近高强度写 comet,感觉效果还不错
         输出：最近高强度写 Kotlin,感觉效果还不错
      4) 指令：把列表换成逗号分隔的一行
         原文：苹果\n香蕉\n葡萄
         输出：苹果，香蕉，葡萄。
      5) 指令：脱敏姓名与电话
         原文：联系人张三，电话 13800000000
         输出：联系人[REDACTED]，电话[REDACTED]
    """.trimIndent()

    val userContent = """
      【编辑指令】
      $instruction

      【原文】
      $original
    """.trimIndent()

    val messages = JSONArray().apply {
      put(JSONObject().apply {
        put("role", "system")
        put("content", systemPrompt)
      })
      put(JSONObject().apply {
        put("role", "user")
        put("content", userContent)
      })
    }

    val result = performChatWithRetry(config, messages)
    if (!result.ok) {
      if (result.httpCode != null) {
        Log.w(TAG, "LLM editText() failed: HTTP ${result.httpCode}, ${result.error}")
      } else {
        Log.w(TAG, "LLM editText() failed: ${result.error}")
      }
      return@withContext LlmProcessResult(false, text = original, errorMessage = result.error, httpCode = result.httpCode)
    }

    val out = result.text ?: original

    Log.d(TAG, "Text editing completed, output length: ${out.length}")
    return@withContext LlmProcessResult(true, text = out)
  }
}
