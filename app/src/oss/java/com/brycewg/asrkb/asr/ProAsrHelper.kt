package com.brycewg.asrkb.asr

import android.content.Context

/**
 * OSS 变体占位：提供与 Pro 版相同的 API，但不注入任何额外上下文/热词。
 * 这样主工程可以无条件调用，而不会在 OSS 变体产生依赖错误。
 */
object ProAsrHelper {
    fun buildPromptWithContext(
        context: Context,
        basePrompt: String,
        compact: Boolean = false
    ): String {
        return basePrompt
    }

    fun buildVolcContext(context: Context): String? {
        return null
    }
}

