package org.siongdict.app.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.siongdict.app.data.CognateMembership
import org.siongdict.app.data.DialectProfile
import org.siongdict.app.fakes.FakeCognateBook
import org.siongdict.app.fakes.FakeLexicon

/**
 * [ClusterSession] 状态机测试：会话自己持有快照、代际守卫、默认门槛与上限，
 * 因此这里不需要 SearchViewModel，直接注入 fake 端口与测试调度器即可。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClusterSessionTest {

    private val dispatcher = StandardTestDispatcher()
    private val lexicon = FakeLexicon()
    private val cognates = FakeCognateBook()

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
    fun openLoadsSnapshotComputesOnceAndSlicesOnFilterChange() = runTest(dispatcher) {
        seedClusterData()
        val session = ClusterSession(lexicon, cognates, this, dispatcher)
        advanceUntilIdle()

        session.open(DialectFilters())
        advanceUntilIdle()

        val state = session.state.value
        assertTrue(state.visible)
        assertFalse(state.loading)
        assertNull(state.error)
        val result = state.result
        assertEquals(6, result?.visibleDialects ?: -1)
        // 戊 无同源词被排除；最小义项数默认取轮廓系数最大的门槛（此处为 2），
        // 因此只有 1 个义项的 己 被排除。
        assertEquals(4, result?.points?.size ?: -1)
        assertEquals(1, result?.excludedNoCognates ?: -1)
        assertEquals(1, result?.excludedSparse ?: -1)
        assertEquals(2, state.maxMinTags)
        assertEquals("首次进入的默认门槛由轮廓系数挑出", 2, state.minTags)
        // 只有组内两对相似度为正，跨群相似度 0 不成边
        assertEquals(2, state.edges.size)
        assertEquals(1, lexicon.profileLoads)
        assertEquals(1, cognates.membershipLoads)

        // 关闭再打开：快照与结果都在，同一筛选下不读库、不重算
        val resultBefore = session.state.value.result
        session.close()
        session.open(DialectFilters())
        advanceUntilIdle()
        assertSame("同一筛选下复用结果", resultBefore, session.state.value.result)
        assertEquals(1, lexicon.profileLoads)

        // 方言筛选取「湘語－甲區」：只剩甲乙两点参与，且复用内存快照不重读库
        session.open(DialectFilters(selected = setOf("湘語－甲區")))
        advanceUntilIdle()

        val filtered = session.state.value
        assertEquals(2, filtered.result?.visibleDialects ?: -1)
        assertEquals(2, filtered.result?.points?.size ?: -1)
        assertEquals("筛选变化只切片内存快照，不重读库", 1, lexicon.profileLoads)
        assertEquals(1, cognates.membershipLoads)
        assertEquals(2, filtered.maxMinTags)

        // 筛选命中不到任何分区叶子：0 个可见点，不崩
        session.open(DialectFilters(selected = setOf("不存在－分區")))
        advanceUntilIdle()

        val empty = session.state.value
        assertNull(empty.error)
        assertEquals(0, empty.result?.visibleDialects ?: -1)
        assertTrue(empty.result?.points?.isEmpty() ?: false)
        assertFalse(empty.loading)
    }

    @Test
    fun setClusterCountSwitchesPartitionWithoutRecompute() = runTest(dispatcher) {
        seedClusterData()
        val session = ClusterSession(lexicon, cognates, this, dispatcher)
        advanceUntilIdle()
        session.open(DialectFilters())
        advanceUntilIdle()

        val resultBefore = session.state.value.result
        val profileLoadsBefore = lexicon.profileLoads
        val membershipLoadsBefore = cognates.membershipLoads

        session.setClusterCount(3)
        advanceUntilIdle()

        val state = session.state.value
        assertEquals(3, state.selectedK ?: -1)
        assertEquals(3, state.partition?.k ?: -1)
        assertEquals("切换 k 只换分区，不重算", resultBefore, state.result)
        assertEquals(profileLoadsBefore, lexicon.profileLoads)
        assertEquals(membershipLoadsBefore, cognates.membershipLoads)

        // 回到自动：分区改用候选搜索选出的自动分区（optimalK = 社群数）
        session.setClusterCount(null)
        advanceUntilIdle()
        assertEquals(null, session.state.value.selectedK)
        assertEquals(
            session.state.value.result?.optimalK,
            session.state.value.partition?.k
        )
        assertEquals(
            session.state.value.result?.communities,
            session.state.value.partition
        )
    }

    @Test
    fun setMinTagsRecomputesFromSnapshotAndInvalidateClearsState() = runTest(dispatcher) {
        seedClusterData()
        val session = ClusterSession(lexicon, cognates, this, dispatcher)
        advanceUntilIdle()
        session.open(DialectFilters())
        advanceUntilIdle()
        // 默认门槛为 2（轮廓系数最大）：4 点参与，己 被排除。
        assertEquals(2, session.state.value.minTags)
        assertEquals(4, session.state.value.result?.points?.size ?: -1)

        // 调回 1：己 重新参与，5 点
        session.setMinTags(1)
        advanceUntilIdle()
        val lowered = session.state.value
        assertEquals(1, lowered.minTags)
        assertEquals(2, lowered.maxMinTags)
        assertEquals(5, lowered.result?.points?.size ?: -1)
        assertEquals(0, lowered.result?.excludedSparse ?: -1)

        // 再调到 2：己 又被排除；上限由完整快照固定，不缩水
        val profileLoadsBefore = lexicon.profileLoads
        session.setMinTags(2)
        advanceUntilIdle()
        val state = session.state.value
        assertEquals(2, state.minTags)
        assertEquals(4, state.result?.points?.size ?: -1)
        assertEquals(1, state.result?.excludedSparse ?: -1)
        assertEquals("复算复用快照，不再读库", profileLoadsBefore, lexicon.profileLoads)
        assertEquals("调高门槛后上限不变", 2, state.maxMinTags)
        assertTrue("上限必须不小于当前值", state.maxMinTags >= state.minTags)

        // 选中点：高亮类别序号来自当前分区
        session.setClusterCount(2)
        session.selectPoint("甲")
        val selected = session.state.value
        assertEquals("甲", selected.selectedLang)
        assertNotNull(selected.highlightedCluster)

        // 重置数据库：快照与聚类状态一起作废（页面关闭、结果清空）
        session.invalidate()
        assertFalse(session.state.value.visible)
        assertNull(session.state.value.result)
        assertEquals(1, session.state.value.minTags)

        // 重置后再打开必须重新读库
        session.open(DialectFilters())
        advanceUntilIdle()
        assertTrue(session.state.value.visible)
        assertEquals(profileLoadsBefore + 1, lexicon.profileLoads)
        assertEquals(4, session.state.value.result?.points?.size ?: -1)
    }

    @Test
    fun invalidateDuringSnapshotLoadDropsStaleResult() = runTest(dispatcher) {
        seedClusterData()
        cognates.onMemberships = {
            delay(500)
            listOf(CognateMembership("甲", "Press_gin4", "Press"))
        }
        val session = ClusterSession(lexicon, cognates, this, dispatcher)
        advanceUntilIdle()

        session.open(DialectFilters())
        // 快照仍在读取中：作废这一轮，慢结果不得回写
        session.invalidate()
        advanceUntilIdle()

        assertFalse(session.state.value.visible)
        assertNull(session.state.value.result)

        // 再次打开会重新读库并正常出结果
        session.open(DialectFilters())
        advanceUntilIdle()
        assertTrue(session.state.value.visible)
        // 重开后快照重新读取：6 个画像，但只有 甲 有同源词 → 1 点参与
        assertEquals(6, session.state.value.result?.visibleDialects ?: -1)
        assertEquals(1, session.state.value.result?.points?.size ?: -1)
        assertEquals(2, cognates.membershipLoads)
    }
}
