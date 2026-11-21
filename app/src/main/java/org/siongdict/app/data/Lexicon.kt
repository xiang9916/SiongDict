package org.siongdict.app.data

import android.database.Cursor
import android.util.Log
import org.json.JSONObject
import org.siongdict.app.analysis.parseLonLat

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

    /** 全部方言点的音典分区（簡稱 → 分區）；空白分区不進 map。 */
    suspend fun dialectDivisions(): Map<String, String>

    /**
     * 全部方言点画像（簡稱 空白者跳过）；按 (音典排序, 簡稱) 升序。
     *
     * [dialectDivisions] 由本方法的结果派生，两处共用同一份 generation 缓存。
     */
    suspend fun dialectProfiles(): List<DialectProfile>
}

internal class LocalLexicon(
    private val files: DatabaseFiles,
    private val gate: DbGate,
    private val variants: AssetVariantIndex,
) : Lexicon {

    companion object {
        private const val TAG = "Lexicon"
        private const val LANG_COLUMNS = "字組, 語言, 讀音, 註釋, 排序"

        /**
         * 搜釋義的匹配條件：原文子串，或把 `~` 還原成 [字組] 首字後的子串。
         * 兩個佔位符吃同一個查詢變體。
         */
        private const val MEANING_MATCH =
            "(instr(註釋, ?) > 0 OR (instr(註釋, '~') > 0 AND " +
                "instr(REPLACE(註釋, '~', substr(字組, 1, instr(字組 || ' ', ' ') - 1)), ?) > 0))"

        /** 每個字形變體都要全表掃一遍；4 組是桌機／裝置實測可接受的上界。 */
        private const val MAX_MEANING_EXPANSIONS = 4

        /** 子串檢索可能命中上萬行（如「子」12,445 行），按音典排序截前 2,000 條。 */
        private const val MEANING_LIMIT = 2000
    }

    private var toneSystems: Map<String, ToneSystem>? = null
    private var divisions: Map<String, String>? = null
    private var profiles: List<DialectProfile>? = null
    private var cacheGeneration = -1

    // ─── 检索 ───

    override suspend fun searchByChar(hz: String): List<SearchResult> = gate.run {
        query("SELECT $LANG_COLUMNS FROM langs WHERE 字組 MATCH ? ORDER BY 排序", arrayOf(hz))
    }

    /**
     * 搜釋義：對 `註釋` 做**子串**匹配，並把注釋裡的 `~` 還原成該條字組的本字。
     *
     * FTS5 的 `unicode61` 會把連續漢字視為單一 token，`註釋 MATCH` 只能命中「整個 token
     * 恰好等於查詢」的行（例如 `東西` 只命中注釋本身就是「東西」的條目），所以子串檢索
     * 只能用 `instr` 全表掃描；還原 `~` 取 [字組] 的第一個字（多字組只有 92 行，屬近似）。
     * 詳見 docs/adr/0011-meaning-substring-search.md。
     */
    override suspend fun searchByMeaning(keyword: String): List<SearchResult> = gate.run {
        val input = keyword.trim()
        if (input.isEmpty()) return@run emptyList()
        // 字形變體依「與原輸入不同的字數」升冪：原輸入優先，其次只差一個字的寫法。
        val expansions = variants.expand(input)
            .distinct()
            .sortedBy { candidate -> candidate.zip(input).count { (a, b) -> a != b } }
            .take(MAX_MEANING_EXPANSIONS)
        val where = List(expansions.size) { MEANING_MATCH }.joinToString(" OR ")
        val args = expansions.flatMap { listOf(it, it) }.toTypedArray()
        query(
            "SELECT $LANG_COLUMNS FROM langs WHERE $where ORDER BY 排序 LIMIT $MEANING_LIMIT",
            args
        )
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
        divisionCache()[lang] ?: ""
    }

    override suspend fun dialectDivisions(): Map<String, String> = gate.run {
        ensureGeneration()
        divisionCache()
    }

    override suspend fun dialectProfiles(): List<DialectProfile> = gate.run {
        ensureGeneration()
        profileList()
    }

    /**
     * 音典分区缓存（簡稱 → 分區），由方言点画像派生；空分区不入 map。
     * 画像顺序即 (音典排序, 簡稱)，因此分区树的首次出现顺序也随之固定。
     */
    private fun divisionCache(): Map<String, String> =
        divisions ?: profileList().mapNotNull { profile ->
            profile.division.ifEmpty { null }?.let { profile.lang to it }
        }.toMap().also {
            divisions = it
            Log.i(TAG, "Loaded divisions for ${it.size} dialects")
        }

    /** 方言点画像缓存；空白簡稱在载入时即跳过，列顺序固定为 (音典排序, 簡稱)。 */
    private fun profileList(): List<DialectProfile> =
        profiles ?: loadProfiles().also {
            profiles = it
            Log.i(TAG, "Loaded profiles for ${it.size} dialects")
        }

    private fun loadProfiles(): List<DialectProfile> {
        val list = mutableListOf<DialectProfile>()
        files.siongdict().rawQuery(
            "SELECT 簡稱, 音典分區, 音典顏色, 音典排序, 經緯度 FROM info",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val lang = cursor.getString(0) ?: continue
                if (lang.isBlank()) continue
                // 「經緯度」格式為 "lon,lat"；缺失或格式異常時留 null，地圖頁會跳過該點。
                val coordinates = parseLonLat(cursor.getString(4))
                list.add(
                    DialectProfile(
                        lang = lang,
                        // 分区/颜色空白时保留空串：调用方自己决定如何降级显示。
                        division = cursor.getString(1) ?: "",
                        colorHex = cursor.getString(2) ?: "",
                        sortKey = cursor.getString(3) ?: "",
                        lon = coordinates?.first,
                        lat = coordinates?.second,
                    )
                )
            }
        }
        return list.sortedWith(compareBy({ it.sortKey }, { it.lang }))
    }

    private fun ensureGeneration() {
        if (cacheGeneration == files.generation) return
        toneSystems = null
        divisions = null
        profiles = null
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
