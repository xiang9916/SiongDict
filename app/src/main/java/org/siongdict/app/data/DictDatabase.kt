package org.siongdict.app.data

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper
import java.io.FileOutputStream

class DictDatabase(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "DictDatabase"
        private const val DB_VERSION = 2
        private const val DB_NAME = "siongdict.db"
    }

    init {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            copyDatabase(context)
        } else {
            // Check if existing database is outdated; re-copy from assets if so
            val existing = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            val currentVersion = existing.version
            existing.close()
            if (currentVersion < DB_VERSION) {
                Log.i(TAG, "Database v$currentVersion < $DB_VERSION, re-copying from assets")
                dbFile.delete()
                copyDatabase(context)
            }
        }
    }

    private fun copyDatabase(context: Context) {
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        Log.i(TAG, "Copying database from assets to ${dbFile.absolutePath}")
        context.assets.open("databases/$DB_NAME").use { input ->
            FileOutputStream(dbFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "Database copied, size=${dbFile.length()}")
    }

    override fun onCreate(db: SQLiteDatabase) {}

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
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
