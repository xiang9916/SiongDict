package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.siongdict.app.data.CognateMembership
import org.siongdict.app.data.DialectProfile

class ClusterEngineTest {

    @Test
    fun filtersPointsWithoutEnoughTags() {
        val profiles = listOf(
            profile("甲", "湘北", "#111111"),
            profile("乙", "湘南", "#222222"),
            profile("丙", "湘西", "#333333"),
            profile("丁", "湘中", "#444444"),
        )
        val memberships = listOf(
            membership("甲", "Press_gin4", "Press"),
            membership("甲", "Hide_dzɔŋ2", "Hide"),
            membership("乙", "Press_gin4", "Press"),
            membership("丁", "Hide_dzɔŋ2", "Hide"),
        )

        val result = ClusterEngine.analyze(profiles, memberships, minTags = 2)

        assertEquals(4, result.visibleDialects)
        assertEquals(1, result.excludedNoCognates)
        assertEquals(2, result.excludedSparse)
        // 只剩甲参与：单点布局回落到立方体中心，不产生 UPGMA 分区。
        assertEquals(listOf("甲"), result.points.map { it.lang })
        val only = result.points.single()
        assertEquals(2, only.tagCount)
        assertEquals(2, only.entryCount)
        assertEquals(0.5f, only.x, 0f)
        assertEquals(0.5f, only.y, 0f)
        assertEquals(0.5f, only.z, 0f)
        assertEquals(2, result.totalTags)
        // 单点自成一群：optimalK 兜底为 1，轮廓系数无定义记 0。
        assertEquals(1, result.optimalK)
        assertEquals(1, result.communities.k)
        assertEquals(0f, result.optimalSilhouette, 0f)
        // 没有正相似边 → 模组度 0 → 判为稀疏。
        assertEquals(0f, result.modularity, 1e-6f)
        assertTrue(result.sparse)
        assertTrue(result.partitions.isEmpty())
    }

    @Test
    fun keepsProfileOrderAndSeparatesTwoBalancedPairs() {
        val profiles = listOf(
            profile("丁", "湘南", "#222222"),
            profile("丙", "湘南", "#222222"),
            profile("乙", "湘北", "#111111"),
            profile("甲", "湘北", "#111111"),
        )
        val memberships = listOf(
            membership("甲", "Press_gin4", "Press"),
            membership("甲", "Press_dzen4", "Press"),
            membership("乙", "Press_gin4", "Press"),
            membership("乙", "Press_dzen4", "Press"),
            membership("丁", "Hide_dzɔŋ2", "Hide"),
            membership("丁", "Hide_xua5", "Hide"),
            membership("丙", "Hide_dzɔŋ2", "Hide"),
            membership("丙", "Hide_xua5", "Hide"),
        )

        val result = ClusterEngine.analyze(profiles, memberships)

        // points 保持 profiles 原顺序，而不是内部分组顺序。
        assertEquals(listOf("丁", "丙", "乙", "甲"), result.points.map { it.lang })
        assertEquals(0, result.excludedNoCognates)
        assertEquals(0, result.excludedSparse)
        assertEquals(2, result.totalTags)
        // 两对内部各共享 2 个组、跨对无共同义项：社群分群应恰好分成两群。
        assertEquals(2, result.optimalK)
        assertEquals(2, result.communities.k)
        assertEquals(2, result.communities.clusters.size)
        // 每个共同义项最多 +1：对内有 1 个共同义项 → sum=1，经 λ=2 收缩 s = 1/(1+2) = 1/3，跨对 0。
        // 每条边权 1/3、共 2 条边，完美二分 → Q = 0.5。
        assertEquals(0.5f, result.modularity, 1e-4f)
        // 类内距离 d = (1−1/3)/2 = 1/3，类间 0.5 → 轮廓系数 = (0.5−1/3)/0.5 = 1/3。
        assertEquals(1f / 3f, result.optimalSilhouette, 1e-4f)
        assertFalse(result.sparse)
        // 社群分区与层次聚类的 k=2 分区应当一致（同一批两对点）。
        val hierarchical = result.partitions.firstOrNull { it.k == 2 }
        assertEquals(
            result.communities.clusters.map { it.toSet() }.toSet(),
            hierarchical?.clusters?.map { it.toSet() }?.toSet(),
        )

        val first = result.points.first()
        assertEquals("湘南", first.division)
        assertEquals("#222222", first.colorHex)
        assertEquals(1, first.tagCount)
        assertEquals(2, first.entryCount)
        assertTrue(
            result.points.all {
                it.x.isFinite() && it.x in 0f..1f &&
                    it.y.isFinite() && it.y in 0f..1f &&
                    it.z.isFinite() && it.z in 0f..1f
            }
        )
    }

    private fun profile(lang: String, division: String, colorHex: String) =
        DialectProfile(lang, division, colorHex, sortKey = lang)

    private fun membership(lang: String, groupId: String, semanticTag: String) =
        CognateMembership(lang, groupId, semanticTag)
}
