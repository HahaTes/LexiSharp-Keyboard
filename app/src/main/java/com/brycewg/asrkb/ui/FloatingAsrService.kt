package com.brycewg.asrkb.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.StreamingAsrEngine
import com.brycewg.asrkb.asr.VolcFileAsrEngine
import com.brycewg.asrkb.asr.SiliconFlowFileAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine
import com.brycewg.asrkb.asr.OpenAiFileAsrEngine
import com.brycewg.asrkb.asr.DashscopeFileAsrEngine
import com.brycewg.asrkb.asr.GeminiFileAsrEngine
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService.FocusContext
import com.brycewg.asrkb.asr.VolcStreamAsrEngine
import com.brycewg.asrkb.asr.SonioxFileAsrEngine
import com.brycewg.asrkb.asr.SonioxStreamAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.brycewg.asrkb.clipboard.SyncClipboardManager
import androidx.core.graphics.toColorInt
import java.util.Locale
import com.brycewg.asrkb.LocaleHelper

/**
 * 悬浮球语音识别服务
 * 在其他输入法激活时也能通过悬浮球进行语音识别
 */
class FloatingAsrService : Service(), StreamingAsrEngine.Listener {

    companion object {
        private const val TAG = "FloatingAsrService"
        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_ASR_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_ASR_HIDE"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private var ballView: View? = null
    private var ballIcon: ImageView? = null
    private var ballProgress: ProgressBar? = null
    private var lp: WindowManager.LayoutParams? = null
    // 轮盘菜单与供应商选择面板
    private var radialMenuView: View? = null
    private var vendorMenuView: View? = null
    // 悬浮球移动模式：开启后可直接拖动，点按一次退出
    private var moveModeEnabled: Boolean = false
    // 触摸期间的可见性保护（首轮长按出现轮盘时防止被隐藏）
    private var touchActiveGuard: Boolean = false
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asrEngine: StreamingAsrEngine? = null
    private val postproc = LlmPostProcessor()
    
    private var isRecording = false
    private var isProcessing = false
    private var progressAnimator: ObjectAnimator? = null
    private var currentToast: Toast? = null
    // 预览会话上下文：记录焦点输入框前后缀与上次中间结果，便于动态预览
    private var focusContext: FocusContext? = null
    private var lastPartialForPreview: String? = null
    // Telegram 占位符修复：记录是否已注入零宽标记及使用的字符
    private var markerInserted: Boolean = false
    private var markerChar: String? = null
    // 结果提交与超时兜底
    private var processingTimeoutJob: Job? = null
    private var hasCommittedResult: Boolean = false
    // 本地模型延迟预加载触发标记：仅在悬浮球首次出现时尝试一次
    private var svPreloadTriggered: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var imeVisible: Boolean = false
    private val hintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE -> {
                    imeVisible = true
                    updateVisibilityByPref()
                }
                FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN -> {
                    imeVisible = false
                    updateVisibilityByPref()
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        asrEngine = buildEngineForCurrentMode()
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE)
                addAction(FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN)
            }
            registerReceiver(hintReceiver, filter)
        } catch (_: Throwable) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, floatingAsrEnabled=${prefs.floatingAsrEnabled}")
        when (intent?.action) {
            ACTION_SHOW -> updateVisibilityByPref()
            ACTION_HIDE -> hideBall()
            FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE -> {
                imeVisible = true
                updateVisibilityByPref()
            }
            FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN -> {
                imeVisible = false
                updateVisibilityByPref()
            }
            else -> showBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBall()
        hideRadialMenu()
        hideVendorMenu()
        asrEngine?.stop()
        serviceScope.cancel()
        try { unregisterReceiver(hintReceiver) } catch (_: Throwable) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBall() {
        Log.d(TAG, "showBall called: floatingAsrEnabled=${prefs.floatingAsrEnabled}, hasOverlay=${hasOverlayPermission()}")
        if (!prefs.floatingAsrEnabled || !hasOverlayPermission()) {
            Log.w(TAG, "Cannot show ball: permission or setting issue")
            hideBall()
            return
        }

        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible && !isRecording) {
            Log.d(TAG, "Pref requires IME visible; hiding for now")
            hideBall()
            return
        }

        if (ballView != null) {
            applyBallAlpha()
            applyBallSize()
            updateBallState()
            return
        }
        
        val view = LayoutInflater.from(this).inflate(R.layout.floating_asr_ball, null, false)
        ballIcon = view.findViewById(R.id.ballIcon)
        ballProgress = view.findViewById(R.id.ballProgress)
        
        ballIcon?.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            onBallClick()
        }
        // 在根视图与图标上都绑定拖动监听；内部统一更新根视图位置，避免对子视图误设 WindowManager.LayoutParams
        attachDrag(view)
        ballIcon?.let { attachDrag(it) }
        
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val size = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        val params = WindowManager.LayoutParams(
            dp(size), dp(size),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // 恢复上次位置（若存在），否则使用默认值
        try {
            val sx = prefs.floatingBallPosX
            val sy = prefs.floatingBallPosY
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val vw = params.width
            val vh = params.height
            if (sx >= 0 && sy >= 0) {
                params.x = sx.coerceIn(0, (screenW - vw).coerceAtLeast(0))
                params.y = sy.coerceIn(0, (screenH - vh).coerceAtLeast(0))
            } else {
                params.x = dp(12)
                params.y = dp(180)
            }
        } catch (_: Throwable) {
            params.x = dp(12)
            params.y = dp(180)
        }
        
        try {
            windowManager.addView(view, params)
            ballView = view
            lp = params
            applyBallAlpha()
            applyBallSize()
            updateBallState()
            Log.d(TAG, "Ball view added successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add ball view", e)
        }

        // 悬浮球首次出现时，按需异步预加载本地 SenseVoice，避免主线程卡顿
        try {
            if (!svPreloadTriggered) {
                if (prefs.asrVendor == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                    val prepared = try { com.brycewg.asrkb.asr.isSenseVoicePrepared() } catch (_: Throwable) { false }
                    if (!prepared) {
                        serviceScope.launch(Dispatchers.Default) {
                            try { com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(this@FloatingAsrService, prefs) } catch (_: Throwable) { }
                        }
                    }
                }
                svPreloadTriggered = true
            }
        } catch (_: Throwable) { }
    }

    private fun updateVisibilityByPref() {
        val forceVisible = (radialMenuView != null || vendorMenuView != null || moveModeEnabled || touchActiveGuard)
        if (!prefs.floatingAsrEnabled) {
            hideBall(); return
        }
        // 录音中/正在交互（轮盘/面板/移动模式）不受“仅在键盘显示时显示悬浮球”限制
        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible && !isRecording && !forceVisible) {
            hideBall(); return
        }
        showBall()
    }

    private fun hideBall() {
        val v = ballView ?: return
        try { persistBallPosition() } catch (_: Throwable) { }
        try { windowManager.removeView(v) } catch (_: Throwable) { }
        ballView = null
        lp = null
    }

    private fun applyBallAlpha() {
        val a = try { prefs.floatingSwitcherAlpha } catch (_: Throwable) { 1.0f }
        ballView?.alpha = a
    }

    private fun applyBallSize() {
        val v = ballView ?: return
        val p = lp ?: return
        val size = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        p.width = dp(size)
        p.height = dp(size)
        try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
    }

    private fun onBallClick() {
        // 移动模式：点按退出，不触发录音开关
        if (moveModeEnabled) {
            moveModeEnabled = false
            ballView?.let { try { animateSnapToEdge(it) } catch (_: Throwable) { snapToEdge(it) } }
            hideRadialMenu(); hideVendorMenu()
            try { persistBallPosition() } catch (_: Throwable) { }
            return
        }
        // 检查无障碍权限
        if (!AsrAccessibilityService.isEnabled()) {
            Log.w(TAG, "Accessibility service not enabled")
            showToast(getString(R.string.toast_need_accessibility_perm))
            // 跳转到无障碍设置
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to open accessibility settings", e)
            }
            return
        }

        if (isRecording) {
            // 停止录音
            stopRecording()
        } else {
            // 开始录音
            startRecording()
        }
    }

    private fun invokeImePicker() {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
                return
            }
            // 为避免某些系统上透明 Activity 过早 finish 导致选择器被收起，
            // 这里统一跳转到设置页并自动拉起系统输入法选择器，保持在本应用前台。
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(SettingsActivity.EXTRA_AUTO_SHOW_IME_PICKER, true)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
            } catch (_: Throwable) { }
        }
    }

    private fun isOurImeEnabled(imm: InputMethodManager?): Boolean {
        val list = try { imm?.enabledInputMethodList } catch (_: Throwable) { null }
        if (list?.any { it.packageName == packageName } == true) return true
        return try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            val id = "$packageName/.ime.AsrKeyboardService"
            enabled?.contains(id) == true || (enabled?.split(':')?.any { it.startsWith(packageName) } == true)
        } catch (_: Throwable) { false }
    }

    private fun startRecording() {
        Log.d(TAG, "startRecording called")
        // 检查权限
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "No record audio permission")
            showToast(getString(R.string.asr_error_mic_permission_denied))
            return
        }

        if (!prefs.hasAsrKeys()) {
            Log.w(TAG, "No ASR keys configured")
            showToast(getString(R.string.hint_need_keys))
            return
        }

        // 本地 SenseVoice：若已缓存加载则跳过文件检查；否则按“与键盘一致”的变体路径检查
        if (prefs.asrVendor == AsrVendor.SenseVoice) {
            val prepared = try { com.brycewg.asrkb.asr.isSenseVoicePrepared() } catch (_: Throwable) { false }
            if (!prepared) {
                val base = try { getExternalFilesDir(null) } catch (_: Throwable) { null } ?: filesDir
                val probeRoot = java.io.File(base, "sensevoice")
                val variant = try { prefs.svModelVariant } catch (_: Throwable) { "small-int8" }
                val variantDir = if (variant == "small-full") java.io.File(probeRoot, "small-full") else java.io.File(probeRoot, "small-int8")
                val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir) ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
                if (found == null) {
                    showToast(getString(R.string.error_sensevoice_model_missing))
                    return
                }
            }
        }

        isRecording = true
        updateBallState()
        // 录音开始后，若当前键盘未显示，也应强制展示悬浮球
        updateVisibilityByPref()
        showToast(getString(R.string.floating_asr_recording))

        // Telegram 特判：为空时其占位文本会作为真实 text 暴露，先注入零宽字符使其进入“非空”状态，后续全量替换即可避开占位符拼接
        tryFixTelegramPlaceholderIfNeeded()

        asrEngine = buildEngineForCurrentMode()
        Log.d(TAG, "ASR engine created: ${asrEngine?.javaClass?.simpleName}")
        // 开启新会话前清理提交标记与超时任务
        try { processingTimeoutJob?.cancel() } catch (_: Throwable) {}
        processingTimeoutJob = null
        hasCommittedResult = false
        // 记录开始录音时的焦点上下文（prefix/suffix），用于最终写入时避免覆盖原有内容。
        // 注意：在 Telegram 修复逻辑后抓取，但部分 App（如 Telegram）对 ACTION_PASTE 的文本刷新是异步的，
        // 因此这里先抓取一次，并在短延迟后再刷新一次快照，提升准确性。
        focusContext = AsrAccessibilityService.getCurrentFocusContext()
        handler.postDelayed({
            try { focusContext = AsrAccessibilityService.getCurrentFocusContext() } catch (_: Throwable) { }
        }, 120)
        lastPartialForPreview = null
        asrEngine?.start()
    }

    private fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        isRecording = false
        asrEngine?.stop()

        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            // 需要后处理,显示处理中状态
            isProcessing = true
            updateBallState()
            showToast(getString(R.string.floating_asr_recognizing))
            // 启动超时兜底：若迟迟未收到 onFinal，则使用最后一次预览结果提交，避免卡在蓝色状态
            try { processingTimeoutJob?.cancel() } catch (_: Throwable) {}
            processingTimeoutJob = serviceScope.launch {
                val timeoutMs = 8000L
                delay(timeoutMs)
                // 若仍处于处理态且未提交结果，则走兜底提交
                if (isProcessing && !isRecording && !hasCommittedResult) {
                    val candidate = lastPartialForPreview?.trim().orEmpty()
                    Log.w(TAG, "Post-process timeout; fallback with preview='${candidate}'")
                    if (candidate.isNotEmpty()) {
                        var textOut = candidate
                        // 可选：保持与最终逻辑一致的尾部标点裁剪与后处理
                        if (prefs.trimFinalTrailingPunct) textOut = trimTrailingPunctuation(textOut)
                        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
                            try {
                                textOut = postproc.process(textOut, prefs).ifBlank { textOut }
                            } catch (_: Throwable) { /* keep original */ }
                            if (prefs.trimFinalTrailingPunct) textOut = trimTrailingPunctuation(textOut)
                        }
                        // 插入文本（与 onFinal 相同的写入策略与兼容分支）
                        val ctx = focusContext ?: AsrAccessibilityService.getCurrentFocusContext()
                        var toWrite = if (ctx != null) ctx.prefix + textOut + ctx.suffix else textOut
                        toWrite = stripMarkersIfAny(toWrite)
                        val pkg = AsrAccessibilityService.getActiveWindowPackage()
                        val isTg = pkg != null && isTelegramLikePackage(pkg)
                        val writeCompat = try { prefs.floatingWriteTextCompatEnabled } catch (_: Throwable) { true }
                        val compatTarget = pkg != null && isPackageInCompatTargets(pkg)
                        if (isTg && markerInserted) {
                            // 初始为空场景（通过注入标记确认），忽略任何“已有文本”前后缀
                            toWrite = textOut
                        }
                        var wrote = false
                        if (writeCompat && compatTarget) {
                            wrote = AsrAccessibilityService.selectAllAndPasteSilent(toWrite)
                            if (!wrote) {
                                wrote = AsrAccessibilityService.insertText(this@FloatingAsrService, toWrite)
                            }
                        } else {
                            wrote = AsrAccessibilityService.insertText(this@FloatingAsrService, toWrite)
                        }
                        Log.d(TAG, "Fallback inserted=$wrote text='$toWrite'")
                        showToast(getString(R.string.floating_asr_completed))
                        if (wrote) {
                            try { prefs.addAsrChars(textOut.length) } catch (_: Throwable) { }
                        }
                        hasCommittedResult = true
                    } else {
                        Log.w(TAG, "Fallback has no candidate text; only clear state")
                    }
                    // 清理状态
                    isProcessing = false
                    updateBallState()
                    focusContext = null
                    lastPartialForPreview = null
                    markerInserted = false
                    markerChar = null
                }
            }
        } else {
            updateBallState()
            // 若使用本地 SenseVoice 且当前未预加载/未缓存，则先让引擎触发“加载中…”，再稍后提示“识别中…”
            if (prefs.asrVendor == AsrVendor.SenseVoice) {
                val prepared = try { com.brycewg.asrkb.asr.isSenseVoicePrepared() } catch (_: Throwable) { false }
                if (!prepared) {
                    handler.postDelayed({ showToast(getString(R.string.floating_asr_recognizing)) }, 700)
                } else {
                    showToast(getString(R.string.floating_asr_recognizing))
                }
            } else {
                showToast(getString(R.string.floating_asr_recognizing))
            }
        }
        // 录音结束后根据偏好与当前场景重新评估显隐
        updateVisibilityByPref()
    }

    private fun showToast(message: String) {
        handler.post {
            try {
                // 取消之前的 Toast
                currentToast?.cancel()
                // 创建并显示新的 Toast
                val ctx = try { LocaleHelper.wrap(this@FloatingAsrService) } catch (_: Throwable) { this@FloatingAsrService }
                currentToast = Toast.makeText(ctx, message, Toast.LENGTH_SHORT)
                currentToast?.show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show toast: $message", e)
            }
        }
    }

    private fun updateBallState() {
        handler.post {
            Log.d(TAG, "updateBallState: recording=$isRecording, processing=$isProcessing")
            if (isRecording) {
                // 录音中:图标变红色,顺时针旋转动画
                ballIcon?.setColorFilter("#F44336".toColorInt())
            } else if (isProcessing) {
                // 处理中:图标变蓝色,逆时针旋转动画
                ballIcon?.setColorFilter("#2196F3".toColorInt())
            } else {
                // 空闲:图标恢复默认颜色,停止动画
                ballIcon?.clearColorFilter()
            }
        }
    }

    private fun stopProgressAnimation() {
        progressAnimator?.cancel()
        progressAnimator = null
        ballProgress?.visibility = View.GONE
        ballProgress?.rotation = 0f
    }

    override fun onFinal(text: String) {
        Log.d(TAG, "onFinal called with text: $text")
        serviceScope.launch {
            // 收到最终结果，取消兜底任务
            try { processingTimeoutJob?.cancel() } catch (_: Throwable) {}
            processingTimeoutJob = null
            // 若已由兜底提交且当前不在录音，忽略后续 onFinal，避免重复写入
            if (hasCommittedResult && asrEngine?.isRunning != true) {
                Log.w(TAG, "Result already committed by fallback; ignoring residual onFinal")
                return@launch
            }
            var finalText = text
            val stillRecording = (asrEngine?.isRunning == true)

            if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
                // AI后处理：若仍在录音分段，不切换到蓝色“处理中”以保持连贯性
                Log.d(TAG, "Starting AI post-processing (stillRecording=$stillRecording)")
                if (!stillRecording) {
                    isProcessing = true
                    updateBallState()
                    showToast(getString(R.string.floating_asr_processing))
                }

                val raw = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                finalText = try {
                    postproc.process(raw, prefs).ifBlank { raw }
                } catch (e: Throwable) {
                    Log.e(TAG, "Post-processing failed", e)
                    raw
                }

                if (prefs.trimFinalTrailingPunct) {
                    finalText = trimTrailingPunctuation(finalText)
                }
                Log.d(TAG, "Post-processing completed: $finalText")
            } else if (prefs.trimFinalTrailingPunct) {
                finalText = trimTrailingPunctuation(text)
            }

            isProcessing = false
            // 分段期间保持“录音中”外观，不打断录音连贯性
            isRecording = (asrEngine?.isRunning == true)
            updateBallState()
            // 结果提交后根据偏好恢复显隐规则；若还在录音，则保持强制可见
            updateVisibilityByPref()

            // 插入文本：优先使用开始录音时的焦点快照；若为空，再尝试在提交时获取一次快照，避免覆盖
            if (finalText.isNotEmpty()) {
                val ctx = focusContext ?: AsrAccessibilityService.getCurrentFocusContext()
                var toWrite = if (ctx != null) ctx.prefix + finalText + ctx.suffix else finalText
                // 若曾注入零宽标记，最终写入前将其移除（包含多个候选标记的兜底去除）
                toWrite = stripMarkersIfAny(toWrite)
                Log.d(TAG, "Inserting text: $toWrite (previewCtx=${ctx != null})")
                val pkg = AsrAccessibilityService.getActiveWindowPackage()
                val isTg = pkg != null && isTelegramLikePackage(pkg)
                val writeCompat = try { prefs.floatingWriteTextCompatEnabled } catch (_: Throwable) { true }
                val compatTarget = pkg != null && isPackageInCompatTargets(pkg)
                if (isTg && markerInserted) {
                    // 初始为空场景（通过注入标记确认），忽略任何“已有文本”前后缀，直接使用最终识别结果
                    toWrite = finalText
                }
                var wrote = false
                if (writeCompat && compatTarget) {
                    // 兼容性模式：直接使用“全选+粘贴”，不再先尝试 ACTION_SET_TEXT
                    wrote = AsrAccessibilityService.selectAllAndPasteSilent(toWrite)
                    if (!wrote) {
                        // 兜底一次普通写入，避免彻底失败
                        wrote = AsrAccessibilityService.insertText(this@FloatingAsrService, toWrite)
                    }
                } else {
                    wrote = AsrAccessibilityService.insertText(this@FloatingAsrService, toWrite)
                }
                showToast(getString(R.string.floating_asr_completed))
                if (wrote) {
                    try { prefs.addAsrChars(finalText.length) } catch (_: Throwable) { }
                }
            } else {
                Log.w(TAG, "Final text is empty")
                // 空结果时给出明确提示，避免用户无感等待
                showToast(getString(R.string.asr_error_empty_result))
            }
            // 结束一次 onFinal 的提交，但不封锁后续片段（分段模式可能继续回调 onFinal）
            focusContext = null
            lastPartialForPreview = null
            markerInserted = false
            markerChar = null
        }
    }

    override fun onStopped() {
        // 录音阶段结束：若开启后处理，切至处理中；否则恢复空闲外观但仍显示“识别中…”
        serviceScope.launch {
            isRecording = false
            if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
                isProcessing = true
                updateBallState()
                showToast(getString(R.string.floating_asr_processing))
            } else {
                isProcessing = false
                updateBallState()
                if (prefs.asrVendor == AsrVendor.SenseVoice) {
                    val prepared = try { com.brycewg.asrkb.asr.isSenseVoicePrepared() } catch (_: Throwable) { false }
                    if (!prepared) {
                        handler.postDelayed({ showToast(getString(R.string.floating_asr_recognizing)) }, 700)
                    } else {
                        showToast(getString(R.string.floating_asr_recognizing))
                    }
                } else {
                    showToast(getString(R.string.floating_asr_recognizing))
                }
            }
            // 非录音阶段恢复“仅在键盘显示时显示悬浮球”的约束
            updateVisibilityByPref()
        }
    }

    override fun onPartial(text: String) {
        // 仅在录音中且存在焦点可编辑框时进行动态预览；
        // 无焦点时按需忽略（不预览）。
        if (!isRecording) return
        val ctx = focusContext ?: return
        if (text.isEmpty()) return
        if (lastPartialForPreview == text) return
        val toWrite = ctx.prefix + text + ctx.suffix
        Log.d(TAG, "onPartial preview: $text")
        // 切到主线程，静默写入，避免 Toast/Looper 异常与刷屏
        serviceScope.launch {
            AsrAccessibilityService.insertTextSilent(toWrite)
        }
        lastPartialForPreview = text
    }

    private fun tryFixTelegramPlaceholderIfNeeded() {
        markerInserted = false
        markerChar = null
        val pkg = AsrAccessibilityService.getActiveWindowPackage() ?: return
        val compat = try { prefs.floatingWriteTextCompatEnabled } catch (_: Throwable) { true }
        // Telegram 家族/分支：官方包前缀 + 常见分支（Nagram）
        if (!compat || !isTelegramLikePackage(pkg) || !isPackageInCompatTargets(pkg)) return
        // 候选零宽标记：优先 U+2060（WORD JOINER），若失败回退 U+200B（ZWSP）
        val candidates = listOf("\u2060", "\u200B")
        for (m in candidates) {
            val ok = AsrAccessibilityService.pasteTextSilent(m)
            if (ok) {
                markerInserted = true
                markerChar = m
                Log.d(TAG, "Telegram fix: injected marker ${Integer.toHexString(m.codePointAt(0))}")
                break
            }
        }
        // 无论是否注入成功，都不阻塞；后续逻辑仍可工作
    }

    private fun stripMarkersIfAny(s: String): String {
        var out = s
        // 若有会话内记录的标记，优先去除
        markerChar?.let { if (it.isNotEmpty()) out = out.replace(it, "") }
        // 保险起见，去除两种候选零宽字符（避免极个别情况下会话记录缺失）
        out = out.replace("\u2060", "")
        out = out.replace("\u200B", "")
        return out
    }

    private fun isTelegramLikePackage(pkg: String): Boolean {
        if (pkg.startsWith("org.telegram")) return true
        if (pkg == "nu.gpu.nagram") return true // Nagram 分支
        return false
    }

    private fun isPackageInCompatTargets(pkg: String): Boolean {
        val raw = try { prefs.floatingWriteCompatPackages } catch (_: Throwable) { "" }
        val rules = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return rules.any { it == pkg }
    }

    override fun onError(message: String) {
        Log.e(TAG, "onError called: $message")
        serviceScope.launch {
            isRecording = false
            isProcessing = false
            updateBallState()
            // 出错时也根据偏好恢复显隐规则
            updateVisibilityByPref()
            val mapped = mapErrorToFriendlyMessage(message)
            if (mapped != null) {
                showToast(mapped)
            } else {
                showToast(getString(R.string.floating_asr_error, message))
            }
            focusContext = null
            lastPartialForPreview = null
        }
    }

    private fun trimTrailingPunctuation(s: String): String {
        if (s.isEmpty()) return s
        val regex = Regex("[\\p{Punct}，。！？；、：]+$")
        return s.replace(regex, "")
    }

    private fun buildEngineForCurrentMode(): StreamingAsrEngine? {
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                if (prefs.volcStreamingEnabled) {
                    VolcStreamAsrEngine(this, serviceScope, prefs, this)
                } else {
                    VolcFileAsrEngine(this, serviceScope, prefs, this) { }
                }
            } else null
            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                ElevenLabsFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                DashscopeFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.Soniox -> if (prefs.hasSonioxKeys()) {
                if (prefs.sonioxStreamingEnabled) {
                    SonioxStreamAsrEngine(this, serviceScope, prefs, this)
                } else {
                    SonioxFileAsrEngine(this, serviceScope, prefs, this) { }
                }
            } else null
            AsrVendor.SenseVoice -> {
                // 本地引擎无需鉴权；占位实现会在依赖缺失时提示
                com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine(this, serviceScope, prefs, this) { }
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }


    private var edgeAnimator: ValueAnimator? = null
    private fun currentUiAlpha(): Float = try { prefs.floatingSwitcherAlpha } catch (_: Throwable) { 1f }

    private fun attachDrag(target: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var isDragging = false
        var longActionFired = false // 保留变量占位（但不再使用 2s 长按呼出输入法选择器）
        val touchSlop = dp(4)
        val tinyMoveSlop = dp(6) // “极小范围移动”阈值
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var longPressPosted = false
        val longPressRunnable = Runnable {
            longPressPosted = false
            longActionFired = true
            // 长按打开轮盘菜单（替代此前的长按拖动/长按打开选择器）
            showRadialMenu()
        }

        target.setOnTouchListener { v, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeAnimator?.cancel()
                    moved = false
                    isDragging = moveModeEnabled
                    longActionFired = false
                    touchActiveGuard = true
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    // 移动模式下不允许长按触发轮盘
                    if (!moveModeEnabled && !longPressPosted) {
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                        longPressPosted = true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) moved = true

                    if (!isDragging) {
                        if (moved && longPressPosted) {
                            handler.removeCallbacks(longPressRunnable)
                            longPressPosted = false
                        }
                        return@setOnTouchListener true
                    }

                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels
                    val root = ballView ?: v
                    val vw = if (root.width > 0) root.width else p.width
                    val vh = if (root.height > 0) root.height else p.height
                    val nx = (startX + dx).coerceIn(0, screenW - vw)
                    val ny = (startY + dy).coerceIn(0, screenH - vh)
                    p.x = nx
                    p.y = ny
                    try { windowManager.updateViewLayout(ballView ?: v, p) } catch (_: Throwable) { }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressPosted) {
                        handler.removeCallbacks(longPressRunnable)
                        longPressPosted = false
                    }
                    if (longActionFired) {
                        // 已触发长按动作，抬起时不再处理点击或吸附
                    } else if (isDragging) {
                        // 移动模式下：若未移动则视为点击（用于退出移动模式）；若已移动则吸附
                        if (!moved) {
                            hapticTapIfEnabled(ballView ?: v)
                            onBallClick()
                        } else {
                            try { animateSnapToEdge(v) } catch (_: Throwable) { snapToEdge(v) }
                        }
                    } else if (!moved) {
                        // 非移动模式的点按
                        hapticTapIfEnabled(ballView ?: v)
                        onBallClick()
                    }
                    moved = false
                    isDragging = false
                    longActionFired = false
                    touchActiveGuard = false
                    updateVisibilityByPref()
                    true
                }
                else -> false
            }
        }
    }

    // —— 轮盘菜单（与输入法切换悬浮球一致）——
    private fun showRadialMenu() {
        if (radialMenuView != null) return
        val p = lp ?: return
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { hideRadialMenu() }
        }
        root.alpha = currentUiAlpha()
        val isLeft = isBallOnLeft()
        val items = listOf(
            // 顶部：切换 AI 后处理 Prompt（仅 UI 占位）
            RadialItem(R.drawable.ic_prompt, getString(R.string.label_radial_switch_prompt), getString(R.string.label_radial_switch_prompt)) { onPickPromptPresetFromMenu() },
            // 其余：沿用原有三个功能项
            RadialItem(R.drawable.ic_waveform, getString(R.string.label_radial_switch_asr), getString(R.string.label_radial_switch_asr)) { onPickAsrVendor() },
            RadialItem(R.drawable.ic_keyboard, getString(R.string.label_radial_switch_ime), getString(R.string.label_radial_switch_ime)) { invokeImePickerFromMenu() },
            RadialItem(R.drawable.ic_move, getString(R.string.label_radial_move), getString(R.string.label_radial_move)) { enableMoveModeFromMenu() },
            RadialItem(
                if (try { prefs.postProcessEnabled } catch (_: Throwable) { false }) R.drawable.ic_star_filled else R.drawable.ic_star_outline,
                getString(R.string.label_radial_postproc),
                getString(R.string.label_radial_postproc)
            ) { togglePostprocFromMenu() },
            // 底部：一次性上传/拉取粘贴板
            RadialItem(R.drawable.ic_stat_upload, getString(R.string.label_radial_clipboard_upload), getString(R.string.label_radial_clipboard_upload)) { uploadClipboardOnceFromMenu() },
            RadialItem(R.drawable.ic_stat_download, getString(R.string.label_radial_clipboard_pull), getString(R.string.label_radial_clipboard_pull)) { pullClipboardOnceFromMenu() }
        )
        // 垂直容器：统一承载按钮
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@FloatingAsrService, R.drawable.bg_panel_round)
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
        }
        items.forEachIndexed { index, it ->
            val row = buildCapsule(it.iconRes, it.label, it.contentDescription) { it.onClick(); hideRadialMenu() }
            val lpRow = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            if (index > 0) lpRow.topMargin = dp(6)
            container.addView(row, lpRow)
        }
        container.alpha = 0f
        container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()
        val paramsContainer = android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
        root.addView(container, paramsContainer)
        container.post {
            try {
                val dm = resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels
                val centerX = p.x + (ballView?.width ?: p.width) / 2
                val centerY = p.y + (ballView?.height ?: p.height) / 2
                val offset = dp(16)
                val w = container.width
                val h = container.height
                val lpC = container.layoutParams as android.widget.FrameLayout.LayoutParams
                val left = if (isLeft) (centerX + offset) else (centerX - offset - w)
                val top = centerY - h / 2
                lpC.leftMargin = left.coerceIn(0, (screenW - w).coerceAtLeast(0))
                lpC.topMargin = top.coerceIn(dp(8), (screenH - h - dp(8)).coerceAtLeast(0))
                container.layoutParams = lpC
                container.animate().alpha(1f).translationX(0f).setDuration(160).start()
            } catch (_: Throwable) { }
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(root, params)
            radialMenuView = root
            updateVisibilityByPref()
        } catch (_: Throwable) { }
    }

    private fun hideRadialMenu() {
        radialMenuView?.let { v ->
            try {
                fun cancelAllAnim(view: View) {
                    try { view.animate().cancel() } catch (_: Throwable) { }
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) cancelAllAnim(view.getChildAt(i))
                    }
                }
                cancelAllAnim(v)
                try { windowManager.removeView(v) } catch (_: Throwable) { }
            } catch (_: Throwable) { }
            radialMenuView = null
            updateVisibilityByPref()
        } ?: run { updateVisibilityByPref() }
    }

    private data class RadialItem(
        val iconRes: Int,
        val contentDescription: String,
        val label: String,
        val onClick: () -> Unit
    )

    private fun buildCircleButton(iconRes: Int, cd: String, onClick: () -> Unit): View {
        val iv = ImageView(this)
        iv.setImageResource(iconRes)
        iv.background = ContextCompat.getDrawable(this, R.drawable.bg_floating_ball)
        val pad = dp(6)
        iv.setPadding(pad, pad, pad, pad)
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iv.contentDescription = cd
        try { iv.setColorFilter(0xFF111111.toInt()) } catch (_: Throwable) { }
        iv.setOnClickListener { hapticTapIfEnabled(iv); onClick() }
        return iv
    }

    private fun buildCapsule(iconRes: Int, label: String, cd: String, onClick: () -> Unit): View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@FloatingAsrService, R.drawable.ripple_capsule)
            val p = dp(10)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            contentDescription = cd
            setOnClickListener { hapticTapIfEnabled(this); onClick() }
        }
        val iv = ImageView(this).apply {
            setImageResource(iconRes)
            try { setColorFilter(0xFF111111.toInt()) } catch (_: Throwable) { }
        }
        val tv = android.widget.TextView(this).apply {
            text = label
            setTextColor(0xFF111111.toInt())
            textSize = 12f
            setPadding(dp(6), 0, 0, 0)
        }
        layout.addView(iv, android.widget.LinearLayout.LayoutParams(dp(18), dp(18)))
        layout.addView(tv, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        return layout
    }

    private fun isBallOnLeft(): Boolean {
        val p = lp ?: return true
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val root = ballView
        val vw = root?.width ?: p.width
        val centerX = p.x + vw / 2
        return centerX < w / 2
    }

    private fun invokeImePickerFromMenu() {
        hideVendorMenu()
        invokeImePicker()
    }

    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (prefs.micHapticEnabled) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) { }
    }

    private fun enableMoveModeFromMenu() {
        moveModeEnabled = true
        hideVendorMenu()
        try { android.widget.Toast.makeText(this, getString(R.string.toast_move_mode_on), android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
    }

    private fun togglePostprocFromMenu() {
        try {
            val newVal = !prefs.postProcessEnabled
            prefs.postProcessEnabled = newVal
            val msg = getString(R.string.status_postproc, if (newVal) getString(R.string.toggle_on) else getString(R.string.toggle_off))
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) { }
    }

    private fun onPickAsrVendor() {
        hideVendorMenu()
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { hideVendorMenu() }
        }
        root.alpha = currentUiAlpha()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@FloatingAsrService, R.drawable.bg_panel_round)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        val title = android.widget.TextView(this).apply {
            text = getString(R.string.label_choose_asr_vendor)
            setTextColor(0xFF111111.toInt())
            textSize = 16f
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(title, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))

        val entries = listOf(
            AsrVendor.Volc to getString(R.string.vendor_volc),
            AsrVendor.SiliconFlow to getString(R.string.vendor_sf),
            AsrVendor.ElevenLabs to getString(R.string.vendor_eleven),
            AsrVendor.OpenAI to getString(R.string.vendor_openai),
            AsrVendor.DashScope to getString(R.string.vendor_dashscope),
            AsrVendor.Gemini to getString(R.string.vendor_gemini),
            AsrVendor.Soniox to getString(R.string.vendor_soniox),
            AsrVendor.SenseVoice to getString(R.string.vendor_sensevoice)
        )
        val cur = try { prefs.asrVendor } catch (_: Throwable) { AsrVendor.Volc }
        entries.forEach { (v, name) ->
            val tv = android.widget.TextView(this).apply {
                text = if (v == cur) "✓  $name" else name
                setTextColor(0xFF222222.toInt())
                textSize = 14f
                setPadding(dp(6), dp(8), dp(6), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        val old = try { prefs.asrVendor } catch (_: Throwable) { AsrVendor.Volc }
                        if (v != old) {
                            prefs.asrVendor = v
                            // 离开 SenseVoice 时卸载；切回 SenseVoice 且开启预加载时预热
                            if (old == AsrVendor.SenseVoice && v != AsrVendor.SenseVoice) {
                                try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
                            } else if (v == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                                try { com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(this@FloatingAsrService, prefs) } catch (_: Throwable) { }
                            }
                        }
                        android.widget.Toast.makeText(this@FloatingAsrService, name, android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) { }
                    hideVendorMenu()
                }
            }
            container.addView(tv, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val paramsContainer = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        // 先添加，待测量后根据悬浮球位置与面板尺寸进行智能定位，防止遮挡与越界
        root.addView(container, paramsContainer)
        val (cx, cy) = getBallCenterSnapshot()
        val isLeft = cx < (resources.displayMetrics.widthPixels / 2)
        container.alpha = 0f
        container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()
        container.post {
            try {
                val dm = resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels
                val offset = dp(16)
                val w = container.width
                val h = container.height
                val lpC = container.layoutParams as android.widget.FrameLayout.LayoutParams
                val left = if (isLeft) (cx + offset) else (cx - offset - w)
                val top = cy - h / 2
                lpC.leftMargin = left.coerceIn(0, (screenW - w).coerceAtLeast(0))
                lpC.topMargin = top.coerceIn(0, (screenH - h).coerceAtLeast(0))
                container.layoutParams = lpC
                try { container.animate().alpha(1f).translationX(0f).setDuration(160).start() } catch (_: Throwable) { }
            } catch (_: Throwable) { }
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(root, params)
            vendorMenuView = root
            updateVisibilityByPref()
        } catch (_: Throwable) { }
    }

    private fun hideVendorMenu() {
        vendorMenuView?.let { v ->
            try {
                fun cancelAllAnim(view: View) {
                    try { view.animate().cancel() } catch (_: Throwable) { }
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) cancelAllAnim(view.getChildAt(i))
                    }
                }
                cancelAllAnim(v)
                try { windowManager.removeView(v) } catch (_: Throwable) { }
            } catch (_: Throwable) { }
            vendorMenuView = null
            updateVisibilityByPref()
        } ?: run { updateVisibilityByPref() }
    }

    // 获取悬浮球中心点（容错：lp 可能为 null 时回退到上次持久化位置与默认大小）
    private fun getBallCenterSnapshot(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val sizeDp = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (lp?.width ?: dp(sizeDp))
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (lp?.height ?: dp(sizeDp))
        val px = lp?.x ?: run { try { prefs.floatingBallPosX } catch (_: Throwable) { (dm.widthPixels - vw) / 2 } }
        val py = lp?.y ?: run { try { prefs.floatingBallPosY } catch (_: Throwable) { (dm.heightPixels - vh) / 2 } }
        return (px + vw / 2) to (py + vh / 2)
    }

    private fun onPickPromptPresetFromMenu() {
        // 打开子面板期间保持可见，避免 lp 在可见性刷新时被清空
        touchActiveGuard = true
        hideVendorMenu()
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { hideVendorMenu() }
        }
        root.alpha = currentUiAlpha()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@FloatingAsrService, R.drawable.bg_panel_round)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        val title = android.widget.TextView(this).apply {
            text = getString(R.string.label_llm_prompt_presets)
            setTextColor(0xFF111111.toInt())
            textSize = 16f
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(title, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))

        val presets = try { prefs.getPromptPresets() } catch (_: Throwable) { emptyList() }
        val active = try { prefs.activePromptId } catch (_: Throwable) { "" }
        presets.forEach { p ->
            val tv = android.widget.TextView(this).apply {
                text = if (p.id == active) "✓  ${p.title}" else p.title
                setTextColor(0xFF222222.toInt())
                textSize = 14f
                setPadding(dp(6), dp(8), dp(6), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        prefs.activePromptId = p.id
                        android.widget.Toast.makeText(this@FloatingAsrService, getString(R.string.switched_preset, p.title), android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) { }
                    hideVendorMenu()
                }
            }
            container.addView(tv, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val paramsContainer = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(container, paramsContainer)
        val (cx, cy) = getBallCenterSnapshot()
        val isLeft = cx < (resources.displayMetrics.widthPixels / 2)
        container.alpha = 0f
        container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()
        container.post {
            try {
                val dm = resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels
                val offset = dp(16)
                val w = container.width
                val h = container.height
                val lpC = container.layoutParams as android.widget.FrameLayout.LayoutParams
                val left = if (isLeft) (cx + offset) else (cx - offset - w)
                val top = cy - h / 2
                lpC.leftMargin = left.coerceIn(0, (screenW - w).coerceAtLeast(0))
                lpC.topMargin = top.coerceIn(0, (screenH - h).coerceAtLeast(0))
                container.layoutParams = lpC
                try { container.animate().alpha(1f).translationX(0f).setDuration(160).start() } catch (_: Throwable) { }
            } catch (_: Throwable) { }
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(root, params)
            vendorMenuView = root
            touchActiveGuard = false
            updateVisibilityByPref()
        } catch (_: Throwable) { }
    }

    private fun uploadClipboardOnceFromMenu() {
        try {
            val mgr = SyncClipboardManager(this, prefs, serviceScope)
            serviceScope.launch(Dispatchers.IO) {
                val ok = try { mgr.uploadOnce() } catch (t: Throwable) { false }
                handler.post {
                    try {
                        android.widget.Toast.makeText(
                            this@FloatingAsrService,
                            getString(if (ok) R.string.sc_status_uploaded else R.string.sc_test_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Throwable) { }
                }
            }
        } catch (_: Throwable) { }
    }

    private fun pullClipboardOnceFromMenu() {
        try {
            val mgr = SyncClipboardManager(this, prefs, serviceScope)
            serviceScope.launch(Dispatchers.IO) {
                val ok = try { mgr.pullNow(updateClipboard = true).first } catch (t: Throwable) { false }
                handler.post {
                    try {
                        android.widget.Toast.makeText(
                            this@FloatingAsrService,
                            getString(if (ok) R.string.sc_test_success else R.string.sc_test_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Throwable) { }
                }
            }
        } catch (_: Throwable) { }
    }

    private fun snapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val centerX = p.x + vw / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)

        p.x = targetX
        p.y = targetY
        try { windowManager.updateViewLayout(ballView ?: v, p) } catch (_: Throwable) { }
        persistBallPosition()
    }

    private fun animateSnapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val centerX = p.x + vw / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)

        val startX = p.x
        val startY = p.y
        val dx = targetX - startX
        val dy = targetY - startY

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                p.x = (startX + dx * f).toInt()
                p.y = (startY + dy * f).toInt()
                try { windowManager.updateViewLayout(ballView ?: v, p) } catch (_: Throwable) { }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                }
            })
            start()
        }
    }

    private fun persistBallPosition() {
        val p = lp ?: return
        try {
            prefs.floatingBallPosX = p.x
            prefs.floatingBallPosY = p.y
        } catch (_: Throwable) { }
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }

    /**
     * 根据底层错误信息粗粒度归类，映射为更友好的悬浮球提示。
     * 不改变上游 API，仅在 UI 侧做字符串判定与分类，保证最小改动。
     */
    private fun mapErrorToFriendlyMessage(raw: String): String? {
        if (raw.isEmpty()) return null
        val lower = raw.lowercase(Locale.ROOT)

        // 空结果（引擎可能已用 error_asr_empty_result 报错）
        try {
            val emptyHints = listOf(
                getString(R.string.error_asr_empty_result),
                getString(R.string.error_audio_empty),
                "empty asr result",
                "empty audio",
                "识别返回为空",
                "空音频"
            )
            if (emptyHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_empty_result)
            }
        } catch (_: Throwable) { }

        // HTTP 状态码分类（401/403）
        try {
            val httpCode = Regex("HTTP\\s+(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when (httpCode) {
                401 -> return getString(R.string.asr_error_auth_invalid)
                403 -> return getString(R.string.asr_error_auth_forbidden)
            }
        } catch (_: Throwable) { }

        // WebSocket/通用 code 提示（如：ASR Error 401）
        try {
            val code = Regex("(?:ASR\\s*Error|status|code)\\s*(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when (code) {
                401 -> return getString(R.string.asr_error_auth_invalid)
                403 -> return getString(R.string.asr_error_auth_forbidden)
            }
        } catch (_: Throwable) { }

        // 录音权限
        try {
            val permHints = listOf(
                getString(R.string.error_record_permission_denied),
                getString(R.string.hint_need_permission),
                "record audio permission"
            )
            if (permHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_mic_permission_denied)
            }
        } catch (_: Throwable) { }

        // 麦克风被占用/录音初始化失败（常见为被占用导致的初始化失败或 read 错误）
        try {
            val micBusyHints = listOf(
                getString(R.string.error_audio_init_failed),
                "audio recorder busy",
                "resource busy",
                "in use",
                "device busy"
            )
            if (micBusyHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_mic_in_use)
            }
        } catch (_: Throwable) { }

        // 握手失败（SSL/TLS、证书）
        if (lower.contains("handshake") || lower.contains("sslhandshakeexception") || lower.contains("trust anchor") || lower.contains("certificate")) {
            return getString(R.string.asr_error_network_handshake)
        }

        // 网络不可用/连接失败/超时
        if (
            lower.contains("unable to resolve host") ||
            lower.contains("no address associated") ||
            lower.contains("failed to connect") ||
            lower.contains("connect exception") ||
            lower.contains("network is unreachable") ||
            lower.contains("software caused connection abort") ||
            lower.contains("timeout") ||
            lower.contains("timed out")
        ) {
            return getString(R.string.asr_error_network_unavailable)
        }

        return null
    }
}
