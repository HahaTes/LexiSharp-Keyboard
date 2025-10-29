package com.brycewg.asrkb.util

/**
 * 文本清理工具
 * - 提供去除句末标点与 emoji 的统一实现，避免重复代码。
 */
object TextSanitizer {

  /**
   * 去除字符串末尾的标点和 emoji（包含组合序列）。
   * 规则：
   * - 剥离各类 Unicode 标点符号（含中英文常见标点）
   * - 剥离常见 emoji 范围，以及其修饰/连接符（ZWJ/变体选择符/肤色/标签/Keycap 组合）
   * - 支持从右往左按 code point 处理，避免截断 surrogate pair
   */
  fun trimTrailingPunctAndEmoji(s: String): String {
    if (s.isEmpty()) return s
    var end = s.length
    var removeNextKeycapBase = false // 处理 keycap 组合（例如 3️⃣ 的基字符）
    while (end > 0) {
      val cp = java.lang.Character.codePointBefore(s, end)
      val count = java.lang.Character.charCount(cp)

      val isPunct = when (java.lang.Character.getType(cp)) {
        java.lang.Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        java.lang.Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        java.lang.Character.OTHER_PUNCTUATION.toInt(),
        java.lang.Character.DASH_PUNCTUATION.toInt(),
        java.lang.Character.START_PUNCTUATION.toInt(),
        java.lang.Character.END_PUNCTUATION.toInt(),
        java.lang.Character.CONNECTOR_PUNCTUATION.toInt() -> true
        else -> false
      } || when (cp) {
        // 常见中文标点（补充）
        '，'.code, '。'.code, '！'.code, '？'.code, '；'.code, '、'.code, '：'.code -> true
        else -> false
      }

      // Emoji 相关：常见表情范围、肤色修饰符、ZWJ、变体选择符、旗帜、标签等
      val isEmojiCore = (
        (cp in 0x1F600..0x1F64F) || // Emoticons
        (cp in 0x1F300..0x1F5FF) || // Misc Symbols and Pictographs
        (cp in 0x1F680..0x1F6FF) || // Transport & Map
        (cp in 0x1F900..0x1F9FF) || // Supplemental Symbols and Pictographs
        (cp in 0x1FA70..0x1FAFF) || // Symbols & Pictographs Extended-A
        (cp in 0x1F1E6..0x1F1FF) || // Regional Indicator Symbols (flags)
        (cp in 0x2600..0x26FF) ||   // Misc symbols
        (cp in 0x2700..0x27BF)      // Dingbats
      )
      val isEmojiModifier = (cp in 0x1F3FB..0x1F3FF) // 肤色修饰符
      val isEmojiJoinerOrSelector = (cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E)
      val isEmojiTag = (cp in 0xE0020..0xE007F) // 标签序列（部分旗帜等）
      val isKeycapEncloser = (cp == 0x20E3) // 组合按键包围符

      val isEmojiPart = isEmojiCore || isEmojiModifier || isEmojiJoinerOrSelector || isEmojiTag || isKeycapEncloser

      val isKeycapBase = (cp in '0'.code..'9'.code) || cp == '#'.code || cp == '*'.code

      val shouldRemove = isPunct || isEmojiPart || (removeNextKeycapBase && isKeycapBase)
      if (!shouldRemove) break

      end -= count

      // 如果遇到 keycap 包围符（U+20E3），向前继续移除其基字符（数字/#/*）
      if (isKeycapEncloser) {
        removeNextKeycapBase = true
      } else if (!isEmojiJoinerOrSelector) {
        // 移除了实际字符（非连接/选择符）后，重置 keycap 基字符移除标志
        removeNextKeycapBase = false
      }
    }
    return if (end < s.length) s.substring(0, end) else s
  }
}
