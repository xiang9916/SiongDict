package org.siongdict.app.data

import android.content.Context

/**
 * 偏好存储接缝：生产环境用 [SharedPrefsStore]（SharedPreferences），测试与预览用内存 fake。
 *
 * 只暴露本专案实际用到的四种操作；换存储后端（如 DataStore）只需换适配器，
 * 上层 [DisplayPreferences] 与 [UpdateStatus] 不用改。
 */
internal interface PrefStore {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, defaultValue: String?): String?
    fun putString(key: String, value: String)
}

/** 生产适配器：所有键仍在 [AppPrefs.FILE] 这一个偏好档里。 */
internal class SharedPrefsStore(context: Context) : PrefStore {
    private val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
