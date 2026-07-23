package org.siongdict.app.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper
import java.io.FileOutputStream

class DictDatabase(private val ctx: Context) : SQLiteOpenHelper(
    ctx, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "DictDatabase"
        private const val DB_VERSION = 2
        private const val DB_NAME = "siongdict.db"
        private const val COG_NAME = "cognates.db"
        private var variantMap: Map<String, List<String>>? = null
    }

    init {
        val dbFile = ctx.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            copyDatabase()
        } else {
            val existing = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            val currentVersion = existing.version
            existing.close()
            if (currentVersion < DB_VERSION) {
                Log.i(TAG, "Database v$currentVersion < $DB_VERSION, re-copying from assets")
                dbFile.delete()
                copyDatabase()
            }
        }
    }

    private fun copyDatabase() {
        val dbFile = ctx.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        Log.i(TAG, "Copying database from assets to ${dbFile.absolutePath}")
        ctx.assets.open("databases/$DB_NAME").use { input ->
            FileOutputStream(dbFile).use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "Database copied, size=${dbFile.length()}")
    }

    private fun copyCognateDatabase() {
        val dbFile = ctx.getDatabasePath(COG_NAME)
        dbFile.parentFile?.mkdirs()
        try {
            ctx.assets.open("databases/$COG_NAME").use { input ->
                FileOutputStream(dbFile).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Cognates database copied, size=${dbFile.length()}")
        } catch (e: Exception) {
            Log.w(TAG, "Cognates database not found in assets, skipping")
            dbFile.delete()
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
        ctx.getDatabasePath(DB_NAME).delete()
        ctx.getDatabasePath(COG_NAME).delete()
        // Also delete SQLite temp files
        ctx.databaseList().forEach { name ->
            if (name.startsWith(DB_NAME) || name.startsWith(COG_NAME)) {
                ctx.deleteDatabase(name)
            }
        }
        copyDatabase()
        copyCognateDatabase()
        Log.i(TAG, "Force reset complete")
    }

    override fun onCreate(db: SQLiteDatabase) {}

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
    }

    fun getVariants(ch: String): List<String> {
        if (variantMap == null) {
            loadVariants()
        }
        return variantMap?.get(ch) ?: listOf(ch)
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
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                map[key] = list
            }
            variantMap = map
            Log.i(TAG, "Loaded ${map.size} variant mappings")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load variants.json", e)
            variantMap = emptyMap()
        }
    }

    fun searchByChar(hz: String): List<SearchResult> {
        val db = readableDatabase
        val results = mutableListOf<SearchResult>()
        val cursor = db.rawQuery(
            "SELECT 字組, 語言, 讀音, 註釋, 排序 FROM langs WHERE 字組 MATCH ? ORDER BY 排序",
            arrayOf(hz)
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(SearchResult(
                    chars = it.getString(0),
                    lang = it.getString(1),
                    ipa = it.getString(2),
                    note = it.getString(3) ?: "",
                    sortKey = it.getString(4) ?: ""
                ))
            }
        }
        return results
    }

    fun searchByPron(ipa: String): List<SearchResult> {
        val db = readableDatabase
        val results = mutableListOf<SearchResult>()
        val cursor = db.rawQuery(
            "SELECT 字組, 語言, 讀音, 註釋, 排序 FROM langs WHERE 讀音 MATCH ? ORDER BY 排序",
            arrayOf(ipa)
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(SearchResult(
                    chars = it.getString(0),
                    lang = it.getString(1),
                    ipa = it.getString(2),
                    note = it.getString(3) ?: "",
                    sortKey = it.getString(4) ?: ""
                ))
            }
        }
        return results
    }

    fun searchByMeaning(keyword: String): List<SearchResult> {
        val db = readableDatabase
        val results = mutableListOf<SearchResult>()
        val cursor = db.rawQuery(
            "SELECT 字組, 語言, 讀音, 註釋, 排序 FROM langs WHERE 註釋 MATCH ? ORDER BY 排序 LIMIT 200",
            arrayOf(keyword)
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(SearchResult(
                    chars = it.getString(0),
                    lang = it.getString(1),
                    ipa = it.getString(2),
                    note = it.getString(3) ?: "",
                    sortKey = it.getString(4) ?: ""
                ))
            }
        }
        return results
    }

    fun getCognateGroup(lang: String, ipa: String): CognateGroup? {
        val cogDb = openCognateDb() ?: return null
        try {
            val cursor = cogDb.rawQuery(
                "SELECT cognate_group, semantic_label FROM cognate_auto WHERE lang = ? AND ipa = ? LIMIT 1",
                arrayOf(lang, ipa)
            )
            var groupId: String? = null
            var label: String? = null
            cursor.use {
                if (it.moveToFirst()) {
                    groupId = it.getString(0)
                    label = it.getString(1)
                }
            }
            if (groupId == null) return null

            val members = mutableListOf<CognateEntry>()
            val memberCursor = cogDb.rawQuery(
                "SELECT lang, ipa, note, sort_key FROM cognate_auto WHERE cognate_group = ? ORDER BY sort_key",
                arrayOf(groupId)
            )
            memberCursor.use {
                while (it.moveToNext()) {
                    members.add(CognateEntry(
                        lang = it.getString(0),
                        ipa = it.getString(1),
                        note = it.getString(2) ?: "",
                        sortKey = it.getString(3) ?: ""
                    ))
                }
            }
            if (members.isEmpty()) return null
            return CognateGroup(groupId!!, label ?: "", members)
        } finally {
            cogDb.close()
        }
    }

    fun searchCognates(query: String): List<CognateGroup> {
        val cogDb = openCognateDb() ?: return emptyList()
        try {
            val cursor = cogDb.rawQuery(
                "SELECT DISTINCT cognate_group FROM cognate_auto WHERE cognate_group LIKE ? OR ipa LIKE ? OR semantic_label LIKE ?",
                arrayOf("%$query%", "%$query%", "%$query%")
            )
            val groupIds = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    groupIds.add(it.getString(0))
                }
            }

            val results = mutableListOf<CognateGroup>()
            for (gid in groupIds) {
                val memberCursor = cogDb.rawQuery(
                    "SELECT lang, ipa, note, sort_key, semantic_label FROM cognate_auto WHERE cognate_group = ? ORDER BY sort_key",
                    arrayOf(gid)
                )
                val members = mutableListOf<CognateEntry>()
                var label = ""
                memberCursor.use {
                    while (it.moveToNext()) {
                        label = it.getString(4) ?: ""
                        members.add(CognateEntry(
                            lang = it.getString(0),
                            ipa = it.getString(1),
                            note = it.getString(2) ?: "",
                            sortKey = it.getString(3) ?: ""
                        ))
                    }
                }
                if (members.isNotEmpty()) {
                    results.add(CognateGroup(gid, label, members))
                }
            }
            return results
        } finally {
            cogDb.close()
        }
    }

    fun getDialectInfo(jc: String): DialectInfo? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 語言, 地點, 地圖集二分區, 音典分區, 音典顏色, 字數 FROM info WHERE 簡稱 MATCH ?",
            arrayOf(jc)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return DialectInfo(
                    name = it.getString(0),
                    location = it.getString(1),
                    division = it.getString(2),
                    ydDivision = it.getString(3),
                    color = it.getString(4) ?: "",
                    charCount = it.getString(5) ?: ""
                )
            }
        }
        return null
    }

    fun getAllDialects(): List<DialectInfo> {
        val db = readableDatabase
        val results = mutableListOf<DialectInfo>()
        val cursor = db.rawQuery(
            "SELECT 簡稱, 語言, 地點, 音典分區, 音典顏色, 字數 FROM info ORDER BY 音典分區, 簡稱",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(DialectInfo(
                    name = it.getString(1),
                    shortName = it.getString(0),
                    location = it.getString(2),
                    division = "",
                    ydDivision = it.getString(3) ?: "",
                    color = it.getString(4) ?: "",
                    charCount = it.getString(5) ?: ""
                ))
            }
        }
        return results
    }
}
