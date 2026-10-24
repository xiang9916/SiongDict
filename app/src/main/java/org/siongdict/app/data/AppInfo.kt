package org.siongdict.app.data

import android.content.Context

/**
 * App 自身的包装信息。原先挂在数据层单类的 companion 上：
 * 数据库版本记录与界面标题都要读 versionName，但它不是数据库的职责。
 */
internal object AppInfo {

    /** 从 PackageManager 取 versionName；失败时返回空串（只影响显示与版本比对）。 */
    fun versionName(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
