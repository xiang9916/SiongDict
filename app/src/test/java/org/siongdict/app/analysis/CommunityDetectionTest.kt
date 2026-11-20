package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 社群偵測（确定性 Louvain）的单元测试：只用直接构造的相似度矩阵，
 * 不依赖资料库，也不依赖 [computeSimilarity]。
 */
class CommunityDetectionTest {

    private fun similarity(scores: Array<DoubleArray>): DialectSimilarity {
        val n = scores.size
        val distances = Array(n) { i ->
            DoubleArray(n) { j -> (1.0 - scores[i][j]) / 2.0 }
        }
        val commonTags = Array(n) { IntArray(n) { 1 } }
        return DialectSimilarity((0 until n).map { "点$it" }, scores, distances, commonTags)
    }

    /** 两对各自内部相似度 1、跨对 −1（无正边）。 */
    private fun twoPairs(): DialectSimilarity = similarity(
        arrayOf(
            doubleArrayOf(1.0, 1.0, -1.0, -1.0),
            doubleArrayOf(1.0, 1.0, -1.0, -1.0),
            doubleArrayOf(-1.0, -1.0, 1.0, 1.0),
            doubleArrayOf(-1.0, -1.0, 1.0, 1.0),
        )
    )

    @Test
    fun twoDisjointPairsBecomeTwoCommunities() {
        val sim = twoPairs()
        val result = detectCommunities(sim)

        assertEquals(2, result.count)
        assertEquals(2, result.labels.toSet().size)
        // 同对同群、跨对不同群（标签具体取值由重编号决定，不写死）。
        assertEquals(result.labels[0], result.labels[1])
        assertEquals(result.labels[2], result.labels[3])
        assertTrue(result.labels[0] != result.labels[2])
        // 每条边权 1、共 2 条边，完美二分 → Q = 0.5。
        assertEquals(0.5, result.modularity, 1e-9)
        // 对外暴露的 modularity() 必须与偵測结果自洽。
        assertEquals(result.modularity, modularity(sim, result.labels), 1e-9)
    }

    @Test
    fun uniformCompleteGraphCollapsesToOneCommunity() {
        val n = 4
        val sim = similarity(Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.5 } })
        val result = detectCommunities(sim)

        assertEquals(1, result.count)
        assertEquals(0.0, result.modularity, 1e-9)
    }

    @Test
    fun graphWithoutPositiveEdgesKeepsEveryPointSeparate() {
        val n = 3
        val sim = similarity(Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else -1.0 } })
        val result = detectCommunities(sim)

        assertEquals(n, result.count)
        assertEquals(n, result.labels.toSet().size)
        assertEquals(0.0, result.modularity, 1e-9)
    }

    @Test
    fun detectionIsDeterministic() {
        val sim = twoPairs()
        val first = detectCommunities(sim)
        val second = detectCommunities(sim)
        assertEquals(first.labels, second.labels)
        assertEquals(first.modularity, second.modularity, 0.0)
        assertEquals(first.count, second.count)
    }

    @Test
    fun modularityOfDegeneratePartitionsIsZero() {
        val sim = twoPairs()
        // 全归一类：Q 恒为 0。
        assertEquals(0.0, modularity(sim, listOf(0, 0, 0, 0)), 1e-9)
        // 全部分开：没有内部边，Q 为负（-Σa²）。
        assertTrue(modularity(sim, listOf(0, 1, 2, 3)) < 0.0)
    }
}
