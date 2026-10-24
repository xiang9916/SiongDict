package org.siongdict.app.data

import android.database.Cursor
import android.util.Log
import org.json.JSONObject

/**
 * 字音库（siongdict.db）的只读端口：FTS 检索、info 元数据、繁简异体展开。
 *
 * 所有方法都是 suspend：实现内部统一经 [DbGate] 串行并在 IO 线程执行，
 * 调用方不需要再自己持锁或切换线程。
 */
interface Lexicon {

    suspend fun searchByChar(hz: String): List<SearchResult>

    suspend fun searchByMeaning(keyword: String): List<SearchResult>

    /** 一个字的繁简/异体写法（含自身）。 */
    suspend fun variantsOf(ch: String): List<String>

    /** 方言点的声调系统；没有数据时 null。 */
    suspend fun toneSystem(lang: String): ToneSystem?

    /** 方言点的音典分区（如「湘贛－岳州」）；没有数据时空串。 */
    suspend fun dialectDivision(lang: String): String
}

internal class LocalLexicon(
    private val files: DatabaseFiles,
    private val gate: DbGate,
    private val variants: AssetVariantIndex,
) : Lexicon {

    companion object {
        private const val TAG = "Lexicon"
        private const val LANG_COLUMNS = "字組, 語言, 讀音, 註釋, 排序"
    }

    private var toneSystems: Map<String, ToneSystem>? = null
    private var divisions: Map<String, String>? = null
    private var cacheGeneration = -1

    // ─── 检索 ───

    override suspend fun searchByChar(hz: String): List<SearchResult> = gate.run {
        query("SELECT $LANG_COLUMNS FROM langs WHERE 字組 MATCH ? ORDER BY 排序", arrayOf(hz))
    }

    override suspend fun searchByMeaning(keyword: String): List<SearchResult> = gate.run {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResult>()
        for (v in variants.expand(keyword)) {
            query(
                "SELECT $LANG_COLUMNS FROM langs WHERE 註釋 MATCH ? ORDER BY 排序 LIMIT 200",
                arrayOf(v)
            ).forEach { result ->
                val key = "${result.chars}|${result.lang}|${result.ipa}|${result.note}"
                if (seen.add(key)) results.add(result)
            }
        }
        results
    }

    override suspend fun variantsOf(ch: String): List<String> = gate.run {
        variants.variantsOf(ch)
    }

    // ─── info 元数据（缓存按 generation 失效） ───

    override suspend fun toneSystem(lang: String): ToneSystem? = gate.run {
        ensureGeneration()
        val cache = toneSystems ?: loadInfoColumn("聲調") { raw ->
            if (raw.isEmpty()) null else parseToneSystem(raw)
        }.also {
            toneSystems = it
            Log.i(TAG, "Loaded tone systems for ${it.size} dialects")
        }
        cache[lang]
    }

    override suspend fun dialectDivision(lang: String): String = gate.run {
        ensureGeneration()
        val cache = divisions ?: loadInfoColumn("音典分區") { it.ifEmpty { null } }
            .also { divisions = it }
        cache[lang] ?: ""
    }

    private fun ensureGeneration() {
        if (cacheGeneration == files.generation) return
        toneSystems = null
        divisions = null
        cacheGeneration = files.generation
    }

    /** Load one info-table column keyed by 簡稱; blank or unparseable rows are skipped. */
    private fun <T> loadInfoColumn(column: String, parse: (String) -> T?): Map<String, T> {
        val map = mutableMapOf<String, T>()
        files.siongdict().rawQuery("SELECT 簡稱, $column FROM info", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                parse(cursor.getString(1) ?: "")?.let { map[name] = it }
            }
        }
        return map
    }

    /** 声调 JSON → 值对象。数组长度/字段语义留给 ToneFormatter 判断（与拆分前一致）。 */
    private fun parseToneSystem(raw: String): ToneSystem? = try {
        val obj = JSONObject(raw)
        val styles = mutableMapOf<String, List<String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = obj.optJSONArray(key) ?: continue
            styles[key] = (0 until arr.length()).map { arr.optString(it, "") }
        }
        ToneSystem(styles)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse tone system", e)
        null
    }

    // ─── 查询 ───

    private fun query(sql: String, args: Array<String>?): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        files.siongdict().rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) results.add(cursor.toSearchResult())
        }
        return results
    }

    private fun Cursor.toSearchResult() = SearchResult(
        chars = getString(0),
        lang = getString(1),
        ipa = getString(2),
        note = getString(3) ?: "",
        sortKey = getString(4) ?: ""
    )
}
