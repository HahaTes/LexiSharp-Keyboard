package com.brycewg.asrkb.ui.settings.asr

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for ASR Settings screen, managing all state and business logic.
 * UI observes StateFlows and reacts to state changes automatically.
 */
class AsrSettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AsrSettingsUiState())
    val uiState: StateFlow<AsrSettingsUiState> = _uiState.asStateFlow()

    private lateinit var prefs: Prefs
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        loadInitialState()
    }

    private fun loadInitialState() {
        fun isQwenOmniModel(model: String): Boolean {
            return model.startsWith("Qwen/Qwen3-Omni-30B-A3B-")
        }
        _uiState.value = AsrSettingsUiState(
            selectedVendor = prefs.asrVendor,
            autoStopSilenceEnabled = prefs.autoStopOnSilenceEnabled,
            silenceWindowMs = prefs.autoStopSilenceWindowMs,
            silenceSensitivity = prefs.autoStopSilenceSensitivity,
            aiEditPreferLastAsr = prefs.aiEditDefaultToLastAsr,
            // Volc settings
            volcStreamingEnabled = prefs.volcStreamingEnabled,
            volcDdcEnabled = prefs.volcDdcEnabled,
            volcVadEnabled = prefs.volcVadEnabled,
            volcNonstreamEnabled = prefs.volcNonstreamEnabled,
            volcFirstCharAccelEnabled = prefs.volcFirstCharAccelEnabled,
            volcLanguage = prefs.volcLanguage,
            // DashScope settings
            dashStreamingEnabled = prefs.dashStreamingEnabled,
            // SiliconFlow settings
            sfUseOmni = isQwenOmniModel(prefs.sfModel.ifBlank { com.brycewg.asrkb.store.Prefs.DEFAULT_SF_MODEL }),
            // OpenAI settings
            oaAsrUsePrompt = prefs.oaAsrUsePrompt,
            // Soniox settings
            sonioxStreamingEnabled = prefs.sonioxStreamingEnabled,
            sonioxLanguages = prefs.getSonioxLanguages(),
            // SenseVoice settings
            svModelVariant = prefs.svModelVariant,
            svNumThreads = prefs.svNumThreads,
            svLanguage = prefs.svLanguage,
            svUseItn = prefs.svUseItn,
            svPreloadEnabled = prefs.svPreloadEnabled,
            svPseudoStreamingEnabled = prefs.svPseudoStreamingEnabled,
            svKeepAliveMinutes = prefs.svKeepAliveMinutes
        )
    }

    fun updateVendor(vendor: AsrVendor) {
        val oldVendor = prefs.asrVendor
        prefs.asrVendor = vendor
        _uiState.value = _uiState.value.copy(selectedVendor = vendor)

        // Handle SenseVoice model lifecycle
        if (oldVendor == AsrVendor.SenseVoice && vendor != AsrVendor.SenseVoice) {
            try {
                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload SenseVoice recognizer", e)
            }
        }

        if (vendor == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload SenseVoice model", e)
                }
            }
        }
    }

    fun updateAutoStopSilence(enabled: Boolean) {
        prefs.autoStopOnSilenceEnabled = enabled
        _uiState.value = _uiState.value.copy(autoStopSilenceEnabled = enabled)
    }

    fun updateSilenceWindow(windowMs: Int) {
        prefs.autoStopSilenceWindowMs = windowMs
        _uiState.value = _uiState.value.copy(silenceWindowMs = windowMs)
    }

    fun updateSilenceSensitivity(sensitivity: Int) {
        prefs.autoStopSilenceSensitivity = sensitivity
        _uiState.value = _uiState.value.copy(silenceSensitivity = sensitivity)
    }

    fun updateVolcStreaming(enabled: Boolean) {
        prefs.volcStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(volcStreamingEnabled = enabled)
    }

    fun updateVolcDdc(enabled: Boolean) {
        prefs.volcDdcEnabled = enabled
        _uiState.value = _uiState.value.copy(volcDdcEnabled = enabled)
    }

    fun updateVolcVad(enabled: Boolean) {
        prefs.volcVadEnabled = enabled
        _uiState.value = _uiState.value.copy(volcVadEnabled = enabled)
    }

    fun updateVolcNonstream(enabled: Boolean) {
        prefs.volcNonstreamEnabled = enabled
        _uiState.value = _uiState.value.copy(volcNonstreamEnabled = enabled)
    }

    fun updateDashStreaming(enabled: Boolean) {
        prefs.dashStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(dashStreamingEnabled = enabled)
    }

    fun updateVolcFirstCharAccel(enabled: Boolean) {
        prefs.volcFirstCharAccelEnabled = enabled
        _uiState.value = _uiState.value.copy(volcFirstCharAccelEnabled = enabled)
    }

    fun updateVolcLanguage(language: String) {
        prefs.volcLanguage = language
        _uiState.value = _uiState.value.copy(volcLanguage = language)
    }

    fun updateSfUseOmni(enabled: Boolean) {
        prefs.sfUseOmni = enabled
        _uiState.value = _uiState.value.copy(sfUseOmni = enabled)
    }

    fun updateOpenAiUsePrompt(enabled: Boolean) {
        prefs.oaAsrUsePrompt = enabled
        _uiState.value = _uiState.value.copy(oaAsrUsePrompt = enabled)
    }

    fun updateSonioxStreaming(enabled: Boolean) {
        prefs.sonioxStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(sonioxStreamingEnabled = enabled)
    }

    fun updateSonioxLanguages(languages: List<String>) {
        prefs.setSonioxLanguages(languages)
        _uiState.value = _uiState.value.copy(sonioxLanguages = languages)
    }

    fun updateSvModelVariant(variant: String) {
        prefs.svModelVariant = variant
        _uiState.value = _uiState.value.copy(svModelVariant = variant)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after variant change", e)
        }
    }

    fun updateSvNumThreads(threads: Int) {
        prefs.svNumThreads = threads
        _uiState.value = _uiState.value.copy(svNumThreads = threads)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after threads change", e)
        }
    }

    fun updateSvLanguage(language: String) {
        prefs.svLanguage = language
        _uiState.value = _uiState.value.copy(svLanguage = language)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after language change", e)
        }
    }

    fun updateSvUseItn(enabled: Boolean) {
        if (prefs.svUseItn != enabled) {
            prefs.svUseItn = enabled
            _uiState.value = _uiState.value.copy(svUseItn = enabled)
            try {
                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload SenseVoice recognizer after ITN change", e)
            }
        }
    }

    fun updateSvPreload(enabled: Boolean) {
        prefs.svPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(svPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.SenseVoice) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload SenseVoice model", e)
                }
            }
        }
    }

    fun updateSvPseudoStreaming(enabled: Boolean) {
        prefs.svPseudoStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(svPseudoStreamingEnabled = enabled)
    }

    fun updateSvKeepAlive(minutes: Int) {
        prefs.svKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(svKeepAliveMinutes = minutes)
    }

    fun checkSvModelDownloaded(context: Context): Boolean {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = File(base, "sensevoice")
        val variant = prefs.svModelVariant
        val dir = if (variant == "small-full") File(root, "small-full") else File(root, "small-int8")
        val modelDir = findModelDir(dir)
        return modelDir != null &&
                File(modelDir, "tokens.txt").exists() &&
                (File(modelDir, "model.int8.onnx").exists() || File(modelDir, "model.onnx").exists())
    }

    private fun findModelDir(root: File): File? {
        if (!root.exists()) return null
        val direct = File(root, "tokens.txt")
        if (direct.exists()) return root
        val subs = root.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory) {
                val t = File(f, "tokens.txt")
                if (t.exists()) return f
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AsrSettingsViewModel"
    }
}

/**
 * UI State for ASR Settings screen.
 * Contains all configuration values and visibility flags.
 */
data class AsrSettingsUiState(
    val selectedVendor: AsrVendor = AsrVendor.Volc,
    val autoStopSilenceEnabled: Boolean = false,
    val silenceWindowMs: Int = 1200,
    val silenceSensitivity: Int = 4,
    val aiEditPreferLastAsr: Boolean = false,
    // Volcengine settings
    val volcStreamingEnabled: Boolean = false,
    val volcDdcEnabled: Boolean = false,
    val volcVadEnabled: Boolean = false,
    val volcNonstreamEnabled: Boolean = false,
    val volcFirstCharAccelEnabled: Boolean = false,
    val volcLanguage: String = "",
    // DashScope settings
    val dashStreamingEnabled: Boolean = false,
    // SiliconFlow settings
    val sfUseOmni: Boolean = false,
    // OpenAI settings
    val oaAsrUsePrompt: Boolean = false,
    // Soniox settings
    val sonioxStreamingEnabled: Boolean = false,
    val sonioxLanguages: List<String> = emptyList(),
    // SenseVoice settings
    val svModelVariant: String = "small-int8",
    val svNumThreads: Int = 2,
    val svLanguage: String = "auto",
    val svUseItn: Boolean = true,
    val svPreloadEnabled: Boolean = false,
    val svPseudoStreamingEnabled: Boolean = false,
    val svKeepAliveMinutes: Int = -1
) {
    // Computed visibility properties based on selected vendor
    val isVolcVisible: Boolean get() = selectedVendor == AsrVendor.Volc
    val isSfVisible: Boolean get() = selectedVendor == AsrVendor.SiliconFlow
    val isElevenVisible: Boolean get() = selectedVendor == AsrVendor.ElevenLabs
    val isOpenAiVisible: Boolean get() = selectedVendor == AsrVendor.OpenAI
    val isDashVisible: Boolean get() = selectedVendor == AsrVendor.DashScope
    val isGeminiVisible: Boolean get() = selectedVendor == AsrVendor.Gemini
    val isSonioxVisible: Boolean get() = selectedVendor == AsrVendor.Soniox
    val isSenseVoiceVisible: Boolean get() = selectedVendor == AsrVendor.SenseVoice
}
