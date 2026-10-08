package org.siongdict.app.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

/**
 * 繁→简的**显示层**转换（术语见 CONTEXT.md 的「簡體渲染」）。
 *
 * 只改显示：不动数据库、不改检索——繁简异体混搜由 `variants.json` 负责，两者互不相干。
 * 表随包分发在 `assets/t2s.json`，由 `tools/build_simplifier.py` 从 MCPDict 自带的
 * OpenCC 表生成（词级例外表优先、字级兜底）；为什么不走运行时算法见
 * `docs/adr/0002-script-variant-tables.md`。
 *
 * [enabled] 是 Compose 状态：任何在组合中读过它的界面都会在开关切换时自动重组，
 * 所以开关是**即时**生效的，不需要重新检索。
 */
object Simplifier {
    private const val TAG = "Simplifier"
    private const val PREFS = "siongdict_prefs"
    private const val KEY_ENABLED = "render_simplified"
    private const val CACHE_MAX = 1024

    private val enabledState = mutableStateOf(false)

    /** 由「關於」面板里的开关控制；默认关闭。 */
    val enabled: Boolean get() = enabledState.value

    private var phrases: Map<String, String> = emptyMap()
    private var chars: Map<String, String> = emptyMap()
    private var maxPhraseLen = 1

    /** 同一条注释每次重组都会被问到，缓存一下，长注释不重复算。 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > CACHE_MAX
    }

    /** UI 起来之前调用一次：装表 + 读回开关状态。 */
    fun init(ctx: Context) {
        if (phrases.isEmpty() && chars.isEmpty()) load(ctx)
        enabledState.value = prefs(ctx).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabledState.value = on
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 界面与导出统一走这里；开关关闭时原样返回。 */
    fun display(text: String): String =
        if (!enabled || text.isEmpty()) text else toSimplified(text)

    /** 词组例外优先（最长匹配），不中则查单字表，再不然保持原样。 */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        cache[text]?.let { return it }

        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            var n = minOf(maxPhraseLen, text.length - i)
            while (n >= 2) {
                val replacement = phrases[text.substring(i, i + n)]
                if (replacement != null) {
                    sb.append(replacement)
                    i += n
                    matched = true
                    break
                }
                n--
            }
            if (!matched) {
                val ch = text[i].toString()
                sb.append(chars[ch] ?: ch)
                i++
            }
        }
        return sb.toString().also { cache[text] = it }
    }

    private fun load(ctx: Context) {
        try {
            val raw = ctx.assets.open("t2s.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(raw)
            phrases = toStringMap(obj.getJSONObject("phrases"))
            chars = toStringMap(obj.getJSONObject("chars"))
            maxPhraseLen = phrases.keys.maxOfOrNull { it.length } ?: 1
            Log.i(TAG, "Loaded t2s.json: ${phrases.size} phrases, ${chars.size} chars, " +
                    "maxPhraseLen=$maxPhraseLen")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load t2s.json, 简体渲染 will be a no-op", e)
            phrases = emptyMap()
            chars = emptyMap()
            maxPhraseLen = 1
        }
    }

    private fun toStringMap(obj: JSONObject): Map<String, String> {
        val map = HashMap<String, String>(obj.length() * 2)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.getString(key)
        }
        return map
    }
}
