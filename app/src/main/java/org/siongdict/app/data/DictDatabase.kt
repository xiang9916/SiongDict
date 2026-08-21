
package org.siongdict.app.data

import android.content.Context
import android.database.Cursor
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.FileOutputStream

class DictDatabase(private val ctx: Context) : SQLiteOpenHelper(
    ctx, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "DictDatabase"
        private const val DB_VERSION = 3
        private const val DB_NAME = "siongdict.db"
        private const val COG_NAME = "cognates.db"
        private const val LANG_COLUMNS = "字組, 語言, 讀音, 註釋, 排序"
        private var variantMap: Map<String, List<String>>? = null
    }

    private var toneSystemCache: Map<String, JSONObject>? = null
    private var divisionCache: Map<String, String>? = null

    init {
        val dbFile = ctx.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            copyDatabase()
        } else {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { existing ->
                val currentVersion = existing.version
                if (currentVersion < DB_VERSION) {
                    Log.i(TAG, "Database v$currentVersion < $DB_VERSION, re-copying from assets")
                    dbFile.delete()
                    copyDatabase()
                }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {}

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ─── Database files ───

    /** Copy a database file from assets/databases/ into the app's database dir. Throws if absent. */
    private fun copyAssetDatabase(name: String) {
        val dbFile = ctx.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        Log.i(TAG, "Copying $name from assets to ${dbFile.absolutePath}")
        ctx.assets.open("databases/$name").use { input ->
            FileOutputStream(dbFile).use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "$name copied, size=${dbFile.length()}")
    }

    private fun copyDatabase() {
        copyAssetDatabase(DB_NAME)
        markDatabaseVersion()
    }

    private fun copyCognateDatabase() {
        try {
            copyAssetDatabase(COG_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Cognates database not found in assets, skipping")
            ctx.getDatabasePath(COG_NAME).delete()
        }
    }

    private fun openCognateDb(): SQLiteDatabase? {
        val dbFile = ctx.getDatabasePath(COG_NAME)
        if (!dbFile.exists()) {
            copyCognateDatabase()
        }
        if (!dbFile.exists()) return null
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun forceReset() {
        Log.i(TAG, "Force reset: deleting cached databases")
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

    // ─── Version bookkeeping ───

    private fun dbPrefs() = ctx.getSharedPreferences("siongdict_prefs", Context.MODE_PRIVATE)

    private fun getAppVersion(): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun markDatabaseVersion() {
        dbPrefs().edit().putString("app_version", getAppVersion()).apply()
    }

    fun isDatabaseOutdated(): Boolean {
        val storedVersion = dbPrefs().getString("app_version", null) ?: return false
        return storedVersion != getAppVersion()
    }

    // ─── info table lookups ───

    /** Load one info-table column keyed by 簡稱; blank or unparseable rows are skipped. */
    private fun <T> loadInfoColumn(column: String, parse: (String) -> T?): Map<String, T> {
        val map = mutableMapOf<String, T>()
        readableDatabase.rawQuery("SELECT 簡稱, $column FROM info", null).use { cursor ->
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

    // ─── Character variants ───

    fun getVariants(ch: String): List<String> {
        if (variantMap == null) {
            loadVariants()
        }
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

    // ─── langs search ───

    fun searchByChar(hz: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        readableDatabase.rawQuery(
            "SELECT $LANG_COLUMNS FROM langs WHERE 字組 MATCH ? ORDER BY 排序",
            arrayOf(hz)
        ).use { cursor ->
            while (cursor.moveToNext()) results.add(cursor.toSearchResult())
        }
        return results
    }

    fun searchByMeaning(keyword: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        for (v in expandQueryVariants(keyword)) {
            readableDatabase.rawQuery(
                "SELECT $LANG_COLUMNS FROM langs WHERE 註釋 MATCH ? ORDER BY 排序 LIMIT 200",
                arrayOf(v)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val result = cursor.toSearchResult()
                    val key = "${result.chars}|${result.lang}|${result.ipa}|${result.note}"
                    if (seen.add(key)) results.add(result)
                }
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


    // ─── cognates ───

    fun getCognateGroup(lang: String, ipa: String): CognateGroup? {
        val cogDb = openCognateDb() ?: return null
        try {
            var groupId: String? = null
            var label: String? = null
            cogDb.rawQuery(
                "SELECT cognate_group, semantic_label FROM cognates WHERE lang = ? AND ipa = ? LIMIT 1",
                arrayOf(lang, ipa)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    groupId = cursor.getString(0)
                    label = cursor.getString(1)
                }
            }
            val gid = groupId ?: return null
            val members = readCognateMembers(cogDb, gid)
            if (members.isEmpty()) return null
            return CognateGroup(gid, label ?: "", members)
        } finally {
            cogDb.close()
        }
    }

    fun searchCognates(query: String): List<CognateGroup> {
        val cogDb = openCognateDb() ?: return emptyList()
        try {
            val groupIds = mutableSetOf<String>()
            for (v in expandQueryVariants(query)) {
                cogDb.rawQuery(
                    "SELECT DISTINCT cognate_group FROM cognates WHERE cognate_group LIKE ? OR ipa LIKE ? OR semantic_label LIKE ?",
                    arrayOf("%$v%", "%$v%", "%$v%")
                ).use { cursor ->
                    while (cursor.moveToNext()) groupIds.add(cursor.getString(0))
                }
            }

            val results = mutableListOf<CognateGroup>()
            for (gid in groupIds) {
                var label = ""
                val members = mutableListOf<CognateEntry>()
                cogDb.rawQuery(
                    "SELECT lang, ipa, note, sort_key, semantic_label FROM cognates WHERE cognate_group = ? ORDER BY sort_key",
                    arrayOf(gid)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        label = cursor.getString(4) ?: ""
                        members.add(cursor.toCognateEntry())
                    }
                }
                if (members.isNotEmpty()) results.add(CognateGroup(gid, label, members))
            }
            return results
        } finally {
            cogDb.close()
        }
    }

    private fun readCognateMembers(db: SQLiteDatabase, groupId: String): List<CognateEntry> {
        val members = mutableListOf<CognateEntry>()
        db.rawQuery(
            "SELECT lang, ipa, note, sort_key FROM cognates WHERE cognate_group = ? ORDER BY sort_key",
            arrayOf(groupId)
        ).use { cursor ->
            while (cursor.moveToNext()) members.add(cursor.toCognateEntry())
        }
        return members
    }

    private fun Cursor.toCognateEntry() = CognateEntry(
        lang = getString(0),
        ipa = getString(1),
        note = getString(2) ?: "",
        sortKey = getString(3) ?: ""
    )
}
