package org.siongdict.app.data

/**
 * 数据文件维护端口：把「assets → 私有目录」的供给、版本比对与重置从查询里分出来。
 * 这三个动作的生命周期与查询不同（首装、升级、用户手动重置），失败模式也不同。
 */
interface DatabaseMaintenance {

    /** versionName 与记录值不一致 → 需要从 assets 重拷。只读偏好，主线程安全。 */
    fun isOutdated(): Boolean

    /** 确保两个库就位并打开连接；首次安装的 54 MB 复制在这里发生。 */
    suspend fun prepare()

    /** 关连接、删私有库文件、从 assets 重拷；完成后 info 缓存按 generation 自动失效。 */
    suspend fun reset()
}

internal class LocalDatabaseMaintenance(
    private val files: DatabaseFiles,
    private val gate: DbGate,
) : DatabaseMaintenance {

    override fun isOutdated(): Boolean = files.isOutdated()

    override suspend fun prepare() = gate.run { files.prepare() }

    override suspend fun reset() = gate.run { files.reset() }
}
