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
        // 只剩甲参与：单点布局回落到画布中心，不产生分区。
        assertEquals(listOf("甲"), result.points.map { it.lang })
        val only = result.points.single()
        assertEquals(2, only.tagCount)
        assertEquals(2, only.entryCount)
        assertEquals(0.5f, only.x, 0f)
        assertEquals(0.5f, only.y, 0f)
        assertEquals(2, result.totalTags)
        assertEquals(1, result.optimalK)
        assertEquals(0f, result.optimalSilhouette, 0f)
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
            membership("丁", "Hide_dzɔŋ2", "Hide"),
            membership("丙", "Hide_dzɔŋ2", "Hide"),
        )

        val result = ClusterEngine.analyze(profiles, memberships)

        // points 保持 profiles 原顺序，而不是内部分组顺序。
        assertEquals(listOf("丁", "丙", "乙", "甲"), result.points.map { it.lang })
        assertEquals(0, result.excludedNoCognates)
        assertEquals(0, result.excludedSparse)
        assertEquals(2, result.totalTags)
        assertEquals(2, result.optimalK)
        assertEquals(1.0f, result.optimalSilhouette, 1e-4f)
        assertFalse(result.sparse)

        val first = result.points.first()
        assertEquals("湘南", first.division)
        assertEquals("#222222", first.colorHex)
        assertEquals(1, first.tagCount)
        assertEquals(1, first.entryCount)
        assertTrue(
            result.points.all { it.x.isFinite() && it.x in 0f..1f && it.y.isFinite() && it.y in 0f..1f }
        )
    }

    private fun profile(lang: String, division: String, colorHex: String) =
        DialectProfile(lang, division, colorHex, sortKey = lang)

    private fun membership(lang: String, groupId: String, semanticTag: String) =
        CognateMembership(lang, groupId, semanticTag)
}
