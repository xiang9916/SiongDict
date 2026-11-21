package org.siongdict.app.data

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「檢查更新」的进程内状态：启动时自动查一次，之后可在「關於」面板手动重试。
 *
 * 取代旧的 UpdateState 单例——由 MainActivity 创建、经 CompositionLocal 提供给界面。
 * 构造注入 [PrefStore]（缓存最近一次 tag）、查询函数 [checker] 与 IO 调度器，
 * 因此完整行为可在 JVM 上用 fake 测试。
 *
 * 结果按 [AppPrefs.KEY_UPDATE_LATEST] 缓存，重启后先按缓存显示「有新版本」，
 * 网络回来后用新结果覆盖；查询失败不打扰使用者，只在「關於」面板里显示一行错误。
 */
internal class UpdateStatus(
    private val store: PrefStore,
    private val checker: suspend (currentVersion: String) -> UpdateResult,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var result: UpdateResult? by mutableStateOf(null)
        private set

    var checking: Boolean by mutableStateOf(false)
        private set

    /** 读上次缓存的最新版本号；实际「是否更新」由 [hasUpdate] 现算。 */
    fun init() {
        val cached = store.getString(AppPrefs.KEY_UPDATE_LATEST, null) ?: return
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
    suspend fun refresh(currentVersion: String) {
        if (checking) return
        checking = true
        try {
            val fresh = withContext(ioDispatcher) { checker(currentVersion) }
            result = fresh
            val tag = fresh.latestTag
            if (tag != null) {
                store.putString(AppPrefs.KEY_UPDATE_LATEST, tag)
            }
        } finally {
            checking = false
        }
    }

    private companion object {
        const val TAG = "UpdateStatus"
    }
}
