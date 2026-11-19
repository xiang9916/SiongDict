package org.siongdict.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.siongdict.app.data.CognateMembership
import org.siongdict.app.data.DialectProfile
import org.siongdict.app.data.SearchMode
import org.siongdict.app.fakes.FakeCognateBook
import org.siongdict.app.fakes.FakeLexicon
import org.siongdict.app.fakes.FakeMaintenance
import org.siongdict.app.fakes.cognateEntry
import org.siongdict.app.fakes.cognateGroup
import org.siongdict.app.fakes.result

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val maintenance = FakeMaintenance()
    private val lexicon = FakeLexicon()
    private val cognates = FakeCognateBook()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SearchViewModel(maintenance, lexicon, cognates, "test", dispatcher)

    /**
     * 聚类测试资料：甲乙共享 Press/Hide 两个义项且同音类组，丙丁共享 Cut/Bend，
     * 戊无同源词（不算 active），己只有 1 个义项（用于验证 minTags 门槛）。
     */
    private fun seedClusterData() {
        lexicon.onDialectProfiles = {
            listOf(
                DialectProfile("甲", "湘語－甲區", "#1E88E5", "01"),
                DialectProfile("乙", "湘語－甲區", "#E53935", "02"),
                DialectProfile("丙", "湘語－乙區", "#43A047", "03"),
                DialectProfile("丁", "湘語－乙區", "#FDD835", "04"),
                DialectProfile("戊", "湘語－丙區", "#8E24AA", "05"),
                DialectProfile("己", "湘語－丙區", "#FB8C00", "06")
            )
        }
        cognates.onMemberships = {
            listOf(
                CognateMembership("甲", "Press_gin4", "Press"),
                CognateMembership("甲", "Hide_dzɔŋ2", "Hide"),
                CognateMembership("乙", "Press_gin4", "Press"),
                CognateMembership("乙", "Hide_dzɔŋ2", "Hide"),
                CognateMembership("丙", "Cut_sa1", "Cut"),
                CognateMembership("丙", "Bend_van1", "Bend"),
                CognateMembership("丁", "Cut_sa1", "Cut"),
                CognateMembership("丁", "Bend_van1", "Bend"),
                CognateMembership("己", "Sit_tu6", "Sit")
            )
        }
    }

    @Test
    fun charSearchBuildsOneCardPerVariantSortedByDialectCount() = runTest(dispatcher) {
        lexicon.onVariantsOf = { ch -> if (ch == "湘") listOf("湘", "湘2") else listOf(ch) }
        lexicon.onSearchByChar = { hz ->
            when (hz) {
                "湘" -> listOf(result("湘", "甲", "a1"), result("湘", "乙", "b1"))
                "湘2" -> listOf(result("湘2", "甲", "c1"))
                else -> emptyList()
            }
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateQuery("湘")
        vm.search()
        advanceUntilIdle()

        val results = vm.uiState.value.results
        assertEquals(2, results.size)
        assertEquals("湘", results[0].chars)
        assertEquals(2, results[0].entries.size)
        assertEquals("湘2", results[1].chars)
    }

    @Test
    fun meaningSearchGroupsByCharGroup() = runTest(dispatcher) {
        lexicon.onSearchByMeaning = {
            listOf(result("字A", "甲", "1"), result("字A", "乙", "2"), result("字B", "甲", "3"))
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateQuery("彎腰")
        vm.updateMode(SearchMode.MEANING)
        vm.search()
        advanceUntilIdle()

        val results = vm.uiState.value.results
        assertEquals(listOf("字A", "字B"), results.map { it.chars })
        assertEquals(2, results[0].entries.size)
    }

    @Test
    fun cognateSearchUsesLabelAsTitleAndGroupIdAsSubtitle() = runTest(dispatcher) {
        cognates.onSearch = {
            listOf(
                cognateGroup(
                    "Press_gin4",
                    "按",
                    cognateEntry("乙", "gin6", sortKey = "02"),
                    cognateEntry("甲", "gin6", sortKey = "01")
                )
            )
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateQuery("按")
        vm.updateMode(SearchMode.COGNATE)
        vm.search()
        advanceUntilIdle()

        val card = vm.uiState.value.results.single()
        assertEquals("按", card.chars)
        assertEquals("Press_gin4", card.subtitle)
        assertTrue(card.charsIsLabel)
        assertEquals(listOf("甲", "乙"), card.entries.map { it.lang })
    }

    @Test
    fun dialectFiltersHideMatchingDivision() = runTest(dispatcher) {
        lexicon.onSearchByChar = { hz -> listOf(result(hz, "甲", "a1"), result(hz, "乙", "b1")) }
        lexicon.onDialectDivision = { lang ->
            when (lang) {
                "甲" -> "湘贛－岳州"
                "乙" -> "湘南"
                else -> ""
            }
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateQuery("湘")
        vm.updateFilters(DialectFilters(selected = setOf("湘南")))
        vm.search()
        advanceUntilIdle()

        assertEquals(listOf("乙"), vm.uiState.value.results.flatMap { it.entries }.map { it.lang })
    }

    @Test
    fun filterChangeRerunsSearchAndLoadsDialectTree() = runTest(dispatcher) {
        lexicon.onSearchByChar = { hz -> listOf(result(hz, "甲", "a1"), result(hz, "乙", "b1")) }
        lexicon.onDialectDivision = { lang -> if (lang == "甲") "湘贛－岳州" else "湘南" }
        lexicon.onDialectDivisions = { linkedMapOf("甲" to "湘贛－岳州", "乙" to "湘南") }
        val vm = viewModel()
        advanceUntilIdle()

        // 数据层就绪后异步载入分区树
        val tree = vm.uiState.value.dialectTree
        assertEquals(listOf("湘贛", "湘南"), tree.map { it.name })
        assertEquals(1, tree.first().children.single().pointCount)
        assertEquals(1, tree[1].pointCount)
        assertEquals(1, lexicon.divisionLoads)

        vm.updateQuery("湘")
        vm.search()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.results.single().entries.size)
        val callsBefore = lexicon.charQueries.count { it == "湘" }

        vm.updateFilters(DialectFilters(selected = setOf("湘南")))
        advanceUntilIdle()

        assertEquals(
            "改筛选后应只保留湘南",
            listOf("乙"),
            vm.uiState.value.results.flatMap { it.entries }.map { it.lang }
        )
        assertTrue(
            "改筛选后应自动重跑检索",
            lexicon.charQueries.count { it == "湘" } > callsBefore
        )
    }

    @Test
    fun rapidSearchesKeepOnlyLatestResults() = runTest(dispatcher) {
        lexicon.onSearchByChar = { hz ->
            if (hz == "湘") delay(100)
            listOf(result(hz, "甲", "1"))
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateQuery("湘")
        vm.search()
        vm.updateQuery("行")
        vm.search()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("最后一次检索结果应含「行」", state.results.any { it.chars == "行" })
        assertFalse("旧检索结果不得回写", state.results.any { it.chars == "湘" })
    }

    @Test
    fun resetDuringSearchClearsAndDiscardsStaleResults() = runTest(dispatcher) {
        lexicon.onSearchByChar = { delay(100); listOf(result(it, "甲", "1")) }
        maintenance.resetDelayMs = 50
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateQuery("湘")
        vm.search()
        vm.resetDatabases()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertFalse(state.searched)
        assertTrue(state.results.isEmpty())
        assertEquals(1, maintenance.resetCount)

        lexicon.onSearchByChar = { listOf(result(it, "甲", "1")) }
        vm.updateQuery("湘")
        vm.search()
        advanceUntilIdle()
        assertTrue("重置后应能继续检索", vm.uiState.value.results.isNotEmpty())
    }

    @Test
    fun searchFailureSurfacesErrorState() = runTest(dispatcher) {
        lexicon.onSearchByChar = { throw IllegalStateException("查询失败") }
        val vm = viewModel()
        advanceUntilIdle()

        vm.updateQuery("湘")
        vm.search()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.results.isEmpty())
        assertEquals("查询失败", state.error)
    }

    @Test
    fun outdatedDatabaseResetsOnInit() = runTest(dispatcher) {
        maintenance.outdated = true
        maintenance.resetDelayMs = 10
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(1, maintenance.resetCount)
        assertEquals(0, maintenance.prepareCount)
        assertFalse(vm.uiState.value.dbOutdated)
    }

    @Test
    fun upToDateDatabasePreparesOnInit() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(1, maintenance.prepareCount)
        assertEquals(0, maintenance.resetCount)
        assertEquals("test", vm.uiState.value.appVersion)
    }

    // ─── 聚类分析（研究入口） ───

    @Test
    fun openClusterAnalysisLoadsSnapshotAndComputesResult() = runTest(dispatcher) {
        seedClusterData()
        val vm = viewModel()
        advanceUntilIdle()

        vm.openClusterAnalysis()
        advanceUntilIdle()

        val state = vm.clusterState.value
        assertTrue(state.visible)
        assertFalse(state.loading)
        assertNull(state.error)
        val result = state.result
        assertEquals(6, result?.visibleDialects ?: -1)
        // 戊 无同源词被排除；己 只有 1 个义项，在 minTags=1 时仍参与
        assertEquals(5, result?.points?.size ?: -1)
        assertEquals(1, result?.excludedNoCognates ?: -1)
        assertEquals(0, result?.excludedSparse ?: -1)
        assertEquals(2, state.maxMinTags)
        // 只有组内两对相似度为正，跨群相似度 0 不成边
        assertEquals(2, state.edges.size)
        assertEquals(1, lexicon.profileLoads)
        assertEquals(1, cognates.membershipLoads)

        // 方言筛选取「湘語－甲區」：只剩甲乙两点参与，且复用内存快照不重读库
        vm.updateFilters(DialectFilters(selected = setOf("湘語－甲區")))
        vm.closeClusterAnalysis()
        vm.openClusterAnalysis()
        advanceUntilIdle()

        val filtered = vm.clusterState.value
        assertEquals(2, filtered.result?.visibleDialects ?: -1)
        assertEquals(2, filtered.result?.points?.size ?: -1)
        assertEquals("筛选变化只切片内存快照，不重读库", 1, lexicon.profileLoads)
        assertEquals(1, cognates.membershipLoads)
        assertEquals(2, filtered.maxMinTags)

        // 筛选命中不到任何分区叶子：0 个可见点，不崩
        vm.updateFilters(DialectFilters(selected = setOf("不存在－分區")))
        vm.closeClusterAnalysis()
        vm.openClusterAnalysis()
        advanceUntilIdle()

        val empty = vm.clusterState.value
        assertNull(empty.error)
        assertEquals(0, empty.result?.visibleDialects ?: -1)
        assertTrue(empty.result?.points?.isEmpty() ?: false)
        assertFalse(empty.loading)
    }

    @Test
    fun setClusterCountSwitchesPartitionWithoutRecompute() = runTest(dispatcher) {
        seedClusterData()
        val vm = viewModel()
        advanceUntilIdle()
        vm.openClusterAnalysis()
        advanceUntilIdle()

        val resultBefore = vm.clusterState.value.result
        val profileLoadsBefore = lexicon.profileLoads
        val membershipLoadsBefore = cognates.membershipLoads

        vm.setClusterCount(3)
        advanceUntilIdle()

        val state = vm.clusterState.value
        assertEquals(3, state.selectedK ?: -1)
        assertEquals(3, state.partition?.k ?: -1)
        assertEquals("切换 k 只换分区，不重算", resultBefore, state.result)
        assertEquals(profileLoadsBefore, lexicon.profileLoads)
        assertEquals(membershipLoadsBefore, cognates.membershipLoads)

        // 回到自动：分区改用 optimalK
        vm.setClusterCount(null)
        advanceUntilIdle()
        assertEquals(null, vm.clusterState.value.selectedK)
        assertEquals(
            vm.clusterState.value.result?.optimalK,
            vm.clusterState.value.partition?.k
        )
    }

    @Test
    fun setMinTagsRecomputesFromSnapshotAndResetClearsClusterState() = runTest(dispatcher) {
        seedClusterData()
        val vm = viewModel()
        advanceUntilIdle()
        vm.openClusterAnalysis()
        advanceUntilIdle()
        assertEquals(5, vm.clusterState.value.result?.points?.size ?: -1)

        val profileLoadsBefore = lexicon.profileLoads
        vm.setMinTags(2)
        advanceUntilIdle()

        val state = vm.clusterState.value
        assertEquals(2, state.minTags)
        // 己 的义项数（1）低于门槛，被排除
        assertEquals(4, state.result?.points?.size ?: -1)
        assertEquals(1, state.result?.excludedSparse ?: -1)
        assertEquals("复算复用快照，不再读库", profileLoadsBefore, lexicon.profileLoads)

        // 上限由完整快照固定：调高门槛后不缩水，滑桿范围恒包含当前值
        assertEquals("调高门槛后上限不变", 2, state.maxMinTags)
        assertTrue("上限必须不小于当前值", state.maxMinTags >= state.minTags)

        // 还能再调回 1（上限不变，被排除的点重新参与）
        vm.setMinTags(1)
        advanceUntilIdle()
        val lowered = vm.clusterState.value
        assertEquals(1, lowered.minTags)
        assertEquals(2, lowered.maxMinTags)
        assertEquals(5, lowered.result?.points?.size ?: -1)
        assertEquals(0, lowered.result?.excludedSparse ?: -1)

        // 重置数据库：快照与聚类状态一起作废（对话关闭）
        vm.resetDatabases()
        advanceUntilIdle()
        assertFalse(vm.clusterState.value.visible)
        assertNull(vm.clusterState.value.result)

        // 重置后再打开必须重新读库
        vm.openClusterAnalysis()
        advanceUntilIdle()
        assertTrue(vm.clusterState.value.visible)
        assertEquals(profileLoadsBefore + 1, lexicon.profileLoads)
    }
}
