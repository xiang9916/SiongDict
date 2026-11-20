package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import org.siongdict.app.data.CognateMembership

class SimilarityTest {

    @Test
    fun sharedTagAndGroupIsPositiveButShrunk() {
        val similarity = computeSimilarity(
            memberships = listOf(
                membership("甲", "Press_gin4", "Press"),
                membership("乙", "Press_gin4", "Press"),
            ),
            langs = listOf("甲", "乙"),
        )

        // λ=2：单个共同义项全共享 → 1 / (1 + 2) = 1/3，距离 (1 - 1/3) / 2 = 1/3。
        assertEquals(1.0 / 3.0, similarity.scores[0][1], 1e-9)
        assertEquals(1.0 / 3.0, similarity.scores[1][0], 1e-9)
        assertEquals(1.0 / 3.0, similarity.distances[0][1], 1e-9)
        assertEquals(1.0 / 3.0, similarity.distances[1][0], 1e-9)
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
        assertEquals(1.0 / 3.0, similarity.scores[0][1], 1e-9)
        assertEquals(1.0 / 3.0, similarity.distances[0][1], 1e-9)
        assertEquals(0.0, similarity.distances[0][0], 1e-9)
        assertEquals(0.0, similarity.distances[1][1], 1e-9)
    }

    @Test
    fun moreCommonTagsMeansStrongerEvidence() {
        // 同一组两点：共享 3 个义项 → 3 / (3 + 2) = 0.6，明显高于只共享 1 个义项的 1/3。
        val threeShared = computeSimilarity(
            memberships = listOf(
                membership("甲", "Press_gin4", "Press"),
                membership("甲", "Hide_dzɔŋ2", "Hide"),
                membership("甲", "Curse_ʃwien3", "Curse"),
                membership("乙", "Press_gin4", "Press"),
                membership("乙", "Hide_dzɔŋ2", "Hide"),
                membership("乙", "Curse_ʃwien3", "Curse"),
            ),
            langs = listOf("甲", "乙"),
        )
        val oneShared = computeSimilarity(
            memberships = listOf(
                membership("甲", "Press_gin4", "Press"),
                membership("乙", "Press_gin4", "Press"),
            ),
            langs = listOf("甲", "乙"),
        )

        assertEquals(3, threeShared.commonTags[0][1])
        assertEquals(3.0 / 5.0, threeShared.scores[0][1], 1e-9)
        assertEquals(0.2, threeShared.distances[0][1], 1e-9)
        assertEquals(true, threeShared.scores[0][1] > oneShared.scores[0][1])
    }

    private fun membership(lang: String, groupId: String, semanticTag: String) =
        CognateMembership(lang, groupId, semanticTag)
}
