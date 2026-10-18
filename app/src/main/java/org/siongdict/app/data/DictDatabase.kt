package org.siongdict.app.data

import android.content.Context
import android.database.Cursor
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 字音库（siongdict.db）与同源词库（cognates.db）的访问层。
 *
 * 两个库都随 APK 分发在 assets/databases/ 下，首次使用时复制到应用私有目录
 * （复制是延迟 + 原子的：不在构造函数里做，也不会留下半截文件）。
 * 连接一律以 **OPEN_READONLY** 打开：App 从不写使用者装置上的资料；
 * 同源词库的连接在进程内复用，避免「每个方言点开关一次 300 KB 的库」。
 *
 * **并发约定**：本类只用 synchronized 保护「打开/关闭/复制」这几个动作，
 * 查询本身不持锁；调用方必须串行调用（App 侧由 SearchViewModel 的 Mutex 保证）。
 */
class DictDatabase(private val ctx: Context) {

    companion object {
        private const val TAG = "DictDatabase"
        private const val DB_VERSION = 3
        private const val DB_NAME = "siongdict.db"
        private const val COG_NAME = "cognates.db"
        private const val LANG_COLUMNS = "字組, 語言, 讀音, 註釋, 排序"

        private var variantMap: Map<String, List<String>>? = null

        /**
         * 「搜同源」结果的**固定显示顺序**：按组名（`<義項>_<音類>`，即卡片上那行英文）不分大小写的字母序。
         * 不看方言点多寡、也不看命中相关度。同字母序时再比原字符串，保证顺序完全确定。
         */
        private val COGNATE_DISPLAY_ORDER =
            compareBy<CognateGroup> { it.groupId.lowercase() }.thenBy { it.groupId }

        /** 从 PackageManager 取 versionName；失败时返回空串（只影响显示与版本比对）。 */
        fun appVersion(ctx: Context): String = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private var siongDb: SQLiteDatabase? = null
    private var cognateDb: SQLiteDatabase? = null
    private var toneSystemCache: Map<String, JSONObject>? = null
    private var divisionCache: Map<String, String>? = null

    // ─── 数据库文件与版本 ───

    /** 预取：在后台线程上把两个库准备好，避免首装的 54 MB 复制卡在主线程。 */
    @Synchronized
    fun prepare() {
        database()
        cognateDatabase()
    }

    /**
     * 文件缺失、损坏（读不出 user_version）或版本落后于 [DB_VERSION] 时，从 assets 重新复制。
     * 半截文件（复制过程中进程被杀）会在这里被识别并覆盖，不会让 App 一启动就崩。
     */
    @Synchronized
    private fun ensureDatabaseCopied() {
        val dbFile = ctx.getDatabasePath(DB_NAME)
        val cachedVersion = if (dbFile.exists()) {
            runCatching { openReadOnly(dbFile).use { it.version } }.getOrNull()
        } else {
            null
        }
        if (cachedVersion == null || cachedVersion < DB_VERSION) {
            Log.i(TAG, "Database missing/corrupt/outdated (version=$cachedVersion), re-copying from assets")
            dbFile.delete()
            copyDatabase()
        }
    }

    /**
     * 从 assets 复制一个数据库：先写同目录的 `.tmp`，成功后原子改名到正式文件名。
     * Throws if the asset is absent（与 AGENTS.md §3 的约定一致）。
     */
    private fun copyAssetDatabase(name: String) {
        val target = ctx.getDatabasePath(name)
        val dir = target.parentFile ?: error("数据库目录不可用：${target.absolutePath}")
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        Log.i(TAG, "Copying $name from assets to ${target.absolutePath}")
        try {
            ctx.assets.open("databases/$name").use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "数据库改名失败：$name" }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        Log.i(TAG, "$name copied, size=${target.length()}")
    }

    @Synchronized
    private fun copyDatabase() {
        siongDb?.close()
        siongDb = null
        copyAssetDatabase(DB_NAME)
        markDatabaseVersion()
    }

    @Synchronized
    private fun copyCognateDatabase() {
        cognateDb?.close()
        cognateDb = null
        try {
            copyAssetDatabase(COG_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Cognates database not found in assets, skipping")
            ctx.getDatabasePath(COG_NAME).delete()
        }
    }

    private fun openReadOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    /** 字音库连接：首次使用时（在调用方线程上）供给文件并打开，进程内复用。 */
    @Synchronized
    private fun database(): SQLiteDatabase {
        siongDb?.let { return it }
        ensureDatabaseCopied()
        return openReadOnly(ctx.getDatabasePath(DB_NAME)).also { siongDb = it }
    }

    /** 同源词库连接：缺失时返回 null（「搜同源」自动停用），打开成功后复用。 */
    @Synchronized
    private fun cognateDatabase(): SQLiteDatabase? {
        cognateDb?.let { return it }
        val file = ctx.getDatabasePath(COG_NAME)
        if (!file.exists()) copyCognateDatabase()
        if (!file.exists()) return null
        return openReadOnly(file).also { cognateDb = it }
    }

    /** 删除缓存库文件并重新复制；在「重置資料庫」与版本过期时调用。 */
    @Synchronized
    fun forceReset() {
        Log.i(TAG, "Force reset: deleting cached databases")
        siongDb?.close()
        siongDb = null
        cognateDb?.close()
        cognateDb = null
        // deleteDatabase() removes the main file plus any -journal/-wal/-shm side files
        ctx.databaseList().forEach { name ->
            if (name.startsWith(DB_NAME) || name.startsWith(COG_NAME)) {
                ctx.deleteDatabase(name)
            }
        }
        copyDatabase()
        copyCognateDatabase()
        toneSystemCache = null
        divisionCache = null
        Log.i(TAG, "Force reset complete")
    }

    // ─── 版本记录 ───

    private fun dbPrefs() = ctx.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)

    private fun markDatabaseVersion() {
        dbPrefs().edit().putString(AppPrefs.KEY_APP_VERSION, appVersion(ctx)).apply()
    }

    fun isDatabaseOutdated(): Boolean {
        val storedVersion = dbPrefs().getString(AppPrefs.KEY_APP_VERSION, null) ?: return false
        return storedVersion != appVersion(ctx)
    }

    // ─── info 表查询 ───

    /** Load one info-table column keyed by 簡稱; blank or unparseable rows are skipped. */
    private fun <T> loadInfoColumn(column: String, parse: (String) -> T?): Map<String, T> {
        val map = mutableMapOf<String, T>()
        database().rawQuery("SELECT 簡稱, $column FROM info", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                parse(cursor.getString(1) ?: "")?.let { map[name] = it }
            }
        }
        return map
    }

    fun getDialectDivision(jc: String): String {
        val cache = divisionCache ?: loadInfoColumn("音典分區") { it.ifEmpty { null } }
            .also { divisionCache = it }
        return cache[jc] ?: ""
    }

    fun getToneSystem(jc: String): JSONObject? {
        val cache = toneSystemCache ?: loadInfoColumn("聲調") { raw ->
            if (raw.isEmpty()) null
            else try {
                JSONObject(raw)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tone system", e)
                null
            }
        }.also {
            toneSystemCache = it
            Log.i(TAG, "Loaded tone systems for ${it.size} dialects")
        }
        return cache[jc]
    }

    // ─── 异体字表 ───

    fun getVariants(ch: String): List<String> {
        if (variantMap == null) loadVariants()
        return variantMap?.get(ch) ?: listOf(ch)
    }

    /**
     * Expand a multi-character query into all simplified/traditional/variant
     * combinations via the cartesian product of per-character variants.
     * Returns just [query] when the combination count would be too large.
     */
    fun expandQueryVariants(query: String): List<String> {
        if (query.isEmpty()) return listOf(query)
        val charVariants = query.map { ch -> getVariants(ch.toString()) }
        val totalCombos = charVariants.fold(1) { acc, list -> acc * list.size }
        if (totalCombos > 64) return listOf(query)

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

    private fun loadVariants() {
        try {
            val json = ctx.assets.open("variants.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val map = mutableMapOf<String, List<String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = obj.getJSONArray(key)
                map[key] = (0 until arr.length()).map { arr.getString(it) }
            }
            variantMap = map
            Log.i(TAG, "Loaded ${map.size} variant mappings")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load variants.json", e)
            variantMap = emptyMap()
        }
    }

    // ─── 字音检索 ───

    private fun query(sql: String, args: Array<String>?): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        database().rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) results.add(cursor.toSearchResult())
        }
        return results
    }

    fun searchByChar(hz: String): List<SearchResult> =
        query("SELECT $LANG_COLUMNS FROM langs WHERE 字組 MATCH ? ORDER BY 排序", arrayOf(hz))

    fun searchByMeaning(keyword: String): List<SearchResult> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResult>()
        for (v in expandQueryVariants(keyword)) {
            query(
                "SELECT $LANG_COLUMNS FROM langs WHERE 註釋 MATCH ? ORDER BY 排序 LIMIT 200",
                arrayOf(v)
            ).forEach { result ->
                val key = "${result.chars}|${result.lang}|${result.ipa}|${result.note}"
                if (seen.add(key)) results.add(result)
            }
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

    // ─── 同源词 ───

    fun getCognateGroup(lang: String, ipa: String): CognateGroup? {
        val db = cognateDatabase() ?: return null
        val groupId = db.rawQuery(
            "SELECT cognate_group FROM cognates WHERE lang = ? AND ipa = ? LIMIT 1",
            arrayOf(lang, ipa)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null
        return readCognateGroup(db, groupId)
    }

    fun searchCognates(query: String): List<CognateGroup> {
        val db = cognateDatabase() ?: return emptyList()
        val groupIds = mutableSetOf<String>()
        for (v in expandQueryVariants(query)) {
            db.rawQuery(
                "SELECT DISTINCT cognate_group FROM cognates WHERE cognate_group LIKE ? OR ipa LIKE ? OR semantic_label LIKE ?",
                arrayOf("%$v%", "%$v%", "%$v%")
            ).use { cursor ->
                while (cursor.moveToNext()) groupIds.add(cursor.getString(0))
            }
        }
        return groupIds.mapNotNull { readCognateGroup(db, it) }
            .sortedWith(COGNATE_DISPLAY_ORDER)
    }

    /** 读出一整组的成员；组内按音典排序。组不存在（空）时返回 null。 */
    private fun readCognateGroup(db: SQLiteDatabase, groupId: String): CognateGroup? {
        var label = ""
        val members = mutableListOf<CognateEntry>()
        db.rawQuery(
            "SELECT lang, ipa, note, sort_key, semantic_label FROM cognates WHERE cognate_group = ? ORDER BY sort_key",
            arrayOf(groupId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (label.isEmpty()) label = cursor.getString(4) ?: ""
                members.add(cursor.toCognateEntry())
            }
        }
        return if (members.isEmpty()) null else CognateGroup(groupId, label, members)
    }

    private fun Cursor.toCognateEntry() = CognateEntry(
        lang = getString(0),
        ipa = getString(1),
        note = getString(2) ?: "",
        sortKey = getString(3) ?: ""
    )
}
