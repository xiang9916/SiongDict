package org.siongdict.app.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 繁→简字表：词组例外优先（最长匹配）、单字兜底。
 * 由 `assets/t2s.json` 解析，见 `tools/build_simplifier.py` 与 `docs/adr/0002`。
 */
internal data class SimplifierTable(
    val phrases: Map<String, String>,
    val chars: Map<String, String>,
) {
    val maxPhraseLength: Int = phrases.keys.maxOfOrNull { it.length } ?: 1
}

/** 从 assets 读 t2s.json；坏档记 warn 并回空表（渲染为 no-op），不影响启动。 */
internal fun loadSimplifierTable(context: Context): SimplifierTable = try {
    val raw = context.assets.open("t2s.json").bufferedReader().use { it.readText() }
    val obj = JSONObject(raw)
    SimplifierTable(
        phrases = toStringMap(obj.getJSONObject("phrases")),
        chars = toStringMap(obj.getJSONObject("chars")),
    ).also { table ->
        Log.i(TAG, "Loaded t2s.json: ${table.phrases.size} phrases / ${table.chars.size} chars / maxPhraseLen=${table.maxPhraseLength}")
    }
} catch (e: Exception) {
    Log.w(TAG, "Failed to load t2s.json, 简体渲染 will be a no-op", e)
    SimplifierTable(emptyMap(), emptyMap())
}

private const val TAG = "DisplayPreferences"

private fun toStringMap(obj: JSONObject): Map<String, String> {
    val map = HashMap<String, String>(obj.length() * 2)
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = obj.getString(key)
    }
    return map
}

/**
 * 显示偏好：繁→简渲染与护眼模式。
 *
 * 取代旧的 Simplifier／EyeCare 两个进程单例——由 MainActivity 创建一次、经
 * CompositionLocal 提供给界面，开关状态仍是 Compose 状态，拨动即时重组。
 * 构造只依赖 [PrefStore] 与字表加载器，可在 JVM 上用内存 store + 假字表测试。
 *
 * @param tables 首次需要时调用一次并缓存；生产传 `{ loadSimplifierTable(context) }`。
 */
internal class DisplayPreferences(
    private val store: PrefStore,
    private val tables: () -> SimplifierTable,
) {
    /** 由「關於」面板里的开关控制；默认关闭。 */
    var renderSimplifiedEnabled by mutableStateOf(false)
        private set

    /** 护眼模式：开启后界面底色用淡黄（0xFFF2EEE6）。 */
    var eyeCareEnabled by mutableStateOf(false)
        private set

    private var table: SimplifierTable? = null

    /** 与旧版一致：第一次重组时不会重复解析字表。 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > CACHE_MAX
    }

    /** 首帧之前调用一次：读回两个开关，并预热字表（旧版 Simplifier.init 的行为）。 */
    fun init() {
        renderSimplifiedEnabled = store.getBoolean(AppPrefs.KEY_RENDER_SIMPLIFIED, false)
        eyeCareEnabled = store.getBoolean(AppPrefs.KEY_EYE_CARE, false)
        runCatching { loadedTable() }
    }

    fun setRenderSimplified(on: Boolean) {
        renderSimplifiedEnabled = on
        store.putBoolean(AppPrefs.KEY_RENDER_SIMPLIFIED, on)
    }

    fun setEyeCare(on: Boolean) {
        eyeCareEnabled = on
        store.putBoolean(AppPrefs.KEY_EYE_CARE, on)
    }

    /** 界面与导出统一走这里；开关关闭时原样返回。 */
    fun display(text: String): String =
        if (!renderSimplifiedEnabled || text.isEmpty()) text else toSimplified(text)

    /** 词组例外优先（最长匹配），不中则查单字表，再不然保持原样。 */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        cache[text]?.let { return it }

        val loaded = loadedTable()
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            var n = minOf(loaded.maxPhraseLength, text.length - i)
            while (n >= 2) {
                val replacement = loaded.phrases[text.substring(i, i + n)]
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
                sb.append(loaded.chars[ch] ?: ch)
                i++
            }
        }
        return sb.toString().also { cache[text] = it }
    }

    private fun loadedTable(): SimplifierTable = table ?: tables().also { table = it }

    private companion object {
        const val CACHE_MAX = 1024
    }
}
