package org.siongdict.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.siongdict.app.data.DictDatabase
import org.siongdict.app.data.ToneFormatter
import org.siongdict.app.ui.SearchUiState
import org.siongdict.app.ui.SearchViewModel

/**
 * 数据层回归测试（真机/模拟器）：覆盖读写只读连接、异体展开、同源词查询、
 * 声调格式化，以及本次重构新增的两件事——**forceReset 后连接可重建**、
 * **检索与重置串行化 + 结果代际守卫**。
 *
 * 断言只依赖「不变量」（有序、数量相等、非空、往返一致），不写死条数，
 * 以免上游数据更新后测试误红。
 */
@RunWith(AndroidJUnit4::class)
class DataLayerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun db() = DictDatabase(ctx)

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** 轮询 ViewModel 状态直到满足条件（检索在 IO 线程，不能只 waitForIdle）。 */
    private fun waitForState(vm: SearchViewModel, predicate: (SearchUiState) -> Boolean): SearchUiState {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            var state: SearchUiState? = null
            onMain { state = vm.uiState.value }
            val current = state!!
            if (predicate(current)) return current
            Thread.sleep(50)
        }
        fail("等待检索状态超时")
        error("unreachable")
    }

    @Test
    fun searchByCharReturnsSortedRows() {
        val results = db().searchByChar("湘")
        assertTrue("searchByChar(湘) 应有结果", results.isNotEmpty())
        assertTrue(results.all { it.chars.isNotBlank() && it.lang.isNotBlank() && it.ipa.isNotBlank() })
        val sortKeys = results.map { it.sortKey }
        assertEquals("结果应按音典排序", sortKeys.sorted(), sortKeys)
    }

    @Test
    fun searchByMeaningMatchesAcrossVariants() {
        val db = db()
        val traditional = db.searchByMeaning("濕")
        val simplified = db.searchByMeaning("湿")
        assertTrue("搜釋義(濕) 应有结果", traditional.isNotEmpty())
        assertEquals("繁简异体应命中等量结果", traditional.size, simplified.size)
    }

    @Test
    fun variantsCoverTraditionalAndSimplified() {
        val db = db()
        val variants = db.getVariants("濕")
        assertTrue("濕 的异体组应包含 湿：$variants", variants.contains("湿"))
        assertTrue(db.expandQueryVariants("濕").size > 1)
        // 组合数超过上限（64）时退回原查询
        assertEquals(listOf("濕濕濕濕"), db.expandQueryVariants("濕濕濕濕"))
    }

    @Test
    fun cognateResultsAreOrdered() {
        val db = db()
        assertTrue("搜同源(彎腰) 应至少命中一组", db.searchCognates("彎腰").isNotEmpty())
        val ids = db.searchCognates("躲").map { it.groupId }
        assertTrue("搜同源(躲) 应命中多组", ids.size > 1)
        assertEquals(
            "搜同源结果应按组名（不分大小写）排序",
            ids.sortedWith(compareBy(String::lowercase).thenBy { it }),
            ids
        )
    }

    @Test
    fun cognateRoundTripByLangAndIpa() {
        val db = db()
        val group = db.searchCognates("彎腰").firstOrNull { it.members.isNotEmpty() }
        assertNotNull("需要一个带成员的组", group)
        val member = group!!.members.first()
        val same = db.getCognateGroup(member.lang, member.ipa)
        assertNotNull("(lang, ipa) 应能反查回同源词组", same)
        assertEquals(group.groupId, same!!.groupId)
        assertNull("不存在的读音应返回 null", db.getCognateGroup(member.lang, member.ipa + "zzz"))
    }

    @Test
    fun toneSystemFormatting() {
        val db = db()
        val toneSystem = db.getToneSystem("長沙")
        assertNotNull("長沙 声调表应能解析", toneSystem)
        val formatted = ToneFormatter.format("tən1", toneSystem)
        assertNotEquals("调类应转成调值符号", "tən1", formatted)
        assertTrue("音节部分应保留：$formatted", formatted.startsWith("tən"))
        assertEquals("无调类编号时原样返回", "tən", ToneFormatter.format("tən", toneSystem))
    }

    @Test
    fun dialectDivisionLookup() {
        assertTrue(db().getDialectDivision("長沙").isNotBlank())
    }

    @Test
    fun forceResetRebuildsConnections() {
        val db = db()
        val charsBefore = db.searchByChar("湘").size
        val groupsBefore = db.searchCognates("彎腰").size
        assertTrue(charsBefore > 0)

        db.forceReset() // 关连接 → 删库 → 重拷 → 重新打开

        assertEquals("重置后字音库仍可查询", charsBefore, db.searchByChar("湘").size)
        assertEquals("重置后同源库仍可查询", groupsBefore, db.searchCognates("彎腰").size)
        assertTrue(db.searchByMeaning("彎腰").isNotEmpty())
        assertFalse(db.isDatabaseOutdated())
    }

    @Test
    fun rapidResearchesLastWins() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lateinit var vm: SearchViewModel
        onMain { vm = SearchViewModel(app) }

        onMain { vm.updateQuery("湘"); vm.search() }
        onMain { vm.updateQuery("行"); vm.search() }

        val state = waitForState(vm) { !it.loading && it.results.isNotEmpty() }
        assertTrue("最后一次检索结果应含「行」", state.results.any { it.chars == "行" })
        assertFalse("旧检索结果不得回写", state.results.any { it.chars == "湘" })
    }

    @Test
    fun resetDuringSearchDoesNotCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lateinit var vm: SearchViewModel
        onMain { vm = SearchViewModel(app) }

        onMain { vm.updateQuery("的一是"); vm.search() }
        onMain { vm.resetDatabases() } // 检索仍在进行时重置

        val reset = waitForState(vm) { !it.loading && !it.searched && it.results.isEmpty() }
        assertFalse(reset.dbOutdated)

        onMain { vm.updateQuery("湘"); vm.search() }
        val after = waitForState(vm) { !it.loading && it.results.isNotEmpty() }
        assertTrue("重置后应能继续检索", after.results.isNotEmpty())
    }
}
