package org.siongdict.app.data

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * 两个数据库文件的供给与连接生命周期：assets → 应用私有目录的延迟 + 原子复制、
 * 损坏/落后自愈、版本记录、只读连接复用、重置。
 *
 * 查询与重置之间的串行由 [DbGate] 保证；本类只同步「开/关连接」这一小段。
 * assets 缺失时 [siongdict] 直接抛异常（与拆分前一致，不吞错）。
 */
internal class DatabaseFiles(private val ctx: Context) {

    companion object {
        private const val TAG = "DatabaseFiles"
        private const val DB_VERSION = 3
        private const val DB_NAME = "siongdict.db"
        private const val COG_NAME = "cognates.db"
    }

    private var siongDb: SQLiteDatabase? = null
    private var cognateDb: SQLiteDatabase? = null

    /** 每次 [reset] 自增；[LocalLexicon] 用它判断 info 缓存是否失效。只在 [DbGate] 内读写。 */
    @Volatile
    var generation: Int = 0
        private set

    // ─── 连接与供给 ───

    /** 字音库连接：首次使用时（在调用方线程上）供给文件并打开，进程内复用。 */
    @Synchronized
    fun siongdict(): SQLiteDatabase {
        siongDb?.let { return it }
        ensureDatabaseCopied()
        return openReadOnly(ctx.getDatabasePath(DB_NAME)).also { siongDb = it }
    }

    /** 同源词库连接：缺失时返回 null（「搜同源」自动停用），打开成功后复用。 */
    @Synchronized
    fun cognates(): SQLiteDatabase? {
        cognateDb?.let { return it }
        val file = ctx.getDatabasePath(COG_NAME)
        if (!file.exists()) copyCognateDatabase()
        if (!file.exists()) return null
        return openReadOnly(file).also { cognateDb = it }
    }

    /** 预取两个库（首装的 54 MB 复制在这里发生）。 */
    @Synchronized
    fun prepare() {
        siongdict()
        cognates()
    }

    /** 删连接、删私有库文件、从 assets 重拷；generation +1 使 info 缓存失效。 */
    @Synchronized
    fun reset() {
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
        generation++
        Log.i(TAG, "Force reset complete (generation=$generation)")
    }

    // ─── 复制与自愈 ───

    /**
     * 文件缺失、损坏（读不出 user_version）或版本落后于 [DB_VERSION] 时，从 assets 重新复制。
     * 半截文件（复制过程中进程被杀）会在这里被识别并覆盖。
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
     * 从 assets 复制一个数据库：先写同目录的 .tmp，成功后原子改名到正式文件名。
     * Throws if the asset is absent（与 AGENTS.md $3 的约定一致）。
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

    // ─── 版本记录 ───

    private fun prefs() = ctx.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)

    private fun markDatabaseVersion() {
        prefs().edit().putString(AppPrefs.KEY_APP_VERSION, AppInfo.versionName(ctx)).apply()
    }

    /**
     * versionName 与 SharedPreferences 里记录的值做**不等比较**（不解析语义化版本）。
     * 记录不存在（首装）时返回 false：由 [prepare] 负责首次复制。
     */
    fun isOutdated(): Boolean {
        val storedVersion = prefs().getString(AppPrefs.KEY_APP_VERSION, null) ?: return false
        return storedVersion != AppInfo.versionName(ctx)
    }
}
