package org.siongdict.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase as RequerySQLiteDatabase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
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
import org.siongdict.app.analysis.ClusterAnalysisResult
import org.siongdict.app.analysis.ClusterEngine
import org.siongdict.app.analysis.fitTransform
import org.siongdict.app.analysis.groupedLayout3D
import org.siongdict.app.analysis.MapSite
import org.siongdict.app.analysis.minClusterSize
import org.siongdict.app.analysis.PlanePoint
import org.siongdict.app.analysis.voronoiCells
import org.siongdict.app.data.LocalData
import org.siongdict.app.data.MapAssets
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
     * 同源词库存与调类不变量：2,804 条 / 206 组、本版新增的 53 组、两腿分開的调类范围，
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

            // 成员集合与调类分布用列表比对：本版新增组的成员和点位被逐条写死，
            // 成员或点位变动会直接红，避免只查总数掩盖成员替换。
            fun members(group: String): List<String> =
                db.rawQuery(
                    "SELECT lang || '|' || ipa FROM cognates WHERE cognate_group = ?",
                    arrayOf(group)
                ).use { c ->
                    val rows = mutableListOf<String>()
                    while (c.moveToNext()) rows.add(c.getString(0))
                    rows
                }

            fun toneProfile(group: String): String =
                db.rawQuery(
                    "SELECT tone_cat || ':' || COUNT(*) FROM cognates WHERE cognate_group = ? " +
                        "GROUP BY tone_cat ORDER BY tone_cat",
                    arrayOf(group)
                ).use { c ->
                    val rows = mutableListOf<String>()
                    while (c.moveToNext()) rows.add(c.getString(0))
                    rows.joinToString(",")
                }

            // 2026-11-20 用户裁决：Cold/Hit 第二轮收录 14 条（ADR-0005 附录十九），2,757/201 → 2,771/202；
            // 同日第三轮 3 新增/1 移动/1 删除/1 合并（ADR-0005 附录二十），2,771/202 → 2,772/203；
            // 同日第四轮 3 个新组 32 条（ADR-0005 附录二十二），2,772/203 → 2,804/206。
            assertEquals(2804, scalar("SELECT COUNT(*) FROM cognates"))
            assertEquals(206, scalar("SELECT COUNT(DISTINCT cognate_group) FROM cognates"))

            val added = listOf(
                "ShakeOffDust_lɔŋ4", "ShakeOffDust_pʰɔŋ3", "Naughty_tsa1",
                "Bottom_tuk7", "Heel_lɔŋ8", "Swing_xuak7", "Wing_ie8",
                "InsistOn_tai1", "InsistOn_tan1", "Calf_ȵiau2", "Calf_ȵiam2",
                "SpreadLegs_ba4", "Provoke_ȵiaŋ1", "Collide_guan4",
                "Bumpy_pʰau3", "Loom_lɔm2",
                "Hiccup_sut7", "Hiccup_ŋek8", "Burp_ge4", "Burp_ke5",
                "Shout_wiak8", "Shout_tɕwia5", "Shout_ŋɔŋ1", "Shout_bau2",
                "Shout_ɣau2", "Shout_o5", "Shout_uai7", "Shout_si1",
                "Poking_ŋen3", "Poking_am5",
                "Suck_tswin1", "Suck_tsʰwien3",
                "Greedy_soŋ2",
                "Cold_laŋ4", "Cold_liaŋ4", "Hit_tiaŋ5", "Hit_tiaŋ3", "Hit_taŋ3", "Hit_tæ3", "Fat_dut8",
                "Shield_ʃwin3", "Rub_tsʰeu3", "Curse_fan3", "Curse_ʃwien3",
                "Pile_tsut7", "Pile_tʃiuk7", "Hungry_la8", "ScoopRice_xa3",
                "LookFor_nien2", "Hit_pʰiaŋ3",
                "Devour_tai3", "Cangue_ka1", "Extinguish_i5"
            )
            for (g in added) {
                assertTrue("缺少同源词组 $g", scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = ?", g) >= 2)
            }

            // 2026-11-10 用户裁决：8 组音类标签改名，成员与条数不变，旧名不得残留。
            val renamed = listOf(
                "Crop_swi5" to "Crop_sy5",
                "Shout_wiak8" to "Shout_yak8",
                "Bend_wit7" to "Bend_yt7",
                "Shout_tɕwia5" to "Shout_tɕya5",
                "Drink_swiet7" to "Drink_syet7",
                "Shadow_wiaŋ3" to "Shadow_yaŋ3",
                "Sew_lwien2" to "Sew_lyen2",
                "Curse_kwiet7" to "Curse_kyet7"
            )
            for ((newName, oldName) in renamed) {
                assertTrue(
                    "改名后缺少同源词组 $newName",
                    scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = ?", newName) >= 2
                )
                assertEquals(
                    "旧组名 $oldName 不得残留在 cognates",
                    0,
                    scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = ?", oldName)
                )
                assertEquals(
                    "旧组名 $oldName 不得残留在 cognate_groups",
                    0,
                    scalar("SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = ?", oldName)
                )
            }

            // 2026-11-10 用户裁决：新建 Poking_am5，5 条、final 全 an、调类跨 3/5/6。
            assertEquals(5, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Poking_am5'"))
            assertEquals(
                "Poking_am5 只收 -an 韵",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Poking_am5' AND final <> 'an'")
            )
            assertEquals(
                "Poking_am5 调类限 3/5/6",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Poking_am5' AND tone_cat NOT IN ('3','5','6')")
            )

            // 2026-11-10 用户裁决：原拟 A1（Suck_tsin1）与 A2（Suck_tɕyn1）合并为 Suck_tswin1（33 条），
            // B 类单独为 Suck_tsʰwien3（8 条）；tswin1 是词源层面的合并标签，不表示各点同韵。
            assertEquals(33, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Suck_tswin1'"))
            assertEquals(8, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Suck_tsʰwien3'"))
            assertTrue(
                "Suck_tswin1 合并前 A1/A2 分属不同韵类，final 应有多种",
                scalar("SELECT COUNT(DISTINCT final) FROM cognates WHERE cognate_group = 'Suck_tswin1'") >= 15
            )
            assertEquals(
                "Suck_tswin1 应同时含 in/yn/ueŋ/uən 四类韵母",
                4,
                scalar("SELECT COUNT(DISTINCT final) FROM cognates WHERE cognate_group = 'Suck_tswin1' AND final IN ('in','yn','ueŋ','uən')")
            )
            assertEquals(
                "Suck_tswin1 调类限阴平 1",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Suck_tswin1' AND tone_cat <> '1'")
            )
            assertEquals(
                "Suck_tsʰwien3 调类限上声 3",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Suck_tsʰwien3' AND tone_cat <> '3'")
            )
            assertEquals(
                "Suck 两组须同名义项 Suck/吸吮",
                0,
                scalar("SELECT COUNT(*) FROM cognate_groups WHERE cognate_group IN ('Suck_tswin1','Suck_tsʰwien3') AND (semantic_tag <> 'Suck' OR semantic_label <> '吸吮')")
            )
            for (oldName in listOf("Suck_tsin1", "Suck_tɕyn1")) {
                assertEquals(0, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = ?", oldName))
                assertEquals(0, scalar("SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = ?", oldName))
            }

            // 用户裁决 m01425：新建 Greedy_soŋ2（嘴饞/貪吃）10 条，s + 通摄阳平。
            assertEquals(10, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Greedy_soŋ2'"))
            assertEquals(10, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Greedy_soŋ2'"))
            assertEquals(
                "Greedy_soŋ2 声母须全为 s",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Greedy_soŋ2' AND initial <> 's'")
            )
            assertEquals(
                "Greedy_soŋ2 调类须全为阳平 2",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Greedy_soŋ2' AND tone_cat <> '2'")
            )
            assertEquals(
                "Greedy_soŋ2 注释须全非空",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Greedy_soŋ2' AND (note IS NULL OR TRIM(note) = '')")
            )
            assertEquals(
                "Greedy_soŋ2 成员不得与既有组 (lang, ipa) 重叠",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Greedy_soŋ2' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Greedy_soŋ2')"
                )
            )
            assertEquals(
                "Greedy_soŋ2 组参数（Greedy/嘴饞/貪吃，10 条 10 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Greedy_soŋ2' " +
                        "AND semantic_tag = 'Greedy' AND semantic_label = '嘴饞/貪吃' " +
                        "AND member_count = 10 AND dialect_count = 10"
                )
            )

            // 2026-11-13 用户裁决：新建 Cold 两组（冷）与 Hit 两组（打）；写库后按用户裁决纠偏：
            // Cold_laŋ4 删除 12 条文读层读音，只留梗摄二等白读（11 条 9 点）；Cold_liaŋ4 增补长沙系 4 条（9 条 8 点）；
            // 2026-11-20 第二轮：Cold_laŋ4 +7（18 条 16 点）、Cold_liaŋ4 +1（10 条 9 点）、Hit_tiaŋ5 +3（9 条 9 点）、
            // Hit_tæ3 +1（33 条 33 点）、新建 Hit_pʰiaŋ3（2 条 2 点）；5 条空注释例外见 ADR-0005 附录十九。
            // 2026-11-13 当时：Hit_tiaŋ5 删除益陽 tʰiɔ̃1 / 醴陵石亭 pʰiaŋ3，补江永桃川 liɛ3 / 湘陰界頭鋪 tiã5（6 条 6 点）；
            // Hit_tæ3 扩为 31 个新增点（独立韵 a/æ/ɑ/ᴀ，32 条 32 点）——这两组已在 2026-11-20 第二轮增至 9 条 9 点、33 条 33 点。
            // 组名末位数字是音类标签，不代表成员 tone_cat。Cold_laŋ4 收梗摄二等白读（來母 l/n 自由变体）。
            assertEquals(18, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_laŋ4'"))
            assertEquals(16, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Cold_laŋ4'"))
            assertEquals(
                "Cold_laŋ4 调类分布（1/3/4/5/6 五调共 18 行）",
                "1:2,3:13,4:1,5:1,6:1",
                toneProfile("Cold_laŋ4")
            )
            assertEquals(
                "Cold_laŋ4 声母限來母 l/n 自由变体，另许嘉禾普滿 d-（附录十九）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_laŋ4' AND initial NOT IN ('l','n','d')")
            )
            assertEquals(
                "Cold_laŋ4 梗二白读无 -i- 介音（嘉禾普滿 diɛ3 为 d- 层例外，附录十九）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_laŋ4' AND final LIKE 'i%' AND lang <> '嘉禾普滿'")
            )
            assertEquals(
                "Cold_laŋ4 空注释恰 4 条（岳陽路口/湘潭花石/湘陰界頭鋪/攸縣皇圖嶺，附录十九；第 5 条空注例外 冷水江鐸山 tᴀ3 在 Hit_tæ3）",
                4,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_laŋ4' AND (note IS NULL OR TRIM(note) = '')")
            )
            assertEquals(
                "Cold_laŋ4 已删的 12 条文读层 id 不得残留",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE id IN (2589,2590,2593,2596,2597,2599,2600,2601,2603,2606,2607,2611)")
            )
            assertEquals(
                "Cold_laŋ4 仅 汨羅長樂|loŋ3 与 ShakeOffDust_lɔŋ4 同 (lang, ipa) 并存（附录十九第 45 对重复）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Cold_laŋ4' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Cold_laŋ4')"
                )
            )
            assertEquals(
                "Cold_laŋ4 除 汨羅長樂|loŋ3 外不得再有跨组同音",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Cold_laŋ4' " +
                        "AND NOT (c.lang = '汨羅長樂' AND c.ipa = 'loŋ3') AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Cold_laŋ4')"
                )
            )
            assertEquals(
                "汨羅長樂|loŋ3 應同時留在 ShakeOffDust_lɔŋ4（id 2227）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'ShakeOffDust_lɔŋ4' " +
                        "AND lang = '汨羅長樂' AND ipa = 'loŋ3'"
                )
            )
            assertEquals(
                "Cold_laŋ4 组参数（Cold/冷，18 条 16 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Cold_laŋ4' " +
                        "AND semantic_tag = 'Cold' AND semantic_label = '冷' " +
                        "AND member_count = 18 AND dialect_count = 16"
                )
            )
            assertEquals(
                "Cold_laŋ4 成员 (lang|ipa) 集合（18 条 16 点）",
                listOf(
                    "安福瓜畲|lãŋ3",
                    "安福瓜畲|lãŋ4",
                    "攸縣新市|laŋ1",
                    "攸縣新市|laŋ5",
                    "桂陽六合|lɔ̃3",
                    "石泉中池|nuɑ̃3",
                    "耒陽|lɔ̃3",
                    "臨桂陶善|no3",
                    "銅仁漾頭|nɔ̃3",
                    "靈川三街|la3",
                    "鳳凰|laŋ3",
                    "岳陽路口|naŋ3",
                    "湘潭花石|lɔ̃3",
                    "湘陰界頭鋪|lã3",
                    "攸縣皇圖嶺|lai6",
                    "嘉禾普滿|diɛ3",
                    "汝城|lɜŋ1",
                    "汨羅長樂|loŋ3",
                ).sorted(),
                members("Cold_laŋ4").sorted()
            )

            // Cold_liaŋ4 收梗摄四等带 -i- 介音；臨武鎮南 liɑŋ3/liɑŋ4 同点两条，故 9 条 8 点（DISTINCT lang=8）。
            // 2026-11-20 第二轮 +1 道縣祥霖鋪 lu5（10 条 9 点，附录十九）。
            assertEquals(10, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_liaŋ4'"))
            assertEquals(9, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Cold_liaŋ4'"))
            assertEquals(
                "Cold_liaŋ4 调类分布（3/4/5 三调共 10 行）",
                "3:5,4:4,5:1",
                toneProfile("Cold_liaŋ4")
            )
            assertEquals(
                "Cold_liaŋ4 声母须为來母 l/n（韶山 niæ̃3 为 n-）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_liaŋ4' AND initial NOT IN ('l','n')")
            )
            assertEquals(
                "Cold_liaŋ4 梗四白读须带 -i- 介音（道縣祥霖鋪 lu5 为 u 口型，附录十九）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_liaŋ4' AND final NOT LIKE 'i%' AND lang <> '道縣祥霖鋪'")
            )
            assertEquals(
                "Cold_liaŋ4 空注释恰 1 条（韶山 niæ̃3）",
                1,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_liaŋ4' AND (note IS NULL OR TRIM(note) = '')")
            )
            assertEquals(
                "Cold_liaŋ4 文读层不得混入（无 lən 行）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_liaŋ4' AND initial = 'l' AND final IN ('ən','ə̃ŋ')")
            )
            assertEquals(
                "Cold_liaŋ4 成员不得与既有组 (lang, ipa) 重叠",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Cold_liaŋ4' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Cold_liaŋ4')"
                )
            )
            assertEquals(
                "Cold_liaŋ4 新增长沙系 4 条须在组内",
                4,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_liaŋ4' AND (lang || '|' || ipa) IN " +
                        "('長沙雙江|liɒŋ4','長沙金井|liaŋ4','湘音檢字|lian3','韶山|niæ̃3')"
                )
            )
            assertEquals(
                "Cold_liaŋ4 组参数（Cold/冷，10 条 9 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Cold_liaŋ4' " +
                        "AND semantic_tag = 'Cold' AND semantic_label = '冷' " +
                        "AND member_count = 10 AND dialect_count = 9"
                )
            )
            assertEquals(
                "Cold_liaŋ4 成员 (lang|ipa) 集合（10 条 9 点）",
                listOf(
                    "冷水江鐸山|liᴀ̃3",
                    "江永夏層舖|lioŋ4",
                    "湘音檢字|lian3",
                    "臨武鎮南|liɑŋ3",
                    "臨武鎮南|liɑŋ4",
                    "達縣安仁|liaŋ3",
                    "長沙金井|liaŋ4",
                    "長沙雙江|liɒŋ4",
                    "韶山|niæ̃3",
                    "道縣祥霖鋪|lu5",
                ).sorted(),
                members("Cold_liaŋ4").sorted()
            )

            // Hit_tiaŋ5 收梗摄四等白读（带 -i- 介音，参考调类 5）；2026-11-20 第二轮 +3，同日第三轮移出江永桃川 liɛ3（改归 Hit_tiaŋ3），
            // 现为 8 条 8 点；新晃凳寨 tʰiaŋ5 / 汝城 tiaŋ5 / 綏寧河口 ȵiʌŋ3 按「打」语素同一性并入，声母遂含 tʰ 与 ȵ（附录十九、二十）。
            assertEquals(8, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ5'"))
            assertEquals(8, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Hit_tiaŋ5'"))
            assertEquals(
                "Hit_tiaŋ5 调类分布（3/5 两调共 8 行）",
                "3:1,5:7",
                toneProfile("Hit_tiaŋ5")
            )
            assertEquals(
                "Hit_tiaŋ5 声母限 t/l，另许 tʰ/ȵ（新晃凳寨、綏寧河口，附录十九）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ5' AND initial NOT IN ('t','l','tʰ','ȵ')")
            )
            assertEquals(
                "Hit_tiaŋ5 梗四白读须带 -i- 介音",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ5' AND final NOT LIKE 'i%'")
            )
            assertEquals(
                "Hit_tiaŋ5 注释须全非空",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ5' AND (note IS NULL OR TRIM(note) = '')")
            )
            assertEquals(
                "Hit_tiaŋ5 已删的益陽/醴陵石亭两条不得残留",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE (lang = '益陽' AND ipa = 'tʰiɔ̃1') OR (lang = '醴陵石亭' AND ipa = 'pʰiaŋ3')")
            )
            assertEquals(
                "Hit_tiaŋ5 第三轮后仅湘陰界頭鋪一条命中（江永桃川 liɛ3 已移出至 Hit_tiaŋ3，附录二十）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ5' AND (lang || '|' || ipa) IN " +
                        "('江永桃川|liɛ3','湘陰界頭鋪|tiã5')"
                )
            )
            assertEquals(
                "Hit_tiaŋ5 成员不得与既有组 (lang, ipa) 重叠",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Hit_tiaŋ5' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Hit_tiaŋ5')"
                )
            )
            assertEquals(
                "Hit_tiaŋ5 组参数（Hit/打，8 条 8 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Hit_tiaŋ5' " +
                        "AND semantic_tag = 'Hit' AND semantic_label = '打' " +
                        "AND member_count = 8 AND dialect_count = 8"
                )
            )
            assertEquals(
                "Hit_tiaŋ5 成员 (lang|ipa) 集合（8 条 8 点）",
                listOf(
                    "冷水江鐸山|tiᴀ̃5a",
                    "新長沙|tiæ̃5",
                    "湘潭易俗河|tiã5",
                    "湘陰界頭鋪|tiã5",
                    "長沙|tiã5",
                    "新晃凳寨|tʰiaŋ5",
                    "汝城|tiaŋ5",
                    "綏寧河口|ȵiʌŋ3",
                ).sorted(),
                members("Hit_tiaŋ5").sorted()
            )

            // Hit_tæ3 收非麻韵独立韵（a/æ/ɑ/ᴀ）：2026-11-20 第二轮 +1 冷水江鐸山 tᴀ3（33 条 33 点）；
            // 无注成员属 ADR-0005 登记例外（附录十九），仅桑植芙蓉橋 tæ3 保留原注「~架」。
            assertEquals(33, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tæ3'"))
            assertEquals(33, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Hit_tæ3'"))
            assertEquals(
                "Hit_tæ3 调类分布（上声 3 共 33 行）",
                "3:33",
                toneProfile("Hit_tæ3")
            )
            assertEquals(
                "Hit_tæ3 调类限上声 3",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tæ3' AND tone_cat <> '3'")
            )
            assertEquals(
                "Hit_tæ3 声母须全为 t",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tæ3' AND initial <> 't'")
            )
            assertEquals(
                "Hit_tæ3 独立韵应有 a/æ/ɑ/ᴀ 4 种",
                4,
                scalar("SELECT COUNT(DISTINCT final) FROM cognates WHERE cognate_group = 'Hit_tæ3'")
            )
            assertEquals(
                "Hit_tæ3 韵母限 a/æ/ɑ/ᴀ",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tæ3' AND final NOT IN ('a','æ','ɑ','ᴀ')")
            )
            assertEquals(
                "Hit_tæ3 空注释恰 32 条（仅桑植芙蓉橋 tæ3 有注；冷水江鐸山 tᴀ3 为附录十九例外）",
                32,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tæ3' AND (note IS NULL OR TRIM(note) = '')")
            )
            assertEquals(
                "Hit_tæ3 有注的只应是桑植芙蓉橋 tæ3",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tæ3' AND lang = '桑植芙蓉橋' " +
                        "AND ipa = 'tæ3' AND note IS NOT NULL AND TRIM(note) <> ''"
                )
            )
            assertEquals(
                "Hit_tæ3 仅桑植芙蓉橋 tæ3 与 Devour_tai3 跨组同音（附录二十二 §六）",
                listOf("桑植芙蓉橋|tæ3|Devour_tai3"),
                db.rawQuery(
                    "SELECT c.lang || '|' || c.ipa || '|' || o.cognate_group FROM cognates c " +
                        "JOIN cognates o ON o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Hit_tæ3' " +
                        "WHERE c.cognate_group = 'Hit_tæ3' ORDER BY o.cognate_group",
                    null
                ).use { c ->
                    val rows = mutableListOf<String>()
                    while (c.moveToNext()) rows.add(c.getString(0))
                    rows
                }
            )
            assertEquals(
                "Hit_tæ3 组参数（Hit/打，33 条 33 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Hit_tæ3' " +
                        "AND semantic_tag = 'Hit' AND semantic_label = '打' " +
                        "AND member_count = 33 AND dialect_count = 33"
                )
            )
            assertEquals(
                "Hit_tæ3 成员 (lang|ipa) 集合（33 条 33 点）",
                listOf(
                    "古丈死客子話|ta3",
                    "冷水江鐸山|tᴀ3",
                    "婁底|ta3",
                    "婁底石井|ta3",
                    "婁底萬寶|ta3",
                    "安化高明|ta3",
                    "寧遠張家|ta3",
                    "寧鄉大成橋|ta3",
                    "寧鄉花明樓|ta3",
                    "寧鄉黃材|ta3",
                    "新邵坪上|ta3",
                    "桂陽歐陽海|ta3",
                    "桑植芙蓉橋|tæ3",
                    "永州杉木橋|ta3",
                    "永興馬田|tᴀ3",
                    "沅陵仰溪鋪|ta3",
                    "湘鄉|ta3",
                    "湘鄉棋梓|ta3",
                    "漣源|ta3",
                    "漣源橋頭河|tɑ3",
                    "漵浦|tɑ3",
                    "瀘溪武溪|ta3",
                    "石泉中池|tᴀ3",
                    "辰谿|ta3",
                    "達縣安仁|ta3",
                    "達縣新勝|ta3",
                    "邵東斫曹|ta3",
                    "雙峰|ta3",
                    "雙峰三塘鋪|ta3",
                    "雙峰甘棠|ta3",
                    "雙峰花門|ta3",
                    "韶山|ta3",
                    "韶山大坪|ta3",
                ).sorted(),
                members("Hit_tæ3").sorted()
            )

            // 2026-11-20 用户裁决：新建 Hit_pʰiaŋ3（打，2 条 2 点），收梗二/梗四白读送气 pʰ-：
            // 寧鄉花明樓 pʰiã3「打」、新長沙 pʰiæ̃3「打,擊」；两条注释均非空且语义相符（ADR-0005 附录十九）。
            assertEquals(2, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_pʰiaŋ3'"))
            assertEquals(2, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Hit_pʰiaŋ3'"))
            assertEquals(
                "Hit_pʰiaŋ3 调类分布（上声 3 共 2 行）",
                "3:2",
                toneProfile("Hit_pʰiaŋ3")
            )
            assertEquals(
                "Hit_pʰiaŋ3 声母须全为送气 pʰ",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_pʰiaŋ3' AND initial <> 'pʰ'")
            )
            assertEquals(
                "Hit_pʰiaŋ3 韵母须带 -i- 介音（梗三四白读）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_pʰiaŋ3' AND final NOT LIKE 'i%'")
            )
            assertEquals(
                "Hit_pʰiaŋ3 两条注释均不得为空（ADR-0005 硬规则）",
                2,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_pʰiaŋ3' " +
                        "AND note IS NOT NULL AND trim(note) <> ''"
                )
            )
            assertEquals(
                "Hit_pʰiaŋ3 成员不得与既有组 (lang, ipa) 重叠",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Hit_pʰiaŋ3' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Hit_pʰiaŋ3')"
                )
            )
            assertEquals(
                "Hit_pʰiaŋ3 组参数（Hit/打，2 条 2 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Hit_pʰiaŋ3' " +
                        "AND semantic_tag = 'Hit' AND semantic_label = '打' " +
                        "AND member_count = 2 AND dialect_count = 2"
                )
            )
            assertEquals(
                "Hit_pʰiaŋ3 成员 (lang|ipa) 集合（2 条 2 点）",
                listOf(
                    "寧鄉花明樓|pʰiã3",
                    "新長沙|pʰiæ̃3",
                ).sorted(),
                members("Hit_pʰiaŋ3").sorted()
            )

            // 2026-11-20 用户裁决第三轮：新建 Hit_tiaŋ3（打，2 条 2 点），收梗摄三四等白读 -iɛ/-eŋ 层：
            // 江永桃川 liɛ3「~铁」自 Hit_tiaŋ5 移入、鐘山赤嶺 neŋ3「~蘸」新收；l-/n- 为同点自由变体（ADR-0005 附录二十）。
            assertEquals(2, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ3'"))
            assertEquals(2, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Hit_tiaŋ3'"))
            assertEquals(
                "Hit_tiaŋ3 调类分布（上声 3 共 2 行）",
                "3:2",
                toneProfile("Hit_tiaŋ3")
            )
            assertEquals(
                "Hit_tiaŋ3 声母须为 l-/n-（同点自由变体）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ3' AND initial NOT IN ('l','n')")
            )
            assertEquals(
                "Hit_tiaŋ3 韵母须为 -iɛ/-eŋ（梗摄三四等白读）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ3' AND final NOT IN ('iɛ','eŋ')")
            )
            assertEquals(
                "Hit_tiaŋ3 两条注释均不得为空（ADR-0005 硬规则）",
                2,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_tiaŋ3' AND note IS NOT NULL AND trim(note) <> ''")
            )
            assertEquals(
                "Hit_tiaŋ3 成员不得与既有组 (lang, ipa) 重叠",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Hit_tiaŋ3' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Hit_tiaŋ3')"
                )
            )
            assertEquals(
                "Hit_tiaŋ3 组参数（Hit/打，2 条 2 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Hit_tiaŋ3' " +
                        "AND semantic_tag = 'Hit' AND semantic_label = '打' " +
                        "AND member_count = 2 AND dialect_count = 2"
                )
            )
            assertEquals(
                "Hit_tiaŋ3 成员 (lang|ipa) 集合（2 条 2 点）",
                listOf(
                    "江永桃川|liɛ3",
                    "鐘山赤嶺|neŋ3",
                ).sorted(),
                members("Hit_tiaŋ3").sorted()
            )

            // 2026-11-20 用户裁决第三轮：新建 Hit_taŋ3（打，2 条 2 点），收梗摄开口 -ie/-i 层：
            // 恭城直話 tie3「~踉跄」、江華老車 li3（注释「又pəu¹³」为又读标记，按同义项并入）；t-/l- 交替（ADR-0005 附录二十）。
            assertEquals(2, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_taŋ3'"))
            assertEquals(2, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Hit_taŋ3'"))
            assertEquals(
                "Hit_taŋ3 调类分布（上声 3 共 2 行）",
                "3:2",
                toneProfile("Hit_taŋ3")
            )
            assertEquals(
                "Hit_taŋ3 声母须为 t-/l-（同类交替）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_taŋ3' AND initial NOT IN ('t','l')")
            )
            assertEquals(
                "Hit_taŋ3 韵母须为 -ie/-i（梗摄开口白读）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_taŋ3' AND final NOT IN ('ie','i')")
            )
            assertEquals(
                "Hit_taŋ3 两条注释均不得为空（ADR-0005 硬规则）",
                2,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hit_taŋ3' AND note IS NOT NULL AND trim(note) <> ''")
            )
            assertEquals(
                "Hit_taŋ3 成员不得与既有组 (lang, ipa) 重叠",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Hit_taŋ3' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Hit_taŋ3')"
                )
            )
            assertEquals(
                "Hit_taŋ3 组参数（Hit/打，2 条 2 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Hit_taŋ3' " +
                        "AND semantic_tag = 'Hit' AND semantic_label = '打' " +
                        "AND member_count = 2 AND dialect_count = 2"
                )
            )
            assertEquals(
                "Hit_taŋ3 成员 (lang|ipa) 集合（2 条 2 点）",
                listOf(
                    "恭城直話|tie3",
                    "江華老車|li3",
                ).sorted(),
                members("Hit_taŋ3").sorted()
            )

            // 2026-11-20 用户裁决第三轮：删除误收的 Hide_ma1（唯一成员 湘陰界頭鋪 ma1「摸」，摸≠躲藏），
            // Hide 义项下音类组 8 → 7，组本身也从 cognate_groups 移除（ADR-0005 附录二十）。
            assertEquals(
                "Hide_ma1 组应已删除（cognates）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hide_ma1'")
            )
            assertEquals(
                "Hide_ma1 组应已删除（cognate_groups）",
                0,
                scalar("SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Hide_ma1'")
            )
            assertEquals("湘陰界頭鋪 ma1（id=627）应已删除", 0, scalar("SELECT COUNT(*) FROM cognates WHERE id = 627"))
            assertEquals(
                "Hide 义项下现挂 7 个音类组",
                7,
                scalar("SELECT COUNT(DISTINCT cognate_group) FROM cognates WHERE semantic_tag = 'Hide'")
            )

            // 2026-11-20 用户裁决第三轮：寧鄉大成橋 tsʰi5「~子:橡皮擦;蹭,擦」并入 Erase_dzut8（擦拭/捽），
            // 重复行 id=1744 删除、保留 id=1734，组变成 21 条 20 点（ADR-0005 附录二十）。
            assertEquals(21, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Erase_dzut8'"))
            assertEquals(20, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Erase_dzut8'"))
            assertEquals(
                "寧鄉大成橋 tsʰi5 应保留在 Erase_dzut8",
                1,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Erase_dzut8' AND lang = '寧鄉大成橋' AND ipa = 'tsʰi5'")
            )
            assertEquals(
                "合并后保留 id=1734 的注释（橡皮擦 + 蹭,擦）",
                1,
                scalar("SELECT COUNT(*) FROM cognates WHERE id = 1734 AND note LIKE '%橡皮擦%' AND note LIKE '%蹭,擦%'")
            )
            assertEquals("重复行 id=1744 应已删除", 0, scalar("SELECT COUNT(*) FROM cognates WHERE id = 1744"))
            assertEquals(
                "Erase_dzut8 组参数（Erase/擦拭/捽，21 条 20 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Erase_dzut8' " +
                        "AND semantic_tag = 'Erase' AND semantic_label = '擦拭/捽' " +
                        "AND member_count = 21 AND dialect_count = 20"
                )
            )
            // 2026-11-13 用户裁决：长沙系「腯」12 条按 Fat（肥胖）同义项新建 Fat_dut8（音类 沒韻 *-ət），
            // 与 Fat_nap8 并列；注释原样保留「膩/厭煩」，属 ADR-0005 附录登记的语义例外。
            assertEquals(12, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Fat_dut8'"))
            assertEquals(12, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Fat_dut8'"))
            assertEquals(
                "Fat_dut8 调类分布（入声 7 共 12 行）",
                "7:12",
                toneProfile("Fat_dut8")
            )
            assertEquals(
                "Fat_dut8 声母须全为送气透母 tʰ",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Fat_dut8' AND initial <> 'tʰ'")
            )
            assertEquals(
                "Fat_dut8 注释须全非空",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Fat_dut8' AND (note IS NULL OR TRIM(note) = '')")
            )
            assertEquals(
                "Fat_dut8 成员不得与既有组 (lang, ipa) 重叠",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Fat_dut8' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Fat_dut8')"
                )
            )
            assertEquals(
                "Fat_dut8 组参数（Fat/肥胖，12 条 12 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Fat_dut8' " +
                        "AND semantic_tag = 'Fat' AND semantic_label = '肥胖' " +
                        "AND member_count = 12 AND dialect_count = 12"
                )
            )
            assertEquals(
                "Fat_dut8 成员 (lang|ipa) 集合（12 条 12 点）",
                listOf(
                    "長沙|tʰɵʊ7",
                    "新長沙|tʰəɯ̽7",
                    "長沙黎圫|tʰəu7",
                    "長沙黃花|tʰei7",
                    "瀏陽官橋|tʰei7",
                    "瀏陽田坪|tʰei7",
                    "瀏陽金剛|tʰei7",
                    "瀏陽鎮頭|tʰei7",
                    "瀏陽|tʰei7",
                    "瀏陽大瑶|tʰei7",
                    "瀏陽永安|tʰei7",
                    "醴陵石亭|tʰəu7"
                ).sorted(),
                members("Fat_dut8").sorted()
            )

            // 2026-11-13 用户裁决：衡東草市 Hide 的 piaŋ5（id=76）自 Hide_paŋ5 移入带 -i- 介音的 Hide_piaŋ5，
            // 两组的成员数/点位数随之变为 15/15 与 6/6。
            assertEquals(15, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hide_paŋ5'"))
            assertEquals(15, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Hide_paŋ5'"))
            assertEquals(6, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hide_piaŋ5'"))
            assertEquals(6, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Hide_piaŋ5'"))
            assertEquals(
                "Hide_piaŋ5 须含 衡東草市 piaŋ5",
                1,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hide_piaŋ5' AND lang = '衡東草市' AND ipa = 'piaŋ5'")
            )
            assertEquals(
                "id=76 须已移入 Hide_piaŋ5",
                1,
                scalar("SELECT COUNT(*) FROM cognates WHERE id = 76 AND cognate_group = 'Hide_piaŋ5'")
            )

            // 2026-11-13 用户裁决：Cold_kəu5 义项标签由「構/冷」统一为「冷」；成员为既有 manual 条目，
            // note 允许为空（ADR-0005 不追溯存量），故本组不设注释非空断言。
            assertEquals(10, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cold_kəu5'"))
            assertEquals(10, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Cold_kəu5'"))
            assertEquals(
                "Cold_kəu5 组参数（Cold/冷，10 条 10 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Cold_kəu5' " +
                        "AND semantic_tag = 'Cold' AND semantic_label = '冷' " +
                        "AND member_count = 10 AND dialect_count = 10"
                )
            )

            // 2026-11-16 用户裁决：GetWetInTheRain 音类标签由 dʐai6 统一为 dʒai6；其后新增
            // 萍鄉 tsʰai5、蘆溪 tsʰai2 两条（蘆溪调类并入阳平，tone_cat='2'），现 69 条 68 点。
            assertEquals(69, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'GetWetInTheRain_dʒai6'"))
            assertEquals(68, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'GetWetInTheRain_dʒai6'"))
            assertEquals(
                "GetWetInTheRain_dʒai6 须含萍鄉 tsʰai5、蘆溪 tsʰai2 两条",
                2,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'GetWetInTheRain_dʒai6' " +
                        "AND ((lang = '萍鄉' AND ipa = 'tsʰai5') OR (lang = '蘆溪' AND ipa = 'tsʰai2'))"
                )
            )
            assertEquals(
                "蘆溪 tsʰai2 调类须并入阳平（tone_cat=2）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'GetWetInTheRain_dʒai6' " +
                        "AND lang = '蘆溪' AND ipa = 'tsʰai2' AND tone_cat = '2'"
                )
            )
            assertEquals(
                "旧组名 GetWetInTheRain_dʐai6 不得残留",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'GetWetInTheRain_dʐai6'")
            )

            // 2026-11-16 用户裁决：新建 Shield_ʃwin3（盾牌）12 条 11 点；其中 5 条注释为空，
            // 按库存实况断言空注条数，不设全非空断言。
            assertEquals(12, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shield_ʃwin3'"))
            assertEquals(11, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Shield_ʃwin3'"))
            assertEquals(
                "Shield_ʃwin3 空注释恰 5 条",
                5,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shield_ʃwin3' AND (note IS NULL OR TRIM(note) = '')")
            )
            assertEquals(
                "Shield_ʃwin3 成员不得与既有组 (lang, ipa) 重叠",
                0,
                scalar(
                    "SELECT COUNT(*) FROM cognates c WHERE c.cognate_group = 'Shield_ʃwin3' AND EXISTS (" +
                        "SELECT 1 FROM cognates o WHERE o.lang = c.lang AND o.ipa = c.ipa AND o.cognate_group <> 'Shield_ʃwin3')"
                )
            )
            assertEquals(
                "Shield_ʃwin3 组参数（Shield/盾牌，12 条 11 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Shield_ʃwin3' " +
                        "AND semantic_tag = 'Shield' AND semantic_label = '盾牌' " +
                        "AND member_count = 12 AND dialect_count = 11"
                )
            )

            // Rub_tsʰeu3（搓揉）：2026-11-19 由 Rub_tɕʰiau3 改名并新增上栗 tsʰei3，共 4 条 4 点；
            // 旧组名不得在 cognates／cognate_groups 残留。
            assertEquals(4, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Rub_tsʰeu3'"))
            assertEquals(4, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Rub_tsʰeu3'"))
            assertEquals(
                "Rub_tsʰeu3 须含上栗 tsʰei3",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Rub_tsʰeu3' " +
                        "AND lang = '上栗' AND ipa = 'tsʰei3'"
                )
            )
            for (oldName in listOf("Rub_tɕʰiau3")) {
                assertEquals(
                    "旧组名 $oldName 不得残留在 cognates",
                    0,
                    scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = ?", oldName)
                )
                assertEquals(
                    "旧组名 $oldName 不得残留在 cognate_groups",
                    0,
                    scalar("SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = ?", oldName)
                )
            }

            // Curse 两组：Curse_fan3 9 条 9 点；Curse_ʃwien3 8 条 8 点
            // （2026-11-19 并入瀏陽官橋/田坪/鎮頭 ɕyẽ3，原 5 点增至 8 点；2026-11-16 已增上栗 sɵn3、萍鄉 fɛ̃3。）
            assertEquals(9, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Curse_fan3'"))
            assertEquals(9, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Curse_fan3'"))
            assertEquals(8, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Curse_ʃwien3'"))
            assertEquals(8, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Curse_ʃwien3'"))
            assertEquals(
                "Curse_ʃwien3 声母须为 ʂ/s/f/ɕ（f- 属萍鄉「說」的阳入对转层次，ɕ- 为瀏陽三點新并入层次）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Curse_ʃwien3' AND initial NOT IN ('ʂ','s','f','ɕ')")
            )
            assertEquals(
                "Curse_ʃwien3 成员 (lang|ipa) 集合（8 点，NFD 原串）",
                listOf(
                    "瀏陽|ʂø̃ỹ3",
                    "瀏陽金剛|sø̃ỹ3",
                    "瀏陽大瑶|sø̃ỹ3",
                    "瀏陽官橋|ɕyẽ3",
                    "瀏陽田坪|ɕyẽ3",
                    "瀏陽鎮頭|ɕyẽ3",
                    "上栗|sɵn3",
                    "萍鄉|fɛ̃3",
                ).sorted(),
                members("Curse_ʃwien3").sorted()
            )

            // 2026-11-19 用户裁决：新建 Pile_tsut7（塞，25 条 24 点）与 Pile_tʃiuk7（塞，19 条 19 点）；
            // Pile_tsut7 含汨羅古培 tsei1/tsei5 同点两条，故 25 条 24 点。
            assertEquals(25, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Pile_tsut7'"))
            assertEquals(24, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Pile_tsut7'"))
            assertEquals(19, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Pile_tʃiuk7'"))
            assertEquals(19, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Pile_tʃiuk7'"))
            assertEquals(
                "Pile_tʃiuk7 调类抽查：新化 tɕiəu1 并入阴平 1",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Pile_tʃiuk7' " +
                        "AND lang = '新化' AND ipa = 'tɕiəu1' AND tone_cat = '1'"
                )
            )
            assertEquals(
                "Pile_tʃiuk7 调类抽查：宜賓蔡家 tsəu5 并入阳平 5",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Pile_tʃiuk7' " +
                        "AND lang = '宜賓蔡家' AND ipa = 'tsəu5' AND tone_cat = '5'"
                )
            )

            // Hungry_la8（餓）4 条 4 点；ScoopRice_xa3（撥飯）9 条 9 点；LookFor_nien2（尋找）6 条 6 点。
            assertEquals(4, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hungry_la8'"))
            assertEquals(4, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Hungry_la8'"))
            assertEquals(9, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'ScoopRice_xa3'"))
            assertEquals(9, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'ScoopRice_xa3'"))
            assertEquals(6, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'LookFor_nien2'"))
            assertEquals(6, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'LookFor_nien2'"))

            // Shout_ŋɔŋ1（喊）10 条 10 点。
            assertEquals(10, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Shout_ŋɔŋ1'"))

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
            assertEquals(5, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Loom_lɔm2'"))
            assertEquals(
                "Loom_lɔm2 只收阴平/阳平，晴隆長流去声一条为例外",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Loom_lɔm2' AND tone_cat NOT IN ('1','2','5')")
            )
            assertEquals(5, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hiccup_sut7'"))
            assertEquals(9, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Hiccup_ŋek8'"))
            assertEquals(21, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Burp_ge4'"))
            assertEquals(4, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Burp_ke5'"))
            assertEquals(34, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shout_wiak8'"))
            assertEquals(10, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shout_tɕwia5'"))
            assertEquals(10, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shout_ŋɔŋ1'"))
            assertEquals(8, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shout_bau2'"))
            assertEquals(7, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shout_ɣau2'"))
            assertEquals(5, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shout_o5'"))
            assertEquals(4, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shout_uai7'"))
            assertEquals(4, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Shout_si1'"))
            assertEquals(43, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Scald_ɦok8'"))
            assertEquals(42, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Poking_ŋen3'"))
            assertEquals(12, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Saw_ge4'"))
            assertEquals(0, scalar("SELECT COUNT(*) FROM cognates WHERE lang = '東安橫塘'"))
        } finally {
            db.close()
        }
    }

    /**
     * 萍鄉片（贛西袁州）三點載入：重刊 siongdict.db 後 info 491 點、langs 609,323 條、
     * COUNT(DISTINCT 字組) 124,577；蘆溪／萍鄉／上栗三點音典分區皆「湘贛－贛西－袁州」。
     */
    @Test
    fun pingxiangScopeLoaded() = runBlocking {
        val d = data()
        d.maintenance.prepare()
        // siongdict.db 的 info／langs 是 FTS5 虚拟表，平台 SQLite 没有 fts5 模块，
        // 必须用与生产代码相同的 requery 自带 SQLite（见 DatabaseFiles.kt）。
        val db = RequerySQLiteDatabase.openDatabase(
            ctx.getDatabasePath("siongdict.db").absolutePath,
            null,
            RequerySQLiteDatabase.OPEN_READONLY
        )
        try {
            fun scalar(sql: String, vararg args: String): Int =
                db.rawQuery(sql, args).use { c -> c.moveToFirst(); c.getInt(0) }

            assertEquals(491, scalar("SELECT COUNT(*) FROM info"))
            assertEquals(
                "蘆溪／萍鄉／上栗三點須存在且音典分區為湘贛－贛西－袁州",
                3,
                scalar(
                    "SELECT COUNT(*) FROM info WHERE 簡稱 IN ('蘆溪','萍鄉','上栗') " +
                        "AND 音典分區 = '湘贛－贛西－袁州'"
                )
            )
            assertEquals(
                "三點 info 的語言名須與簡稱對應",
                3,
                scalar(
                    "SELECT COUNT(*) FROM info WHERE (簡稱 = '蘆溪' AND 語言 = '蘆溪話') " +
                        "OR (簡稱 = '萍鄉' AND 語言 = '萍鄉城關話') " +
                        "OR (簡稱 = '上栗' AND 語言 = '上栗話')"
                )
            )
            assertEquals(609323, scalar("SELECT COUNT(*) FROM langs"))
            assertEquals(124577, scalar("SELECT COUNT(DISTINCT 字組) FROM langs"))
            assertEquals(1455, scalar("SELECT COUNT(*) FROM langs WHERE 語言 = '蘆溪'"))
            assertEquals(1729, scalar("SELECT COUNT(*) FROM langs WHERE 語言 = '萍鄉'"))
            assertEquals(1635, scalar("SELECT COUNT(*) FROM langs WHERE 語言 = '上栗'"))
        } finally {
            db.close()
        }
    }

    /**
     * 方言点画像端口（聚类分析的数据入口）：info 表 491 个方言点全部载入；
     * 分区非空；音典颜色符合 #RRGGBB / #AARRGGBB 或逗号分隔双色；長沙 实测颜色为 #148047；
     * 画像顺序按 (音典排序, 簡稱) 升序，保证画布与分区树顺序稳定。
     *
     * 491 / 長沙颜色是查 real 库（2026-11-19）确认的版本事实，上游改数据后应同步核对再改断言。
     */
    @Test
    fun dialectProfilesLoadFromInfo() = runBlocking {
        val profiles = data().lexicon.dialectProfiles()

        assertEquals("info 表 491 个方言点都应载入画像", 491, profiles.size)
        assertTrue(
            "簡稱不得为空：" + profiles.count { it.lang.isBlank() } + " 条",
            profiles.all { it.lang.isNotBlank() }
        )
        assertTrue(
            "分区不得为空：" + profiles.count { it.division.isBlank() } + " 条",
            profiles.all { it.division.isNotBlank() }
        )

        // 音典颜色有两种合法形态：#RRGGBB（或 #AARRGGBB）单色；双色用半角逗号分隔。
        val hex = "#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})"
        val single = Regex("^" + hex + "$")
        val pair = Regex("^" + hex + "(?:," + hex + ")+$")
        val badColors = profiles.filterNot { single.matches(it.colorHex) || pair.matches(it.colorHex) }
        assertTrue(
            "音典颜色格式非法：" + badColors.take(5).map { it.lang to it.colorHex },
            badColors.isEmpty()
        )

        // 長沙：查库确认音典分区「湘贛－北湘－星城」、颜色 #148047（单色）。
        val changsha = profiles.firstOrNull { it.lang == "長沙" }
        assertNotNull("画像中应含 長沙", changsha)
        assertEquals("長沙 音典分区", "湘贛－北湘－星城", changsha!!.division)
        assertTrue(
            "長沙 音典颜色应以 #148047 开头，实际 " + changsha.colorHex,
            changsha.colorHex.startsWith("#148047")
        )

        // 端口保证按 (音典排序, 簡稱) 升序：sortKey 序列非递减。
        val sortKeys = profiles.map { it.sortKey }
        assertEquals("画像应按音典排序非递减", sortKeys.sorted(), sortKeys)

        // 同一 sortKey 内按簡稱升序，顺序完全确定（否则分区树首次出现顺序会抖动）。
        val stable = profiles.zipWithNext().all { (a, b) ->
            a.sortKey < b.sortKey || (a.sortKey == b.sortKey && a.lang <= b.lang)
        }
        assertTrue("同一音典排序内应按簡稱升序", stable)
    }

    /**
     * 第四轮入库（ADR-0005 附录二十二）：新建 Devour_tai3（5 条 5 点）、Cangue_ka1（9 条 9 点）、
     * Extinguish_i5（18 条 18 点），以及桑植芙蓉橋 tæ3 跨组同音（Hit_tæ3 与 Devour_tai3 各一行）的裁定。
     * 库存 2,804 条 / 206 组；空注释 587（本轮新增 2 条例外 2802／2803）；(lang, ipa) 重复对 45。
     */
    @Test
    fun fourthRoundGroupsMatchDatabase() = runBlocking {
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

            fun members(group: String): List<String> =
                db.rawQuery(
                    "SELECT lang || '|' || ipa FROM cognates WHERE cognate_group = ?",
                    arrayOf(group)
                ).use { c ->
                    val rows = mutableListOf<String>()
                    while (c.moveToNext()) rows.add(c.getString(0))
                    rows
                }

            fun toneProfile(group: String): String =
                db.rawQuery(
                    "SELECT tone_cat || ':' || COUNT(*) FROM cognates WHERE cognate_group = ? " +
                        "GROUP BY tone_cat ORDER BY tone_cat",
                    arrayOf(group)
                ).use { c ->
                    val rows = mutableListOf<String>()
                    while (c.moveToNext()) rows.add(c.getString(0))
                    rows.joinToString(",")
                }

            assertEquals("cognates 全表 2,804 条", 2804, scalar("SELECT COUNT(*) FROM cognates"))
            assertEquals("音类组 206 个", 206, scalar("SELECT COUNT(DISTINCT cognate_group) FROM cognates"))
            assertEquals(
                "空注释 587 条（附录二十二新增 2802／2803 两条例外）",
                587,
                scalar("SELECT COUNT(*) FROM cognates WHERE note IS NULL OR TRIM(note) = ''")
            )
            assertEquals(
                "(lang, ipa) 重复对 45（桑植芙蓉橋 tæ3 跨组并存，见附录二十二 §六）",
                45,
                scalar(
                    "SELECT COUNT(*) FROM (SELECT lang, ipa FROM cognates GROUP BY lang, ipa HAVING COUNT(*) > 1)"
                )
            )

            // Devour_tai3：义项 Devour／猛吃，蟹摄 -ai/-æ，声母 t，上声 3，5 条 5 点。
            assertEquals(5, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Devour_tai3'"))
            assertEquals(5, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Devour_tai3'"))
            assertEquals("Devour_tai3 调类分布（上声 3 共 5 行）", "3:5", toneProfile("Devour_tai3"))
            assertEquals(
                "Devour_tai3 声母须全为 t",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Devour_tai3' AND initial <> 't'")
            )
            assertEquals(
                "Devour_tai3 韵母限 ai／æ",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Devour_tai3' AND final NOT IN ('ai','æ')")
            )
            assertEquals(
                "Devour_tai3 组参数（Devour/猛吃，5 条 5 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Devour_tai3' " +
                        "AND semantic_tag = 'Devour' AND semantic_label = '猛吃' " +
                        "AND member_count = 5 AND dialect_count = 5"
                )
            )
            assertEquals(
                "Devour_tai3 成员 (lang|ipa) 集合（5 条 5 点）",
                listOf("安鄉|tai3", "石門|tai3", "臨澧珠日|tai3", "津市渡口|tai3", "桑植芙蓉橋|tæ3").sorted(),
                members("Devour_tai3").sorted()
            )

            // Cangue_ka1：义项 Cangue／枷（器具），k- 系声母、a/ɑ/ɑɔ 韵、阴平 1，9 条 9 点；2802／2803 为空注例外。
            assertEquals(9, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cangue_ka1'"))
            assertEquals(9, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Cangue_ka1'"))
            assertEquals("Cangue_ka1 调类分布（阴平 1 共 9 行）", "1:9", toneProfile("Cangue_ka1"))
            assertEquals(
                "Cangue_ka1 声母须为 k 系",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cangue_ka1' AND initial NOT IN ('k','kʰ')")
            )
            assertEquals(
                "Cangue_ka1 韵母限 a／ɑ／ɑɔ",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cangue_ka1' AND final NOT IN ('a','ɑ','ɑɔ')")
            )
            assertEquals(
                "Cangue_ka1 空注释恰 2 条（2802 津市渡口、2803 桃源，附录二十二 §五）",
                2,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Cangue_ka1' AND (note IS NULL OR TRIM(note) = '')")
            )
            assertEquals(
                "Cangue_ka1 空注释的正是 2802／2803 两行",
                2,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE id IN (2802, 2803) " +
                        "AND cognate_group = 'Cangue_ka1' AND (note IS NULL OR TRIM(note) = '')"
                )
            )
            assertEquals(
                "Cangue_ka1 组参数（Cangue/枷（器具），9 条 9 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Cangue_ka1' " +
                        "AND semantic_tag = 'Cangue' AND semantic_label = '枷（器具）' " +
                        "AND member_count = 9 AND dialect_count = 9"
                )
            )
            assertEquals(
                "Cangue_ka1 成员 (lang|ipa) 集合（9 条 9 点）",
                listOf(
                    "安鄉|ka1", "常德前河|ka1", "臨澧珠日|ka1", "桑植芙蓉橋|ka1",
                    "岳陽張谷英|kɑ1", "益陽泥江口|ka1", "溧陽繆巷|kɑɔ1",
                    "津市渡口|ka1", "桃源|ka1"
                ).sorted(),
                members("Cangue_ka1").sorted()
            )

            // Extinguish_i5：义项 Extinguish／熄滅，零声母、韵母全 i、阴去 5，18 条 18 点，注释全非空。
            assertEquals(18, scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Extinguish_i5'"))
            assertEquals(18, scalar("SELECT COUNT(DISTINCT lang) FROM cognates WHERE cognate_group = 'Extinguish_i5'"))
            assertEquals("Extinguish_i5 调类分布（阴去 5 共 18 行）", "5:18", toneProfile("Extinguish_i5"))
            assertEquals(
                "Extinguish_i5 声母须全为空（零声母）",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Extinguish_i5' AND initial <> ''")
            )
            assertEquals(
                "Extinguish_i5 韵母须全为 i",
                0,
                scalar("SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Extinguish_i5' AND final <> 'i'")
            )
            assertEquals(
                "Extinguish_i5 18 条注释均不得为空",
                18,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE cognate_group = 'Extinguish_i5' " +
                        "AND note IS NOT NULL AND TRIM(note) <> ''"
                )
            )
            assertEquals(
                "Extinguish_i5 组参数（Extinguish/熄滅，18 条 18 点）",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognate_groups WHERE cognate_group = 'Extinguish_i5' " +
                        "AND semantic_tag = 'Extinguish' AND semantic_label = '熄滅' " +
                        "AND member_count = 18 AND dialect_count = 18"
                )
            )

            // 桑植芙蓉橋 tæ3 跨组同音（裁定允许，先例 汨羅長樂 loŋ3）：Hit_tæ3 与 Devour_tai3 各一行。
            assertEquals(
                "桑植芙蓉橋|tæ3 应有 2 行（Hit_tæ3 + Devour_tai3）",
                2,
                scalar("SELECT COUNT(*) FROM cognates WHERE lang = '桑植芙蓉橋' AND ipa = 'tæ3'")
            )
            assertEquals(
                "桑植芙蓉橋|tæ3 在 Hit_tæ3 恰 1 行",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE lang = '桑植芙蓉橋' AND ipa = 'tæ3' " +
                        "AND cognate_group = 'Hit_tæ3'"
                )
            )
            assertEquals(
                "桑植芙蓉橋|tæ3 在 Devour_tai3 恰 1 行",
                1,
                scalar(
                    "SELECT COUNT(*) FROM cognates WHERE lang = '桑植芙蓉橋' AND ipa = 'tæ3' " +
                        "AND cognate_group = 'Devour_tai3'"
                )
            )
        } finally {
            db.close()
        }
    }

    /**
     * 同源词成员关系端口（聚类相似度的最小输入）：全表 2,804 条、383 个方言点、206 组、140 个义项；
     * 每条 groupId 必须以 semanticTag + "_" 开头；抽查 Pile_tsut7（25 条）与 Curse_ʃwien3（8 条）；
     * 端口结果须与 cognates.db 直读的 (lang, groupId, semanticTag) 三元组完全一致。
     *
     * 2,804 / 383 / 206 / 140 与两组条数是查 real 库（2026-11-20，ADR-0005 附录十九 14 条、
     * 附录二十第三轮 3 新增/1 移动/1 删除/1 合并、附录二十二第四轮 3 组 32 条之后）确认的版本事实。
     */
    @Test
    fun cognateMembershipsMatchDatabase() = runBlocking {
        val d = data()
        d.maintenance.prepare()
        val memberships = d.cognateBook.memberships()

        assertEquals("cognates 全表 2,804 条成员", 2804, memberships.size)
        assertEquals("成员应覆盖 383 个方言点", 383, memberships.map { it.lang }.distinct().size)
        assertEquals("成员应覆盖 206 个音类组", 206, memberships.map { it.groupId }.distinct().size)
        assertEquals("成员应覆盖 140 个义项", 140, memberships.map { it.semanticTag }.distinct().size)

        assertTrue("lang 不得为空", memberships.all { it.lang.isNotBlank() })
        assertTrue("groupId 不得为空", memberships.all { it.groupId.isNotBlank() })
        assertTrue("semanticTag 不得为空", memberships.all { it.semanticTag.isNotBlank() })

        val badPrefix = memberships.filterNot { it.groupId.startsWith(it.semanticTag + "_") }
        assertTrue(
            "groupId 必须以 semanticTag+下划线 开头：" +
                badPrefix.take(5).map { Triple(it.lang, it.semanticTag, it.groupId) },
            badPrefix.isEmpty()
        )

        // 已知组抽查：Pile_tsut7 25 条（义项 Pile）、Curse_ʃwien3 8 条（义项 Curse）。
        val pile = memberships.filter { it.groupId == "Pile_tsut7" }
        assertEquals("Pile_tsut7 应有 25 条成员", 25, pile.size)
        assertTrue("Pile_tsut7 的义项都应为 Pile", pile.all { it.semanticTag == "Pile" })

        val curse = memberships.filter { it.groupId == "Curse_ʃwien3" }
        assertEquals("Curse_ʃwien3 应有 8 条成员", 8, curse.size)
        assertTrue("Curse_ʃwien3 的义项都应为 Curse", curse.all { it.semanticTag == "Curse" })

        // 与库直读比对：端口不得漏行、多行或串错字段。
        val db = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath("cognates.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            val fromDb = mutableSetOf<String>()
            db.rawQuery(
                "SELECT lang, cognate_group, semantic_tag FROM cognates WHERE lang IS NOT NULL AND TRIM(lang) <> ''",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    fromDb.add(c.getString(0) + "|" + c.getString(1) + "|" + c.getString(2))
                }
            }
            val fromPort = memberships.map { it.lang + "|" + it.groupId + "|" + it.semanticTag }.toSet()
            assertEquals("端口应与 cognates.db 直读的三元组一致", fromDb, fromPort)
        } finally {
            db.close()
        }
    }

    /**
     * 「自動」分群改為候選分區搜索（UPGMA 各 k + Louvain 多解析度 / 二分 γ）：
     * 真實库上必須給出 k >= 3、輪廓係數 > 0.10（sparse 阈值），模組度僅作對照；
     * 默認最小義項數由「輪廓係數最高 + 覆蓋率下限 50%」決定（用粗候選池挑選，正式 analyze 再二分細化）。
     *
     * Log 行供驗證員跑完儀器化後用 adb logcat -d -s ClusterTest 讀取真實數字：
     * k／輪廓係數／Q／類別規模／完整候選表／默認門槛與其分析結果。
     *
     * 注意：下方的 383／4／66／209／2／108／174 與 2026-11-20 儀器化 logcat 實測一致
     * （門槛 1 → k=6、輪廓 0.1587；默認門槛 4 → k=2、178/31、輪廓 0.1972）。若上游資料變動
     * 導致不符，先記錄實測值、回填文檔，不要為過而放寬斷言。
     */
    @Test
    fun clusterAnalysisUsesCommunityDetection() = runBlocking {
        val d = data()
        d.maintenance.prepare()
        val profiles = d.lexicon.dialectProfiles()
        val memberships = d.cognateBook.memberships()

        // 門槛 1：全部有同源詞的點（真實库 383 點）。
        val result = ClusterEngine.analyze(profiles, memberships)
        Log.i(
            "ClusterTest",
            "points=${result.points.size} k=${result.communities.k} " +
                "sil=${result.optimalSilhouette} Q=${result.modularity} " +
                "sizes=${result.communities.clusters.map { it.size }}"
        )
        Log.i(
            "ClusterTest",
            "candidates=" + result.partitions.joinToString { "k=${it.k}:sil=${it.silhouette}" }
        )
        Log.i(
            "ClusterTest",
            "minClusterSize(n=${result.points.size})=${minClusterSize(result.points.size)}"
        )

        // 默認最小義項數：輪廓係數最高 + 覆蓋率下限（至少 50% 有同源詞的點）。
        val maxMinTags = memberships.groupBy { it.lang }
            .values
            .maxOfOrNull { rows -> rows.map { it.semanticTag }.toSet().size }
            ?: 1
        val defaultMinTags = ClusterEngine.defaultMinTags(profiles, memberships, maxMinTags)
        Log.i("ClusterTest", "defaultMinTags=$defaultMinTags maxMinTags=$maxMinTags")
        assertTrue(
            "默认最小义项数应落在 1..$maxMinTags（实际 $defaultMinTags）",
            defaultMinTags in 1..maxMinTags
        )
        assertEquals("默認最小義項數應為 4（儀器化實測）", 4, defaultMinTags)
        assertEquals("最大義項數應為 66（道縣梅花）", 66, maxMinTags)

        val atDefault = ClusterEngine.analyze(profiles, memberships, minTags = defaultMinTags)
        val defaultSizes = atDefault.communities.clusters.map { it.size }
        Log.i(
            "ClusterTest",
            "atDefault points=${atDefault.points.size} k=${atDefault.communities.k} " +
                "sil=${atDefault.optimalSilhouette} Q=${atDefault.modularity} " +
                "sizes=$defaultSizes excludedNoCognates=${atDefault.excludedNoCognates} " +
                "excludedSparse=${atDefault.excludedSparse}"
        )
        assertEquals("默認門槛應參與 209 點（儀器化實測）", 209, atDefault.points.size)
        assertEquals("默認門槛下自動類數應為 2（儀器化實測，規模 178/31）", 2, atDefault.communities.k)
        assertTrue(
            "默認門槛下輪廓係數應 > 0.15（實測約 0.1972，實際 ${atDefault.optimalSilhouette}）",
            atDefault.optimalSilhouette > 0.15f
        )
        assertEquals(
            "默認門槛下 optimalSilhouette 應等於自動分區輪廓係數",
            atDefault.communities.silhouette,
            atDefault.optimalSilhouette,
            0f
        )
        assertFalse("默認門槛下不應標為稀疏", atDefault.sparse)
        assertEquals("默認門槛應排除 108 個無同源詞的點", 108, atDefault.excludedNoCognates)
        assertEquals("默認門槛應排除 174 個義項不足的點", 174, atDefault.excludedSparse)
        assertEquals(
            "參與點 + 兩類排除應等於可見方言點 491",
            atDefault.visibleDialects,
            atDefault.points.size + atDefault.excludedNoCognates + atDefault.excludedSparse
        )
        assertTrue(
            "默認門槛下每個簇都應 >= minClusterSize=${minClusterSize(atDefault.points.size)}（實際 $defaultSizes）",
            defaultSizes.all { it >= minClusterSize(atDefault.points.size) }
        )
        assertEquals("默認門槛下各簇規模之和應等於參與點數", atDefault.points.size, defaultSizes.sum())

        // 分群锚点布局：同类点应收紧到同一球面锚点附近，类内距应小于类间质心距。
        val labels = IntArray(result.points.size) { p ->
            result.communities.clusters.indexOfFirst { cluster -> p in cluster }
        }
        val grouped = groupedLayout3D(result.points, labels)
        fun dist(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>): Double {
            val dx = (a.first - b.first).toDouble()
            val dy = (a.second - b.second).toDouble()
            val dz = (a.third - b.third).toDouble()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        var intraSum = 0.0
        var intraCount = 0
        result.communities.clusters.forEach { cluster ->
            for (i in cluster.indices) {
                for (j in i + 1 until cluster.size) {
                    intraSum += dist(grouped[cluster[i]], grouped[cluster[j]])
                    intraCount++
                }
            }
        }
        val centroids = result.communities.clusters.map { cluster ->
            Triple(
                cluster.map { grouped[it].first }.average().toFloat(),
                cluster.map { grouped[it].second }.average().toFloat(),
                cluster.map { grouped[it].third }.average().toFloat()
            )
        }
        var betweenSum = 0.0
        var betweenCount = 0
        for (i in centroids.indices) {
            for (j in i + 1 until centroids.size) {
                betweenSum += dist(centroids[i], centroids[j])
                betweenCount++
            }
        }
        val meanIntra = if (intraCount > 0) intraSum / intraCount else 0.0
        val meanBetween = if (betweenCount > 0) betweenSum / betweenCount else 0.0
        Log.i(
            "ClusterTest",
            "groupedLayout3D intra=$meanIntra between=$meanBetween centroids=$centroids"
        )

        // —— 門槛 1 的不變量：候選分區 / 自動分區 / 稀疏判定 / 坐標 ——
        assertEquals("參與聚類的點應為 383（真實库全部有同源詞的點）", 383, result.points.size)
        assertTrue(
            "自動類數應 >= 3（實際 k=${result.communities.k}）",
            result.communities.k >= 3
        )
        assertEquals("optimalK 應等於自動分區類數", result.communities.k, result.optimalK)
        assertEquals(
            "optimalSilhouette 應等於自動分區輪廓係數",
            result.communities.silhouette,
            result.optimalSilhouette,
            0f
        )
        assertTrue(
            "自動分區輪廓係數應 > 0.10（實際 ${result.optimalSilhouette}）",
            result.optimalSilhouette > ClusterAnalysisResult.SPARSE_SILHOUETTE
        )
        assertFalse("真實库不應標為稀疏", result.sparse)
        assertTrue(
            "模組度僅作對照，應落在 -0.5..1（實際 Q=${result.modularity}）",
            result.modularity in -0.5f..1f
        )
        val clusterSizes = result.communities.clusters.map { it.size }
        val minCluster = minClusterSize(result.points.size)
        assertTrue(
            "自動分區每個簇都應 >= minClusterSize=$minCluster（實際 $clusterSizes）",
            clusterSizes.all { it >= minCluster }
        )
        assertTrue(
            "候選分區輪廓係數應落在 -1..1（實際 ${result.partitions.map { it.silhouette }}）",
            result.partitions.all { it.silhouette in -1f..1f }
        )
        assertEquals(
            "候選分區應按 k 升序、每個 k 至多一個候選",
            result.partitions.map { it.k }.sorted().distinct(),
            result.partitions.map { it.k }
        )
        assertTrue("候選分區應包含至少 2 類", result.partitions.any { it.k >= 2 })
        // 站點坐標：地圖頁要靠 info 表的經緯度，缺坐標會讓點在地圖上消失。
        for (p in result.points) {
            val lon = p.lon
            val lat = p.lat
            assertTrue("${p.lang} 應帶經度（實際 $lon）", lon != null && lon in 100.2..119.7)
            assertTrue("${p.lang} 應帶緯度（實際 $lat）", lat != null && lat in 21.5..33.5)
        }
        assertTrue(
            "分群锚点布局應把同類收緊：類內平均距 $meanIntra 應小於類間質心平均距 $meanBetween",
            meanIntra < meanBetween
        )
    }

    /**
     * 地圖投影單位回歸鎖（2026-11-20 修正）：經度與 Web Mercator 縱座標必須換算成同一單位（弧度）、
     * 共用同一個 scale。修正前 X 用「度」、Y 用「弧度」，兩軸相差 180/π ≈ 57.3 倍，真實 App 的圖被壓成
     * 約 1050×12.75 px 的橫帶、點幾乎全落在同一行、泰森多邊形退化成貫穿畫布的豎條。
     *
     * 這裡用真實庫的 491 個方言點 + 真實離線底圖跑 fitTransform(1074×1000, padding 12)：
     * 內容高寬比須等於兩軸弧度跨度比（誤差 < 1%）、contentHeight 必須 > 500 px、
     * 投影點不得共線、Voronoi 單元格必須全部落在 contentBounds 內。
     */
    @Test
    fun mapProjectionKeepsGeographicProportion() = runBlocking {
        val d = data()
        d.maintenance.prepare()
        val profiles = d.lexicon.dialectProfiles()
        assertEquals("方言點應為 491 個", 491, profiles.size)
        val located = profiles.filter { it.lon != null && it.lat != null }
        assertEquals("491 個方言點的經緯度應全部有效", 491, located.size)

        val shapes = MapAssets.load(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("離線底圖應有 20 個省級輪廓", 20, shapes.size)

        val sites = located.map { MapSite(it.lang, it.lon!!, it.lat!!, 0, "#8B0000") }
        val transform = fitTransform(sites, shapes, 1074f, 1000f, 12f)
        val projected = sites.map { transform.project(it.lon, it.lat) }

        // 1) 兩軸同單位：內容高寬比 = Mercator 縱跨度 / 經度弧度跨度。
        val lonSpanRad = (transform.maxLon - transform.minLon) * PI / 180.0
        val mercatorSpan = transform.maxMercatorY - transform.minMercatorY
        val expectedAspect = mercatorSpan / lonSpanRad
        val actualAspect = (transform.contentHeight / transform.contentWidth).toDouble()
        val aspectError = abs(actualAspect - expectedAspect) / expectedAspect
        Log.i(
            "ClusterTest",
            "map shapes=" + shapes.size + " contentW=" + transform.contentWidth +
                " contentH=" + transform.contentHeight + " aspect=" + actualAspect +
                " expectedAspect=" + expectedAspect + " aspectError=" + aspectError
        )
        assertTrue(
            "內容高寬比應等於兩軸弧度跨度比（實際 " + actualAspect + "，預期 " + expectedAspect + "）",
            aspectError < 0.01
        )
        assertTrue(
            "contentHeight 應 > 500 px（修正前只有約 12.75 px，實際 " + transform.contentHeight + "）",
            transform.contentHeight > 500f
        )

        // 2) 所有投影點都要落在內容框內（允許 ±0.5 px 的浮點誤差）。
        val bounds = transform.contentBounds()
        projected.forEachIndexed { index, point ->
            assertTrue(
                sites[index].lang + " 投影點 (" + point.first + ", " + point.second + ") 應落在 contentBounds " +
                    "[" + bounds.left + ", " + bounds.right + "]×[" + bounds.top + ", " + bounds.bottom + "] 內",
                point.first >= bounds.left - 0.5f && point.first <= bounds.right + 0.5f &&
                    point.second >= bounds.top - 0.5f && point.second <= bounds.bottom + 0.5f
            )
        }

        // 3) 點不得共線：y 標準差要佔內容高度的相當比例，且不同 y 值要夠多。
        val ys = projected.map { it.second.toDouble() }
        val meanY = ys.average()
        val yStdDev = sqrt(ys.sumOf { (it - meanY) * (it - meanY) } / ys.size)
        val distinctY = projected.map { it.second }.distinct().size
        Log.i(
            "ClusterTest",
            "map yStdDev=" + yStdDev + " distinctY=" + distinctY +
                " contentH=" + transform.contentHeight
        )
        assertTrue(
            "投影 y 標準差應 > contentHeight 的 10%（實際 " + yStdDev + "，內容高 " + transform.contentHeight + "）",
            yStdDev > 0.1 * transform.contentHeight
        )
        assertTrue("不同 y 值的點應 > 100 個（實際 " + distinctY + "）", distinctY > 100)

        // 4) 泰森多邊形必須裁剪在 contentBounds 內，不能像修正前那樣鋪成貫穿畫布的豎條。
        val cells = voronoiCells(projected.map { PlanePoint(it.first, it.second) }, bounds)
        assertEquals("Voronoi 單元格數量應等於站點數", 491, cells.size)
        cells.forEachIndexed { index, cell ->
            for (vertex in cell) {
                assertTrue(
                    sites[index].lang + " 的 Voronoi 頂點 (" + vertex.x + ", " + vertex.y + ") 超出 contentBounds",
                    vertex.x >= bounds.left - 0.5f && vertex.x <= bounds.right + 0.5f &&
                        vertex.y >= bounds.top - 0.5f && vertex.y <= bounds.bottom + 0.5f
                )
            }
        }
    }

}
