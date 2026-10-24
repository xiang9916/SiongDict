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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
                    "Press_gin6",
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
        assertEquals("Press_gin6", card.subtitle)
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
        vm.updateFilters(DialectFilters(xiangGan = false))
        vm.search()
        advanceUntilIdle()

        assertEquals(listOf("乙"), vm.uiState.value.results.flatMap { it.entries }.map { it.lang })
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
}
