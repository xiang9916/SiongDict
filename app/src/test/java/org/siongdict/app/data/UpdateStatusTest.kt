package org.siongdict.app.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新检查状态：注入 fake 查询函数与内存 store。 */
class UpdateStatusTest {

    private fun status(
        store: MemoryPrefStore = MemoryPrefStore(),
        checker: suspend (String) -> UpdateResult = { UpdateResult(null, false, null) },
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher(),
    ) = UpdateStatus(store = store, checker = checker, ioDispatcher = ioDispatcher)

    @Test
    fun initReadsCachedTag() {
        val store = MemoryPrefStore(mapOf(AppPrefs.KEY_UPDATE_LATEST to "0.5-alpha.9"))
        val status = status(store = store)
        status.init()
        assertEquals("0.5-alpha.9", status.result?.latestTag)
        assertTrue(status.hasUpdate("0.5-alpha.5"))
        assertFalse(status.hasUpdate("0.5-alpha.9"))
    }

    @Test
    fun hasUpdateIsFalseWithoutResultOrVersion() {
        val status = status()
        assertFalse(status.hasUpdate("0.5-alpha.5"))
        status.init()
        assertFalse(status.hasUpdate("0.5-alpha.5"))
        val cached = status(store = MemoryPrefStore(mapOf(AppPrefs.KEY_UPDATE_LATEST to "0.5-alpha.9")))
        cached.init()
        assertFalse(cached.hasUpdate(""))
    }

    @Test
    fun refreshStoresLatestTagAndClearsChecking() = runTest {
        val store = MemoryPrefStore()
        val status = status(
            store = store,
            checker = { UpdateResult("0.5-alpha.9", true, null) },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        status.refresh("0.5-alpha.5")
        assertEquals("0.5-alpha.9", status.result?.latestTag)
        assertEquals("0.5-alpha.9", store.getString(AppPrefs.KEY_UPDATE_LATEST, null))
        assertFalse(status.checking)
        assertTrue(status.hasUpdate("0.5-alpha.5"))
    }

    @Test
    fun refreshFailureIsSilentAndKeepsCache() = runTest {
        val store = MemoryPrefStore(mapOf(AppPrefs.KEY_UPDATE_LATEST to "0.5-alpha.5"))
        val status = status(
            store = store,
            checker = { UpdateResult(null, false, "網路錯誤") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        status.init()
        status.refresh("0.5-alpha.5")
        assertEquals("網路錯誤", status.result?.error)
        assertEquals("0.5-alpha.5", store.getString(AppPrefs.KEY_UPDATE_LATEST, null))
        assertNull(status.result?.latestTag)
        assertFalse(status.checking)
    }

    @Test
    fun concurrentRefreshesInvokeCheckerOnce() = runTest {
        val calls = AtomicInteger()
        val status = status(
            checker = {
                delay(100)
                calls.incrementAndGet()
                UpdateResult("0.5-alpha.9", true, null)
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val first = launch { status.refresh("0.5-alpha.5") }
        val second = launch { status.refresh("0.5-alpha.5") }
        advanceUntilIdle()
        first.join()
        second.join()
        assertEquals(1, calls.get())
        assertFalse(status.checking)
    }
}
