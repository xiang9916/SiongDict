package org.siongdict.app.data

import java.util.regex.Pattern

/**
 * Formats IPA by converting the trailing tone category number into
 * Unicode tone contour letters, using the dialect's tone system.
 *
 * Example: "tən1" with tone system {"1": ["334", ...], ...}
 *   -> "tən˧˧˦1"
 *
 * 本对象只接受已解析的 [ToneSystem]：JSON 解析在数据层完成，显示选择留在显示层
 * （见 docs/adr/0003、docs/adr/0006）。
 */
object ToneFormatter {

    // Regex: group 1 = syllable (non-digit prefix), group 2 = tone category
    private val pattern = Pattern.compile("^(.+?)([0-9]{1,2}[a-z=-]?)$")

    // MCPDict tone system arrays always carry 5 style fields; [0] is the tone value
    private const val TONE_STYLE_FIELD_COUNT = 5

    // Unicode tone bar characters for digits 0-6
    private val toneBars = charArrayOf(
        '\u0294', // 0 -> ʔ
        '\u02E9', // 1 -> ˩
        '\u02E8', // 2 -> ˨
        '\u02E7', // 3 -> ˧
        '\u02E6', // 4 -> ˦
        '\u02E5', // 5 -> ˥
        '\u02C0'  // 6 -> ˀ
    )

    // 已格式化过的音节部分会以这些调符结尾；再次格式化时原样返回（幂等）。
    private val contourChars = setOf('\u02E9', '\u02E8', '\u02E7', '\u02E6', '\u02E5')

    /**
     * Convert a tone value string (e.g. "334") to contour letters (e.g. "˧˧˦").
     * Handles "/" separator for alternative tones.
     */
    private fun toneValueToContour(tv: String): String {
        if (tv.contains("/")) {
            return tv.split("/").joinToString("/") { toneValueToContour(it) }
        }
        val sb = StringBuilder()
        for (c in tv) {
            if (c in '0'..'6') {
                sb.append(toneBars[c - '0'])
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Format an IPA string using the given tone system.
     *
     * @param ipa        raw IPA from the langs table, e.g. "tən1"
     * @param toneSystem parsed 聲調 value for the dialect, or null
     * @return formatted IPA, e.g. "tən˧˧˦1"
     */
    fun format(ipa: String, toneSystem: ToneSystem?): String {
        if (toneSystem == null) return ipa
        if (ipa.length < 2) return ipa
        if (Character.isDigit(ipa[0])) return ipa

        val matcher = pattern.matcher(ipa)
        if (!matcher.matches()) return ipa

        val base = matcher.group(1) ?: return ipa
        val tone = matcher.group(2)
        if (tone.isNullOrEmpty()) return ipa

        // 幂等：音节已带调符，视为已格式化，不再追加。
        if (base.last() in contourChars) return ipa

        // Split tone category digits from modifiers (-/=) for tone system lookup
        val toneDigit = tone.filter { it in '0'..'9' }
        val toneSuffix = tone.filter { it !in '0'..'9' }

        // Tone 0 (glottal/light tone) drops the digit when there is no tone system entry
        fun fallback() = if (toneDigit == "0") base else base + toneDigit + toneSuffix

        // Look up the tone category first by digit, then by the full key (e.g. "2a" for 湘鄉)
        val styles = toneSystem.styles[toneDigit] ?: toneSystem.styles[tone]
        if (styles == null || styles.size != TONE_STYLE_FIELD_COUNT) return fallback()

        // styles[0] = tone value (e.g. "334")
        val tv = styles[0]
        if (tv.isEmpty()) return fallback()

        return base + toneValueToContour(tv) + toneDigit + toneSuffix
    }
}
