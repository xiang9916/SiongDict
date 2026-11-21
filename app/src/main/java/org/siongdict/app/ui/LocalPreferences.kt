package org.siongdict.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import org.siongdict.app.data.DisplayPreferences
import org.siongdict.app.data.PrefStore
import org.siongdict.app.data.SimplifierTable
import org.siongdict.app.data.UpdateResult
import org.siongdict.app.data.UpdateStatus

/**
 * 显示偏好与更新状态的注入点。
 *
 * 生产由 MainActivity 在 setContent 外层 provide 真实实例；默认值给一个不读偏好、
 * 不转简体、无更新状态的降级实例，保证未注入时（预览、孤立测试）界面仍能组合。
 */
private object PreviewPrefStore : PrefStore {
    private val values = mutableMapOf<String, Any>()
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key] as? Boolean ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getString(key: String, defaultValue: String?): String? =
        values[key] as? String ?: defaultValue
    override fun putString(key: String, value: String) { values[key] = value }
}

internal val LocalDisplayPreferences = staticCompositionLocalOf {
    DisplayPreferences(PreviewPrefStore) { SimplifierTable(emptyMap(), emptyMap()) }
}

internal val LocalUpdateStatus = staticCompositionLocalOf {
    UpdateStatus(
        store = PreviewPrefStore,
        checker = { UpdateResult(null, false, null) },
    )
}
