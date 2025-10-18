package com.brycewg.asrkb.ui

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.clipboard.SyncClipboardManager
import com.brycewg.asrkb.LocaleHelper

/**
 * 悬浮球：当当前输入法不是本应用 IME 时显示，点击快速呼出系统输入法选择器，
 * 方便用户切换到 ASR 键盘。
 */
class FloatingImeSwitcherService : Service() {

    override fun attachBaseContext(newBase: android.content.Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    companion object {
        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_HIDE"
        const val ACTION_HINT_IME_VISIBLE = "com.brycewg.asrkb.action.IME_VISIBLE"
        const val ACTION_HINT_IME_HIDDEN = "com.brycewg.asrkb.action.IME_HIDDEN"
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var imeVisible: Boolean = false
    // 轮盘菜单与供应商选择面板
    private var radialMenuView: View? = null
    private var vendorMenuView: View? = null
    // 悬浮球移动模式：开启后可直接拖动，点按一次退出
    private var moveModeEnabled: Boolean = false
    // 触摸期间的可见性保护（首轮长按出现轮盘时防止被隐藏）
    private var touchActiveGuard: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            updateBallVisibility()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        // 监听系统当前输入法变化
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                settingsObserver
            )
        } catch (_: Throwable) { }
        // 监听无障碍服务发来的“IME显示/隐藏”提示
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(ACTION_HINT_IME_VISIBLE)
                addAction(ACTION_HINT_IME_HIDDEN)
            }
            registerReceiver(hintReceiver, filter)
        } catch (_: Throwable) { }
        updateBallVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> updateBallVisibility()
            ACTION_HIDE -> removeBall()
            ACTION_HINT_IME_VISIBLE -> {
                imeVisible = true
                updateBallVisibility()
            }
            ACTION_HINT_IME_HIDDEN -> {
                imeVisible = false
                updateBallVisibility()
            }
            else -> updateBallVisibility()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { contentResolver.unregisterContentObserver(settingsObserver) } catch (_: Throwable) { }
        try { unregisterReceiver(hintReceiver) } catch (_: Throwable) { }
        hideRadialMenu()
        hideVendorMenu()
        removeBall()
        // 取消后台任务
        try { serviceScope.cancel() } catch (_: Throwable) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun updateBallVisibility() {
        val prefs = Prefs(this)
        val forceVisible = (radialMenuView != null || vendorMenuView != null || moveModeEnabled || touchActiveGuard)
        if (!prefs.floatingSwitcherEnabled || !hasOverlayPermission()) {
            removeBall()
            return
        }

        // 如果启用了悬浮球语音识别模式,则不显示切换输入法的悬浮球
        if (prefs.floatingAsrEnabled) {
            removeBall()
            return
        }

        // 交互时强制显示
        if (forceVisible) {
            ensureBall()
            applyBallSize()
            applyBallAlpha()
            return
        }

        // 开启“仅在输入法面板显示时显示”，但当前未检测到输入场景 -> 隐藏
        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible) {
            removeBall()
            return
        }

        if (isOurImeCurrent()) {
            removeBall()
            return
        }
        ensureBall()
        applyBallSize()
        applyBallAlpha()
    }

    private val hintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_HINT_IME_VISIBLE -> {
                    imeVisible = true
                    updateBallVisibility()
                }
                ACTION_HINT_IME_HIDDEN -> {
                    imeVisible = false
                    updateBallVisibility()
                }
            }
        }
    }

    private fun ensureBall() {
        if (ballView != null) return
        val iv = ImageView(this)
        iv.setImageResource(R.drawable.logo)
        iv.clearColorFilter()
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iv.background = ContextCompat.getDrawable(this, R.drawable.bg_floating_ball)
        val pad = dp(6)
        iv.setPadding(pad, pad, pad, pad)
        iv.contentDescription = getString(R.string.cd_floating_switcher)
        iv.setOnClickListener {
            hapticTap(iv)
            onBallClick()
        }
        attachDrag(iv)

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val size = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 28 }
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
            val prefs = Prefs(this)
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
            windowManager.addView(iv, params)
            ballView = iv
            lp = params
        } catch (_: Throwable) { }
        applyBallAlpha()
    }

    private fun removeBall() {
        val v = ballView ?: return
        try { persistBallPosition() } catch (_: Throwable) { }
        try { windowManager.removeView(v) } catch (_: Throwable) { }
        ballView = null
        lp = null
    }

    private fun applyBallAlpha() {
        val a = try { Prefs(this).floatingSwitcherAlpha } catch (_: Throwable) { 1.0f }
        val v = ballView
        if (v != null) {
            try { v.alpha = a } catch (_: Throwable) { }
        }
    }

    private fun applyBallSize() {
        val v = ballView ?: return
        val p = lp ?: return
        val size = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 28 }
        p.width = dp(size)
        p.height = dp(size)
        try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
    }

    private fun onBallClick() {
        // 若处于移动模式：点按退出移动模式，不触发其他动作
        if (moveModeEnabled) {
            moveModeEnabled = false
            // 退出时吸附到边缘
            ballView?.let { try { animateSnapToEdge(it) } catch (_: Throwable) { snapToEdge(it) } }
            try { persistBallPosition() } catch (_: Throwable) { }
            // 隐藏可能存在的菜单
            hideRadialMenu()
            hideVendorMenu()
            return
        }
        // 目标：尽量一键切到我们的键盘；若不可行，唤起系统输入法选择器
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                // 未启用 -> 打开系统设置页让用户启用
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
                return
            }
            // 从后台直接调 showInputMethodPicker 可能被系统忽略，改为启动透明 Activity 置前后再调
            val intent = Intent(this, ImePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            startActivity(intent)
        } catch (_: Exception) {
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

    private fun isOurImeCurrent(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val expectedId = "$packageName/.ime.AsrKeyboardService"
            current == expectedId
        } catch (_: Throwable) {
            false
        }
    }

    private var edgeAnimator: ValueAnimator? = null
    private fun currentUiAlpha(): Float = try { Prefs(this).floatingSwitcherAlpha } catch (_: Throwable) { 1f }

    private fun attachDrag(target: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var isDragging = false
        val touchSlop = dp(4)
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var longPressPosted = false
        var longActionFired = false
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
                    // 停止吸附动画，准备新的拖动
                    edgeAnimator?.cancel()
                    moved = false
                    isDragging = moveModeEnabled
                    longActionFired = false
                    touchActiveGuard = true
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
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
                        // 若在长按触发前移动过远，则取消长按判定
                        if (moved && longPressPosted) {
                            handler.removeCallbacks(longPressRunnable)
                            longPressPosted = false
                        }
                        return@setOnTouchListener true
                    }

                    // 拖动中：更新位置并限制在屏幕范围内
                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels
                    val vw = if (v.width > 0) v.width else p.width
                    val vh = if (v.height > 0) v.height else p.height
                    val nx = (startX + dx).coerceIn(0, screenW - vw)
                    val ny = (startY + dy).coerceIn(0, screenH - vh)
                    p.x = nx
                    p.y = ny
                    try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressPosted) {
                        handler.removeCallbacks(longPressRunnable)
                        longPressPosted = false
                    }
                    if (longActionFired) {
                        // 已触发长按动作，抬起时不触发点击
                    } else if (isDragging) {
                        // 移动模式下：未移动当作点击（退出移动），已移动则吸附
                        if (!moved) {
                            v.performClick()
                        } else {
                            try { animateSnapToEdge(v) } catch (_: Throwable) { snapToEdge(v) }
                        }
                    } else if (!moved) {
                        v.performClick()
                    }
                    moved = false
                    isDragging = false
                    longActionFired = false
                    touchActiveGuard = false
                    updateBallVisibility()
                    true
                }
                else -> false
            }
        }
    }

    private fun showRadialMenu() {
        if (radialMenuView != null) return
        val p = lp ?: return
        // 整屏透明可点按层，用于点击空白处关闭
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { hideRadialMenu() }
        }
        root.alpha = currentUiAlpha()
        val isLeft = isBallOnLeft()
        // 6 个功能：顶部 Prompt 切换 + 原 4 项 + 底部剪贴板同步（两者仅为 UI 占位）
        val items = listOf(
            RadialItem(R.drawable.ic_prompt, getString(R.string.label_radial_switch_prompt), getString(R.string.label_radial_switch_prompt)) { onPickPromptPresetFromMenu() },
            RadialItem(R.drawable.ic_waveform, getString(R.string.label_radial_switch_asr), getString(R.string.label_radial_switch_asr)) { onPickAsrVendor() },
            RadialItem(R.drawable.ic_keyboard, getString(R.string.label_radial_switch_ime), getString(R.string.label_radial_switch_ime)) { invokeImePickerFromMenu() },
            RadialItem(R.drawable.ic_move, getString(R.string.label_radial_move), getString(R.string.label_radial_move)) { enableMoveModeFromMenu() },
            RadialItem(
                if (try { Prefs(this).postProcessEnabled } catch (_: Throwable) { false }) R.drawable.ic_star_filled else R.drawable.ic_star_outline,
                getString(R.string.label_radial_postproc),
                getString(R.string.label_radial_postproc)
            ) { togglePostprocFromMenu() },
            // 一次性上传/拉取粘贴板
            RadialItem(R.drawable.ic_stat_upload, getString(R.string.label_radial_clipboard_upload), getString(R.string.label_radial_clipboard_upload)) { uploadClipboardOnceFromMenu() },
            RadialItem(R.drawable.ic_stat_download, getString(R.string.label_radial_clipboard_pull), getString(R.string.label_radial_clipboard_pull)) { pullClipboardOnceFromMenu() }
        )
        // 改为垂直排布：统一承载在纵向容器
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@FloatingImeSwitcherService, R.drawable.bg_panel_round)
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
                // 入场动画
                container.animate().alpha(1f).translationX(0f).setDuration(160).start()
            } catch (_: Throwable) { }
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 拦截触摸，点击空白处可关闭
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(root, params)
            radialMenuView = root
            updateBallVisibility()
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
            updateBallVisibility()
        } ?: run { updateBallVisibility() }
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
        iv.setOnClickListener { hapticTap(iv); onClick() }
        return iv
    }

    private fun buildCapsule(iconRes: Int, label: String, cd: String, onClick: () -> Unit): View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@FloatingImeSwitcherService, R.drawable.ripple_capsule)
            val p = dp(10)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            contentDescription = cd
            setOnClickListener { hapticTap(this); onClick() }
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
        val vw = ballView?.width ?: p.width
        val centerX = p.x + vw / 2
        return centerX < w / 2
    }

    private fun invokeImePickerFromMenu() {
        // 打开系统输入法选择器
        hideVendorMenu()
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
                return
            }
            val intent = Intent(this, ImePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
            } catch (_: Throwable) { }
        }
    }

    private fun enableMoveModeFromMenu() {
        moveModeEnabled = true
        hideVendorMenu()
        // 提示用户：再次点击悬浮球退出移动
        try {
            android.widget.Toast.makeText(this, getString(R.string.toast_move_mode_on), android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) { }
        updateBallVisibility()
    }

    private fun togglePostprocFromMenu() {
        try {
            val prefs = Prefs(this)
            val newVal = !prefs.postProcessEnabled
            prefs.postProcessEnabled = newVal
            val msg = getString(R.string.status_postproc, if (newVal) getString(R.string.toggle_on) else getString(R.string.toggle_off))
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) { }
    }

    private fun onPickAsrVendor() {
        // 打开子面板期间保持可见，避免 lp 在可见性刷新时被清空
        touchActiveGuard = true
        hideVendorMenu()
        // 构造供应商列表面板
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { hideVendorMenu() }
        }
        root.alpha = currentUiAlpha()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@FloatingImeSwitcherService, R.drawable.bg_panel_round)
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
            com.brycewg.asrkb.asr.AsrVendor.Volc to getString(R.string.vendor_volc),
            com.brycewg.asrkb.asr.AsrVendor.SiliconFlow to getString(R.string.vendor_sf),
            com.brycewg.asrkb.asr.AsrVendor.ElevenLabs to getString(R.string.vendor_eleven),
            com.brycewg.asrkb.asr.AsrVendor.OpenAI to getString(R.string.vendor_openai),
            com.brycewg.asrkb.asr.AsrVendor.DashScope to getString(R.string.vendor_dashscope),
            com.brycewg.asrkb.asr.AsrVendor.Gemini to getString(R.string.vendor_gemini),
            com.brycewg.asrkb.asr.AsrVendor.Soniox to getString(R.string.vendor_soniox),
            com.brycewg.asrkb.asr.AsrVendor.SenseVoice to getString(R.string.vendor_sensevoice)
        )
        val cur = try { Prefs(this).asrVendor } catch (_: Throwable) { com.brycewg.asrkb.asr.AsrVendor.Volc }
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
                        val prefs = Prefs(this@FloatingImeSwitcherService)
                        val old = try { prefs.asrVendor } catch (_: Throwable) { com.brycewg.asrkb.asr.AsrVendor.Volc }
                        if (v != old) {
                            prefs.asrVendor = v
                            // 离开 SenseVoice -> 卸载；切回 SenseVoice -> 若开启预加载则预热
                            if (old == com.brycewg.asrkb.asr.AsrVendor.SenseVoice && v != com.brycewg.asrkb.asr.AsrVendor.SenseVoice) {
                                try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
                            } else if (v == com.brycewg.asrkb.asr.AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                                // 预加载放到后台，避免阻塞触控/动画线程
                                serviceScope.launch(Dispatchers.Default) {
                                    try { com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(this@FloatingImeSwitcherService, prefs) } catch (_: Throwable) { }
                                }
                            }
                        }
                        android.widget.Toast.makeText(this@FloatingImeSwitcherService, name, android.widget.Toast.LENGTH_SHORT).show()
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
        // 先添加，待测量后再根据左右位置与尺寸精确定位，避免越界与遮挡
        root.addView(container, paramsContainer)
        val (cx, cy) = getBallCenterSnapshot()
        val isLeft = cx < (resources.displayMetrics.widthPixels / 2)
        // 入场动画：从靠近悬浮球一侧轻微平移并淡入
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
                try {
                    container.animate().alpha(1f).translationX(0f).setDuration(160).start()
                } catch (_: Throwable) { }
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
            updateBallVisibility()
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
            updateBallVisibility()
        } ?: run { updateBallVisibility() }
    }

    // 获取悬浮球中心点（容错：lp 可能为 null 时使用持久化位置与默认大小）
    private fun getBallCenterSnapshot(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val sizeDp = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 44 }
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (lp?.width ?: dp(sizeDp))
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (lp?.height ?: dp(sizeDp))
        val px = lp?.x ?: run { try { Prefs(this).floatingBallPosX } catch (_: Throwable) { (dm.widthPixels - vw) / 2 } }
        val py = lp?.y ?: run { try { Prefs(this).floatingBallPosY } catch (_: Throwable) { (dm.heightPixels - vh) / 2 } }
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
            background = ContextCompat.getDrawable(this@FloatingImeSwitcherService, R.drawable.bg_panel_round)
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

        val prefs = Prefs(this)
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
                        Prefs(this@FloatingImeSwitcherService).activePromptId = p.id
                        android.widget.Toast.makeText(this@FloatingImeSwitcherService, getString(R.string.switched_preset, p.title), android.widget.Toast.LENGTH_SHORT).show()
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
            updateBallVisibility()
        } catch (_: Throwable) { }
    }

    private fun uploadClipboardOnceFromMenu() {
        try {
            val prefs = Prefs(this)
            val mgr = SyncClipboardManager(this, prefs, serviceScope)
            serviceScope.launch(Dispatchers.IO) {
                val ok = try { mgr.uploadOnce() } catch (t: Throwable) { false }
                handler.post {
                    try {
                        android.widget.Toast.makeText(
                            this@FloatingImeSwitcherService,
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
            val prefs = Prefs(this)
            val mgr = SyncClipboardManager(this, prefs, serviceScope)
            serviceScope.launch(Dispatchers.IO) {
                val ok = try { mgr.pullNow(updateClipboard = true).first } catch (t: Throwable) { false }
                handler.post {
                    try {
                        android.widget.Toast.makeText(
                            this@FloatingImeSwitcherService,
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
        val def = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 28 }
        val vw = if (v.width > 0) v.width else dp(def)
        val vh = if (v.height > 0) v.height else dp(def)
        val margin = dp(0)

        // 计算目标X：吸附到左或右
        val centerX = p.x + vw / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
        // 约束Y范围，避免越界
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)

        p.x = targetX
        p.y = targetY
        try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
        persistBallPosition()
    }

    private fun animateSnapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 28 }
        val vw = if (v.width > 0) v.width else dp(def)
        val vh = if (v.height > 0) v.height else dp(def)
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
                try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
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
            val prefs = Prefs(this)
            prefs.floatingBallPosX = p.x
            prefs.floatingBallPosY = p.y
        } catch (_: Throwable) { }
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }

    private fun hapticTap(view: View?) {
        try {
            if (Prefs(this).micHapticEnabled) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) { }
    }
}
