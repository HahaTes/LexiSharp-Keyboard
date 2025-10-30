package com.brycewg.asrkb.ui.settings.backup

import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebDAV 备份公共辅助：供主工程与 Pro 变体（后台任务）共用。
 * 注意：不做任何 UI 提示，调用方自行处理结果与提示。
 */
object WebDavBackupHelper {
  private const val TAG = "WebDavBackupHelper"
  private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
  private const val WEBDAV_DIRECTORY = "LexiSharp"
  private const val WEBDAV_FILENAME = "asr_keyboard_settings.json"

  fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')
  fun buildDirectoryUrl(baseUrl: String): String = "$baseUrl/$WEBDAV_DIRECTORY"
  fun buildFileUrl(baseUrl: String): String = "$baseUrl/$WEBDAV_DIRECTORY/$WEBDAV_FILENAME"

  /**
   * 将当前偏好（含密钥）导出为 JSON 并通过 WebDAV 上传到固定路径。
   * @return true 表示上传成功；false 表示参数不全或 HTTP 非 2xx。
   */
  suspend fun uploadSettings(context: android.content.Context, prefs: Prefs, http: OkHttpClient = OkHttpClient()): Boolean =
    withContext(Dispatchers.IO) {
      val rawUrl = prefs.webdavUrl.trim()
      if (rawUrl.isEmpty()) return@withContext false
      val baseUrl = normalizeBaseUrl(rawUrl)

      try {
        ensureDirectoryExists(prefs, baseUrl, http)

        val fileUrl = buildFileUrl(baseUrl)
        val payload = try { com.brycewg.asrkb.ProUiInjector.buildBackupJson(context, prefs) } catch (_: Throwable) { prefs.exportJsonString() }
        val body: RequestBody = payload.toRequestBody(JSON_MEDIA)
        val reqBuilder = Request.Builder().url(fileUrl).put(body)
        addBasicAuthIfNeeded(reqBuilder, prefs)
        http.newCall(reqBuilder.build()).execute().use { resp ->
          val ok = resp.isSuccessful
          if (!ok) Log.e(TAG, "upload failed: code=${resp.code}, url=$fileUrl")
          return@withContext ok
        }
      } catch (t: Throwable) {
        Log.e(TAG, "upload exception", t)
        return@withContext false
      }
    }

  /**
   * 从 WebDAV 下载备份 JSON 文本。
   * @return JSON 字符串；若未配置 URL 或 HTTP 非 2xx 返回 null。
   */
  suspend fun downloadSettings(prefs: Prefs, http: OkHttpClient = OkHttpClient()): String? =
    withContext(Dispatchers.IO) {
      val rawUrl = prefs.webdavUrl.trim()
      if (rawUrl.isEmpty()) return@withContext null
      val baseUrl = normalizeBaseUrl(rawUrl)
      val fileUrl = buildFileUrl(baseUrl)
      val reqBuilder = Request.Builder().url(fileUrl).get()
      addBasicAuthIfNeeded(reqBuilder, prefs)
      try {
        http.newCall(reqBuilder.build()).execute().use { resp ->
          if (!resp.isSuccessful) {
            Log.e(TAG, "download failed: code=${resp.code}, url=$fileUrl")
            return@withContext null
          }
          return@withContext resp.body?.string()
        }
      } catch (t: Throwable) {
        Log.e(TAG, "download exception", t)
        return@withContext null
      }
    }

  /**
   * 确保备份目录存在，不存在则创建。
   * 网络/HTTP 异常向上抛出由调用处处理。
   */
  @Throws(Exception::class)
  suspend fun ensureDirectoryExists(prefs: Prefs, baseUrl: String, http: OkHttpClient) {
    withContext(Dispatchers.IO) {
      val dirUrl = buildDirectoryUrl(baseUrl)
      // PROPFIND 检查
      val checkBuilder = Request.Builder()
        .url(dirUrl)
        .method("PROPFIND", ByteArray(0).toRequestBody(null))
        .addHeader("Depth", "0")
      addBasicAuthIfNeeded(checkBuilder, prefs)

      http.newCall(checkBuilder.build()).execute().use { resp ->
        when (resp.code) {
          207 -> return@use // 已存在
          404 -> Unit // 需要创建
          else -> throw Exception("HTTP ${resp.code}")
        }
      }

      // MKCOL 创建
      val mkdirBuilder = Request.Builder().url(dirUrl).method("MKCOL", ByteArray(0).toRequestBody(null))
      addBasicAuthIfNeeded(mkdirBuilder, prefs)
      http.newCall(mkdirBuilder.build()).execute().use { resp ->
        when (resp.code) {
          201, 405 -> return@use // 201 创建成功；405 已存在
          else -> throw Exception("HTTP ${resp.code}")
        }
      }
    }
  }

  private fun addBasicAuthIfNeeded(builder: Request.Builder, prefs: Prefs) {
    val user = prefs.webdavUsername.trim()
    val pass = prefs.webdavPassword.trim()
    if (user.isNotEmpty()) {
      try {
        val token = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        builder.addHeader("Authorization", "Basic $token")
      } catch (e: Exception) {
        Log.e(TAG, "addBasicAuthIfNeeded error", e)
      }
    }
  }
}
