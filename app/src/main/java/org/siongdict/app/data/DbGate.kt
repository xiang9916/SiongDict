package org.siongdict.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 数据层唯一的串行入口：一个 Mutex + 固定的 IO 线程。
 *
 * 拆分前这条契约写在数据层单类的类注释里、靠调用方（ViewModel）兑现；
 * 现在由数据层自己保证：查询与复制/重置之间的并发都在这里排序。
 */
internal class DbGate(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun <T> run(block: () -> T): T =
        mutex.withLock { withContext(dispatcher) { block() } }
}
