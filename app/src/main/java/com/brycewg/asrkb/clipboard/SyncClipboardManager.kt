package com.brycewg.asrkb.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import android.util.Base64
import com.brycewg.asrkb.store.Prefs

/**
 * 在 IME 面板可见期间启用：
 * - 监听剪贴板变动并上传（文本类型）
 * - 按设定周期从服务器拉取文本并写入系统剪贴板
 *
 * 注意：服务端认证按文档要求使用 Header: `Authorization: Basic 用户名:密码`（非 Base64）。
 */
class SyncClipboardManager(
  private val context: Context,
  private val prefs: Prefs,
  private val scope: CoroutineScope,
  private val listener: Listener? = null
) {
  interface Listener {
    fun onPulledNewContent(text: String)
    fun onUploadSuccess()
  }
  private val clipboard by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
  private val client by lazy {
    OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(8, TimeUnit.SECONDS)
      .writeTimeout(8, TimeUnit.SECONDS)
      .build()
  }

  private var pullJob: Job? = null
  private var listenerRegistered = false
  @Volatile private var suppressNextChange = false

  private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
    if (suppressNextChange) {
      // 忽略由我们主动写入导致的回调
      suppressNextChange = false
      return@OnPrimaryClipChangedListener
    }
    if (!prefs.syncClipboardEnabled) return@OnPrimaryClipChangedListener
    scope.launch(Dispatchers.IO) { try { uploadCurrentClipboardText() } catch (_: Throwable) { } }
  }

  fun start() {
    if (!prefs.syncClipboardEnabled) return
    ensureListener()
    ensurePullLoop()
  }

  fun stop() {
    try { if (listenerRegistered) clipboard.removePrimaryClipChangedListener(clipListener) } catch (_: Throwable) { }
    listenerRegistered = false
    pullJob?.cancel()
    pullJob = null
    suppressNextChange = false
  }

  private fun ensureListener() {
    if (!listenerRegistered) {
      try {
        clipboard.addPrimaryClipChangedListener(clipListener)
        listenerRegistered = true
      } catch (_: Throwable) { }
    }
  }

  private fun ensurePullLoop() {
    pullJob?.cancel()
    if (!prefs.syncClipboardAutoPullEnabled) return
    val intervalSec = prefs.syncClipboardPullIntervalSec.coerceIn(1, 600)
    pullJob = scope.launch(Dispatchers.IO) {
      while (isActive && prefs.syncClipboardEnabled && prefs.syncClipboardAutoPullEnabled) {
        try { pullNow(updateClipboard = true) } catch (_: Throwable) { }
        delay(intervalSec * 1000L)
      }
    }
  }

  private fun buildUrl(): String? {
    val raw = prefs.syncClipboardServerBase.trim()
    if (raw.isBlank()) return null
    val base = raw.trimEnd('/')
    val lower = base.lowercase()
    return if (lower.endsWith(".json")) base else "$base/SyncClipboard.json"
  }

  private fun sha256Hex(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) sb.append(String.format("%02x", b))
    return sb.toString()
  }

  private fun authHeaderPlain(): String? {
    val u = prefs.syncClipboardUsername
    val p = prefs.syncClipboardPassword
    if (u.isBlank() || p.isBlank()) return null
    // 按文档要求：Authorization: Basic 用户名:密码（非 Base64）
    return "Basic $u:$p"
  }

  private fun authHeaderB64(): String? {
    val u = prefs.syncClipboardUsername
    val p = prefs.syncClipboardPassword
    if (u.isBlank() || p.isBlank()) return null
    val token = "$u:$p".toByteArray(Charsets.UTF_8)
    val b64 = Base64.encodeToString(token, Base64.NO_WRAP)
    return "Basic $b64"
  }

  private fun readClipboardText(): String? {
    val clip = try { clipboard.primaryClip } catch (_: Throwable) { null } ?: return null
    if (clip.itemCount <= 0) return null
    val item = clip.getItemAt(0)
    val text = try { item.coerceToText(context)?.toString() } catch (_: Throwable) { null }
    return text?.takeIf { it.isNotEmpty() }
  }

  private fun writeClipboardText(text: String) {
    val clip = ClipData.newPlainText("SyncClipboard", text)
    suppressNextChange = true
    try { clipboard.setPrimaryClip(clip) } catch (_: Throwable) { suppressNextChange = false }
  }

  private fun uploadCurrentClipboardText() {
    val url = buildUrl() ?: return
    val auth1 = authHeaderPlain() ?: return
    val auth2 = authHeaderB64() ?: return
    val text = readClipboardText() ?: return
    if (text.isEmpty()) return
    // 若与最近一次成功上传（或最近一次拉取写入）相同，则跳过上传，避免重复
    try {
      val newHash = sha256Hex(text)
      val last = try { prefs.syncClipboardLastUploadedHash } catch (_: Throwable) { "" }
      if (newHash == last) return
    } catch (_: Throwable) { /* ignore and continue */ }
    // 先按文档尝试明文；失败则回退标准 Basic Base64
    if (!uploadText(url, auth1, text)) {
      uploadText(url, auth2, text)
    }
  }

  private fun uploadText(url: String, auth: String, text: String): Boolean {
    val bodyJson = JSONObject().apply {
      put("File", "")
      put("Clipboard", text)
      put("Type", "Text")
    }.toString()
    val req = Request.Builder()
      .url(url)
      .header("Authorization", auth)
      .put(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
      .build()
    client.newCall(req).execute().use { resp ->
      if (resp.isSuccessful) {
        // 记录最近一次成功上传内容的哈希，便于后续对比
        try { prefs.syncClipboardLastUploadedHash = sha256Hex(text) } catch (_: Throwable) { }
        try { listener?.onUploadSuccess() } catch (_: Throwable) { }
        return true
      }
      return false
    }
  }

  /**
   * 一次性上传当前系统粘贴板文本（不进行“与上次一致”跳过判断）。
   * 返回是否成功。
   */
  fun uploadOnce(): Boolean {
    val url = buildUrl() ?: return false
    val auth1 = authHeaderPlain() ?: return false
    val auth2 = authHeaderB64() ?: return false
    val text = readClipboardText() ?: return false
    if (text.isEmpty()) return false
    return try {
      val ok = if (!uploadText(url, auth1, text)) uploadText(url, auth2, text) else true
      ok
    } catch (_: Throwable) { false }
  }

  fun pullNow(updateClipboard: Boolean): Pair<Boolean, String?> {
    val url = buildUrl() ?: return false to null
    val auth1 = authHeaderPlain() ?: return false to null
    val auth2 = authHeaderB64() ?: return false to null
    fun doReq(auth: String): Pair<Boolean, String?> {
      val req = Request.Builder()
        .url(url)
        .header("Authorization", auth)
        .get()
        .build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return false to null
        val body = resp.body?.string()?.takeIf { it.isNotEmpty() } ?: return false to null
        val o = try { JSONObject(body) } catch (_: Throwable) { return false to null }
        val type = o.optString("Type")
        if (!TextUtils.equals(type, "Text")) return false to null
        val text = o.optString("Clipboard", null) ?: return false to null
        if (updateClipboard) {
          val cur = readClipboardText()
          if (text.isNotEmpty() && text != cur) {
            writeClipboardText(text)
            // 将此次拉取的内容也记录到“最近一次上传哈希”，避免后续补上传（减少不必要的上传）
            try { prefs.syncClipboardLastUploadedHash = sha256Hex(text) } catch (_: Throwable) { }
            try { listener?.onPulledNewContent(text) } catch (_: Throwable) { }
          }
        }
        return true to text
      }
    }
    val r1 = doReq(auth1)
    if (r1.first) return r1
    val r2 = doReq(auth2)
    return r2
  }

  /**
   * 在启动时调用：若系统剪贴板文本与上次成功上传不一致，则主动上传一次。
   */
  fun proactiveUploadIfChanged() {
    val url = buildUrl() ?: return
    val auth1 = authHeaderPlain() ?: return
    val auth2 = authHeaderB64() ?: return
    val text = readClipboardText() ?: return
    if (text.isEmpty()) return
    val newHash = sha256Hex(text)
    val last = try { prefs.syncClipboardLastUploadedHash } catch (_: Throwable) { "" }
    if (newHash != last) {
      try {
        if (!uploadText(url, auth1, text)) {
          uploadText(url, auth2, text)
        }
      } catch (_: Throwable) { }
    }
  }
}
