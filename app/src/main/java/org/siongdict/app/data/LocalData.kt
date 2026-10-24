package org.siongdict.app.data

import android.content.Context

/**
 * 数据层的本地装配根：文件/连接（[DatabaseFiles]）、串行化（[DbGate]）、
 * 三个只读端口与变体表在这里组装。
 *
 * 生产入口是 [org.siongdict.app.ui.SearchViewModelFactory]；
 * androidTest 用它直接构造端口做集成测试，JVM 测试则用 fake 端口。
 */
internal class LocalData(context: Context) {

    private val appContext = context.applicationContext
    private val files = DatabaseFiles(appContext)
    private val gate = DbGate()

    val variants = AssetVariantIndex(appContext)
    val maintenance: DatabaseMaintenance = LocalDatabaseMaintenance(files, gate)
    val lexicon: Lexicon = LocalLexicon(files, gate, variants)
    val cognateBook: CognateBook = LocalCognateBook(files, gate, variants)
}
