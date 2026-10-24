package org.siongdict.app.data

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 繁简/异体展开的纯逻辑（字形表由 [AssetVariantIndex] 提供）。
 *
 * 与拆分前的展开实现完全等价：逐字取字形组，做笛卡尔积；
 * 组合数超过 64 时退回原查询，避免一简多繁串出巨量组合。
 */
internal class VariantIndex(private val table: Map<String, List<String>>) {

    /** 一个字的所有写法（含自身）；无记录时返回只含自身的列表。 */
    fun variantsOf(ch: String): List<String> = table[ch] ?: listOf(ch)

    fun expand(query: String): List<String> {
        if (query.isEmpty()) return listOf(query)
        val charVariants = query.map { ch -> variantsOf(ch.toString()) }
        val totalCombos = charVariants.fold(1) { acc, list -> acc * list.size }
        if (totalCombos > MAX_COMBINATIONS) return listOf(query)

        val results = mutableListOf<String>()
        fun generate(index: Int, current: StringBuilder) {
            if (index == charVariants.size) {
                results.add(current.toString())
                return
            }
            for (variant in charVariants[index]) {
                current.append(variant)
                generate(index + 1, current)
                current.setLength(current.length - variant.length)
            }
        }
        generate(0, StringBuilder())
        return results
    }

    companion object {
        const val MAX_COMBINATIONS = 64
    }
}

/**
 * 从 assets/variants.json 读字形表。表是只读资产，按进程缓存一次——
 * 与拆分前的 static 字形缓存等价。
 */
internal class AssetVariantIndex(private val ctx: Context) {

    fun variantsOf(ch: String): List<String> = index().variantsOf(ch)

    fun expand(query: String): List<String> = index().expand(query)

    private fun index(): VariantIndex = cached ?: synchronized(lock) {
        cached ?: load().also { cached = it }
    }

    private fun load(): VariantIndex = try {
        val raw = ctx.assets.open("variants.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(raw)
        val map = mutableMapOf<String, List<String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = obj.getJSONArray(key)
            map[key] = (0 until arr.length()).map { arr.getString(it) }
        }
        Log.i(TAG, "Loaded ${map.size} variant mappings")
        VariantIndex(map)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load variants.json", e)
        VariantIndex(emptyMap())
    }

    companion object {
        private const val TAG = "VariantIndex"
        private val lock = Any()

        @Volatile
        private var cached: VariantIndex? = null
    }
}
