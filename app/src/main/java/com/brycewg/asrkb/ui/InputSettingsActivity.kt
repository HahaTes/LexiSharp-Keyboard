package com.brycewg.asrkb.ui

import android.os.Bundle
import android.view.View
import android.view.HapticFeedbackConstants
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.materialswitch.MaterialSwitch
import android.content.Intent

class InputSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_settings)

        val prefs = Prefs(this)

        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchAutoSwitchPassword = findViewById<MaterialSwitch>(R.id.switchAutoSwitchPassword)
        val switchMicHaptic = findViewById<MaterialSwitch>(R.id.switchMicHaptic)
        val switchMicTapToggle = findViewById<MaterialSwitch>(R.id.switchMicTapToggle)
        val switchSwapAiEditWithSwitcher = findViewById<MaterialSwitch>(R.id.switchSwapAiEditWithSwitcher)
        val switchHideRecentTasks = findViewById<MaterialSwitch>(R.id.switchHideRecentTasks)
        val spKeyboardHeight = findViewById<Spinner>(R.id.spKeyboardHeight)
        val spLanguage = findViewById<Spinner>(R.id.spLanguage)

        fun applyPrefsToUi() {
            switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
            switchAutoSwitchPassword.isChecked = prefs.autoSwitchOnPassword
            switchMicHaptic.isChecked = prefs.micHapticEnabled
            switchMicTapToggle.isChecked = prefs.micTapToggleEnabled
            switchSwapAiEditWithSwitcher.isChecked = prefs.swapAiEditWithImeSwitcher
            switchHideRecentTasks.isChecked = prefs.hideRecentTaskCard
        }
        applyPrefsToUi()

        // 键盘高度：三档
        val kbHeightOptions = arrayOf(
            getString(R.string.keyboard_height_small),
            getString(R.string.keyboard_height_medium),
            getString(R.string.keyboard_height_large)
        )
        spKeyboardHeight.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, kbHeightOptions)
        spKeyboardHeight.setSelection((prefs.keyboardHeightTier - 1).coerceIn(0, 2))

        // 应用语言选择
        val languageItems = listOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh_cn),
            getString(R.string.lang_en)
        )
        spLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageItems)
        val savedTag = prefs.appLanguageTag
        spLanguage.setSelection(
            when (savedTag) {
                "zh", "zh-CN", "zh-Hans" -> 1
                "en" -> 2
                else -> 0
            }
        )

        spLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newTag = when (position) {
                    1 -> "zh-CN"
                    2 -> "en"
                    else -> "" // 跟随系统
                }
                if (newTag != prefs.appLanguageTag) {
                    prefs.appLanguageTag = newTag
                    val locales = if (newTag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(newTag)
                    AppCompatDelegate.setApplicationLocales(locales)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 监听与保存
        switchTrimTrailingPunct.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.trimFinalTrailingPunct = isChecked
        }
        switchAutoSwitchPassword.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.autoSwitchOnPassword = isChecked
        }
        switchMicHaptic.setOnCheckedChangeListener { btn, isChecked ->
            prefs.micHapticEnabled = isChecked
            try { btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) { }
        }
        switchMicTapToggle.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.micTapToggleEnabled = isChecked
        }
        switchSwapAiEditWithSwitcher.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.swapAiEditWithImeSwitcher = isChecked
            try { sendBroadcast(Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI)) } catch (_: Throwable) { }
        }
        switchHideRecentTasks.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.hideRecentTaskCard = isChecked
            applyExcludeFromRecents(isChecked)
        }

        // 键盘高度
        spKeyboardHeight.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tier = (position + 1).coerceIn(1, 3)
                if (prefs.keyboardHeightTier != tier) {
                    prefs.keyboardHeightTier = tier
                    try { sendBroadcast(Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI)) } catch (_: Throwable) { }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 初始应用一次“从最近任务中排除”设置
        applyExcludeFromRecents(prefs.hideRecentTaskCard)
    }

    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (Prefs(this).micHapticEnabled) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) { }
    }

    private fun applyExcludeFromRecents(enabled: Boolean) {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
        } catch (_: Throwable) { }
    }
}

