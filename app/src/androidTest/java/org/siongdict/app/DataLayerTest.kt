package org.siongdict.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.siongdict.app.data.LocalData
import org.siongdict.app.data.ToneFormatter
import org.siongdict.app.ui.SearchUiState
import org.siongdict.app.ui.SearchViewModel

/**
 * 数据层集成测试（真机/模拟器）：覆盖只读连接、异体展开、同源词查询、声调格式化、分区、
 * 重置后连接与缓存重建、文件缺失自愈，以及检索与重置的串行化 + 结果代际守卫。
 *
 * 断言只依赖「不变量」（有序、数量相等、非空、往返一致），不写死条数，
 * 以免上游数据更新后测试误红。
 */
@RunWith(AndroidJUnit4::class)
class DataLayerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun data() = LocalData(ctx)

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** 轮询 ViewModel 状态直到满足条件（检索在数据层 IO 线程，不能只 waitForIdle）。 */
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

    private fun newViewModel(d: LocalData): SearchViewModel {
        lateinit var vm: SearchViewModel
        onMain { vm = SearchViewModel(d.maintenance, d.lexicon, d.cognateBook, "test") }
        return vm
    }

    @Test
    fun searchByCharReturnsSortedRows() {
        val results = runBlocking { data().lexicon.searchByChar("湘") }
        assertTrue("searchByChar(湘) 应有结果", results.isNotEmpty())
        assertTrue(results.all { it.chars.isNotBlank() && it.lang.isNotBlank() && it.ipa.isNotBlank() })
        val sortKeys = results.map { it.sortKey }
        assertEquals("结果应按音典排序", sortKeys.sorted(), sortKeys)
    }

    @Test
    fun searchByMeaningMatchesAcrossVariants() {
        val d = data()
        val (traditional, simplified) = runBlocking {
            d.lexicon.searchByMeaning("濕") to d.lexicon.searchByMeaning("湿")
        }
        assertTrue("搜釋義(濕) 应有结果", traditional.isNotEmpty())
        assertEquals("繁简异体应命中等量结果", traditional.size, simplified.size)
    }

    @Test
    fun variantsCoverTraditionalAndSimplified() {
        val d = data()
        val variants = runBlocking { d.lexicon.variantsOf("濕") }
        assertTrue("濕 的异体组应包含 湿：$variants", variants.contains("湿"))
        assertTrue(d.variants.expand("濕").size > 1)
        // 组合数超过上限（64）时退回原查询
        assertEquals(listOf("濕濕濕濕"), d.variants.expand("濕濕濕濕"))
    }

    @Test
    fun cognateResultsAreOrdered() {
        val d = data()
        assertTrue("搜同源(彎腰) 应至少命中一组", runBlocking { d.cognateBook.search("彎腰") }.isNotEmpty())
        val ids = runBlocking { d.cognateBook.search("躲") }.map { it.groupId }
        assertTrue("搜同源(躲) 应命中多组", ids.size > 1)
        assertEquals(
            "搜同源结果应按组名（不分大小写）排序",
            ids.sortedWith(compareBy(String::lowercase).thenBy { it }),
            ids
        )
    }

    @Test
    fun cognateRoundTripByLangAndIpa() {
        val d = data()
        val group = runBlocking { d.cognateBook.search("彎腰") }.firstOrNull { it.members.isNotEmpty() }
        assertNotNull("需要一个带成员的组", group)
        val member = group!!.members.first()
        val same = runBlocking { d.cognateBook.groupFor(member.lang, member.ipa) }
        assertNotNull("(lang, ipa) 应能反查回同源词组", same)
        assertEquals(group.groupId, same!!.groupId)
        assertNull("不存在的读音应返回 null", runBlocking { d.cognateBook.groupFor(member.lang, member.ipa + "zzz") })
    }

    @Test
    fun toneSystemFormatting() {
        val toneSystem = runBlocking { data().lexicon.toneSystem("長沙") }
        assertNotNull("長沙 声调表应能解析", toneSystem)
        val formatted = ToneFormatter.format("tən1", toneSystem)
        assertNotEquals("调类应转成调值符号", "tən1", formatted)
        assertTrue("音节部分应保留：$formatted", formatted.startsWith("tən"))
        assertEquals("无调类编号时原样返回", "tən", ToneFormatter.format("tən", toneSystem))
    }

    @Test
    fun dialectDivisionLookup() {
        assertTrue(runBlocking { data().lexicon.dialectDivision("長沙") }.isNotBlank())
    }

    @Test
    fun forceResetRebuildsConnections() {
        val d = data()
        val charsBefore = runBlocking { d.lexicon.searchByChar("湘").size }
        val groupsBefore = runBlocking { d.cognateBook.search("彎腰").size }
        assertTrue(charsBefore > 0)

        runBlocking { d.maintenance.reset() } // 关连接 → 删库 → 重拷 → 重新打开

        assertEquals("重置后字音库仍可查询", charsBefore, runBlocking { d.lexicon.searchByChar("湘").size })
        assertEquals("重置后同源库仍可查询", groupsBefore, runBlocking { d.cognateBook.search("彎腰").size })
        assertTrue(runBlocking { d.lexicon.searchByMeaning("彎腰") }.isNotEmpty())
        assertFalse(d.maintenance.isOutdated())
        // 重置后 info 缓存应按 generation 重建
        assertNotNull(runBlocking { d.lexicon.toneSystem("長沙") })
    }

    @Test
    fun missingDatabaseFileIsRecreatedOnFirstQuery() {
        val d = data()
        assertTrue(runBlocking { d.lexicon.searchByChar("湘") }.isNotEmpty())

        ctx.deleteDatabase("siongdict.db")

        // 旧连接仍指向已 unlink 的 inode；新建一套端口应发现文件缺失并从 assets 自愈
        val fresh = data()
        assertTrue(
            "私有副本被删后首次查询应触发重拷",
            runBlocking { fresh.lexicon.searchByChar("湘") }.isNotEmpty()
        )
    }

    @Test
    fun rapidResearchesLastWins() {
        val vm = newViewModel(data())

        onMain { vm.updateQuery("湘"); vm.search() }
        onMain { vm.updateQuery("行"); vm.search() }

        val state = waitForState(vm) { !it.loading && it.results.isNotEmpty() }
        assertTrue("最后一次检索结果应含「行」", state.results.any { it.chars == "行" })
        assertFalse("旧检索结果不得回写", state.results.any { it.chars == "湘" })
    }

    @Test
    fun resetDuringSearchDoesNotCrash() {
        val vm = newViewModel(data())

        onMain { vm.updateQuery("的一是"); vm.search() }
        onMain { vm.resetDatabases() } // 检索仍在进行时重置

        val reset = waitForState(vm) { !it.loading && !it.searched && it.results.isEmpty() }
        assertFalse(reset.dbOutdated)

        onMain { vm.updateQuery("湘"); vm.search() }
        val after = waitForState(vm) { !it.loading && it.results.isNotEmpty() }
        assertTrue("重置后应能继续检索", after.results.isNotEmpty())
    }

    /**
     * 同源词库存与调类不变量：组数、条数、本版新增的 14 组、两腿分開的调类范围，
     * 以及东安横塘随上游移出后 Saw_ge4 的成员数。直接读 cognates.db，覆盖人工编辑结果。
     */
    @Test
    fun cognateInventoryMatchesRelease() = runBlocking {
        val d = data()
        d.maintenance.prepare()
        val db = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath("cognates.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            fun scalar(sql: String, vararg args: String): Int =
                db.rawQuery(sql, args).use { c -> c.moveToFirst(); c.getInt(0) }

            assertEquals(2362, scalar("SELECT COUNT(*) FROM cognates"))
            assertEquals(169, scalar("SELECT COUNT(DISTINCT cognate_group) FROM cognates"))

            val added = listOf(
                "ShakeOffDust_lɔŋ4", "ShakeOffDust_pʰɔŋ3", "Naughty_tsa1",
                "Bottom_tuk7", "Heel_lɔŋ8", "Swing_xuak7", "Wing_ie8",
                "InsistOn_tai1", "InsistOn_tan1", "Calf_ȵiau2", "Calf_ȵiam2",
                "SpreadLegs_ba4", "Provoke_ȵiaŋ1", "Collide_guan4",
                "Bumpy_pʰau3"
            )
            for (g in added) {
                assertTrue("缺少同源词组 $g", scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = ?", g) >= 2)
            }

            assertEquals(
                "两腿分開只收调类 3/6",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'SpreadLegs_ba4' AND tone_cat NOT IN ('3','6')")
            )
            assertEquals(10, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Bumpy_pʰau3'"))
            assertEquals(
                "Bumpy_pʰau3 只收调类 3",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Bumpy_pʰau3' AND tone_cat <> '3'")
            )
            assertEquals(5, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'ShakeOffDust_pʰɔŋ3'"))
            assertEquals(12, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Saw_ge4'"))
            assertEquals(0, scalar("SELECT COUNT(*) FROM cognates WHERE lang = '東安橫塘'"))
        } finally {
            db.close()
        }
    }

}
