package org.siongdict.app.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「檢查更新」的进程内状态：启动时自动查一次，之后可在「關於」面板手动重试。
 *
 * 结果按 [AppPrefs.KEY_UPDATE_LATEST] 缓存最近一次看到的 tag，重启后先按缓存显示「有新版本」，
 * 网络回来后用新结果覆盖；查询失败不打扰使用者，只在「關於」面板里显示一行错误。
 */
internal object UpdateState {

    private const val TAG = "UpdateState"

    var result: UpdateResult? by mutableStateOf(null)
        private set

    var checking: Boolean by mutableStateOf(false)
        private set

    /** 读上次缓存的最新版本号；实际「是否更新」由 [hasUpdate] 现算。 */
    fun init(ctx: Context) {
        val cached = prefs(ctx).getString(AppPrefs.KEY_UPDATE_LATEST, null) ?: return
        if (cached.isNotBlank()) result = UpdateResult(cached, false, null)
    }

    /** 当前版本是否落后于已知最新版；任一为空或格式异常都按「无更新」处理。 */
    fun hasUpdate(currentVersion: String): Boolean {
        val latest = result?.latestTag ?: return false
        if (currentVersion.isBlank() || latest.isBlank()) return false
        return try {
            UpdateChecker.compareVersions(currentVersion, latest) < 0
        } catch (e: Exception) {
            Log.w(TAG, "版本比較失敗", e)
            false
        }
    }

    /** 查一次 GitHub；并发调用只有第一次生效。 */
    suspend fun refresh(ctx: Context, currentVersion: String) {
        if (checking) return
        checking = true
        try {
            val fresh = withContext(Dispatchers.IO) { UpdateChecker.check(currentVersion) }
            result = fresh
            val tag = fresh.latestTag
            if (tag != null) {
                prefs(ctx).edit().putString(AppPrefs.KEY_UPDATE_LATEST, tag).apply()
            }
        } finally {
            checking = false
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
}
