package org.siongdict.app.data

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Formats IPA by converting the trailing tone category number into
 * Unicode tone contour letters, using the dialect's tone system JSON
 * from the info table.
 *
 * Example: "tən1" with tone system {"1": ["334", ...], ...}
 *   -> "tən˧˧˦1"
 */
object ToneFormatter {

    // Regex: group 1 = syllable (non-digit prefix), group 2 = tone category
    private val pattern = Pattern.compile("^(.+?)([0-9]{1,2}[a-z=]?)$")

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
            if (c >= '0' && c <= '6') {
                sb.append(toneBars[c - '0'])
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Format an IPA string using the given tone system JSON.
     *
     * @param ipa        raw IPA from the langs table, e.g. "tən1"
     * @param toneSystem parsed 聲調 JSON for the dialect, or null
     * @return formatted IPA, e.g. "tən˧˧˦1"
     */
    fun format(ipa: String, toneSystem: JSONObject?): String {
        if (toneSystem == null) return ipa
        if (ipa.length < 2) return ipa
        if (Character.isDigit(ipa[0])) return ipa

        val matcher = pattern.matcher(ipa)
        if (!matcher.matches()) return ipa

        val base = matcher.group(1)
        val tone = matcher.group(2)
        if (tone.isNullOrEmpty()) return ipa

        // Look up the tone category in the tone system JSON
        val styles = try {
            toneSystem.optJSONArray(tone)
        } catch (e: Exception) {
            null
        }

        // If not found in the tone system, fall back to raw IPA
        if (styles == null || styles.length() != 5) {
            if (tone == "0") return base
            return base + tone
        }

        // styles[0] = tone value (e.g. "334")
        val tv = styles.optString(0, "")
        if (tv.isEmpty()) {
            if (tone == "0") return base
            return base + tone
        }

        val contour = toneValueToContour(tv)
        return base + contour + tone
    }
}
