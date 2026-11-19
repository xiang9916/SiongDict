package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import org.siongdict.app.data.CognateMembership

class SimilarityTest {

    @Test
    fun sharedTagAndGroupGivesMaxSimilarity() {
        val similarity = computeSimilarity(
            memberships = listOf(
                membership("甲", "Press_gin4", "Press"),
                membership("乙", "Press_gin4", "Press"),
            ),
            langs = listOf("甲", "乙"),
        )

        assertEquals(1.0, similarity.scores[0][1], 1e-9)
        assertEquals(1.0, similarity.scores[1][0], 1e-9)
        assertEquals(0.0, similarity.distances[0][1], 1e-9)
        assertEquals(0.0, similarity.distances[1][0], 1e-9)
        assertEquals(1, similarity.commonTags[0][1])
        assertEquals(1, similarity.commonTags[1][0])
        // 对角距离恒为 0，与点数无关。
        assertEquals(0.0, similarity.distances[0][0], 1e-9)
        assertEquals(0.0, similarity.distances[1][1], 1e-9)
    }

    @Test
    fun sharedAndDisjointGroupsInTwoCommonTagsCancelOut() {
        val similarity = computeSimilarity(
            memberships = listOf(
                membership("甲", "Press_gin4", "Press"),
                membership("甲", "Hide_dzɔŋ2", "Hide"),
                membership("乙", "Press_dzen4", "Press"),
                membership("乙", "Hide_dzɔŋ2", "Hide"),
            ),
            langs = listOf("甲", "乙"),
        )

        // Press 有共同义项但无共享组（-1），Hide 共享同组（+1），两者相加为 0。
        assertEquals(2, similarity.commonTags[0][1])
        assertEquals(0.0, similarity.scores[0][1], 1e-9)
        assertEquals(0.5, similarity.distances[0][1], 1e-9)
    }

    @Test
    fun tagMissingOnOneSideIsNotPenalized() {
        val similarity = computeSimilarity(
            memberships = listOf(
                membership("甲", "Press_gin4", "Press"),
                membership("甲", "Hide_dzɔŋ2", "Hide"),
                membership("乙", "Press_gin4", "Press"),
            ),
            langs = listOf("甲", "乙"),
        )

        // 乙缺 Hide，因此 Hide 不计入共同义项；共同义项只剩共享组的 Press。
        assertEquals(1, similarity.commonTags[0][1])
        assertEquals(1.0, similarity.scores[0][1], 1e-9)
        assertEquals(0.0, similarity.distances[0][1], 1e-9)
        assertEquals(0.0, similarity.distances[0][0], 1e-9)
        assertEquals(0.0, similarity.distances[1][1], 1e-9)
    }

    private fun membership(lang: String, groupId: String, semanticTag: String) =
        CognateMembership(lang, groupId, semanticTag)
}
