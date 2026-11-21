package org.siongdict.app.data

/** 内存 [PrefStore]：JVM 测试与预览用，不碰 Android SharedPreferences。 */
internal class MemoryPrefStore(initial: Map<String, Any> = emptyMap()) : PrefStore {
    private val values = LinkedHashMap<String, Any>(initial)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key] as? Boolean ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getString(key: String, defaultValue: String?): String? =
        values[key] as? String ?: defaultValue

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}
