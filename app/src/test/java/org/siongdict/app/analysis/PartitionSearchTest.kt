package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionSearchTest {

    /** 两对紧密点：0-1 与 2-3 距离 0.1，跨对距离 0.9。 */
    private val twoPairs: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.0, 0.1, 0.9, 0.9),
        doubleArrayOf(0.1, 0.0, 0.9, 0.9),
        doubleArrayOf(0.9, 0.9, 0.0, 0.1),
        doubleArrayOf(0.9, 0.9, 0.1, 0.0),
    )

    @Test
    fun minClusterSizeKeepsAtLeastTwoAndFivePercent() {
        assertEquals(2, minClusterSize(4))
        assertEquals(2, minClusterSize(10))
        assertEquals(5, minClusterSize(100))
        assertEquals(2, minClusterSize(1))
    }

    @Test
    fun admissibilityRejectsSingletonClusters() {
        val balanced = candidate(2, listOf(listOf(0, 1), listOf(2, 3)), silhouette = 0.5)
        val singleton = candidate(2, listOf(listOf(0), listOf(1, 2, 3)), silhouette = 0.9)

        assertTrue(isAdmissible(balanced, 4))
        assertFalse(isAdmissible(singleton, 4))
    }

    @Test
    fun searchReturnsOneCandidatePerKInAscendingOrder() {
        val similarity = similarityOf(twoPairs)

        val candidates = searchPartitions(similarity, twoPairs, maxK = 3)

        assertTrue(candidates.isNotEmpty())
        assertEquals(candidates.map { it.partition.k }, candidates.map { it.partition.k }.sorted())
        assertEquals(candidates.map { it.partition.k }.distinct(), candidates.map { it.partition.k })
        for (candidate in candidates) {
            assertEquals(4, candidate.labels.size)
            assertEquals(candidate.partition.k, (candidate.labels.maxOrNull() ?: -1) + 1)
            assertEquals(candidate.partition.silhouette, candidate.partition.silhouette)
            // 类号必须按最小成员索引升序编排。
            assertEquals(candidate.labels.distinct(), candidate.labels.distinct().sorted())
        }
    }

    @Test
    fun autoPartitionPicksHighestSilhouetteAmongAdmissible() {
        val similarity = similarityOf(twoPairs)
        val candidates = searchPartitions(similarity, twoPairs, maxK = 3)

        val auto = autoPartition(candidates, 4)

        assertEquals(2, auto?.partition?.k)
        assertEquals(8.0 / 9.0, auto!!.partition.silhouette.toDouble(), 1e-4)
        // 两对点：k=2 的轮廓系数必须不劣于 UPGMA 同一 k 的分区。
        val hierarchical = averageLinkage(twoPairs).first { it.k == 2 }
        assertTrue(auto.partition.silhouette >= hierarchical.silhouette)
    }

    @Test
    fun autoPartitionBreaksTiesTowardSmallerK() {
        // n=6：k=3 的 2/2/2 与 k=2 的 3/3 都合格，轮廓系数相同 → 取较小 k。
        val k3 = candidate(3, listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5)), silhouette = 0.5)
        val k2 = candidate(2, listOf(listOf(0, 1, 2), listOf(3, 4, 5)), silhouette = 0.5)

        val auto = autoPartition(listOf(k3, k2), 6)

        assertEquals(2, auto?.partition?.k)
    }

    @Test
    fun searchIsDeterministic() {
        val similarity = similarityOf(twoPairs)

        val first = searchPartitions(similarity, twoPairs, maxK = 3)
        val second = searchPartitions(similarity, twoPairs, maxK = 3)

        assertEquals(first.map { it.partition.k }, second.map { it.partition.k })
        assertEquals(first.map { it.labels }, second.map { it.labels })
        assertEquals(
            first.map { it.partition.silhouette },
            second.map { it.partition.silhouette },
        )
    }

    @Test
    fun tooFewPointsProduceNoCandidates() {
        val distances = arrayOf(doubleArrayOf(0.0, 0.2), doubleArrayOf(0.2, 0.0))
        val similarity = similarityOf(distances)

        assertTrue(searchPartitions(similarity, distances, maxK = 10).isEmpty())
        assertNull(autoPartition(emptyList(), 2))
    }

    private fun similarityOf(distances: Array<DoubleArray>): DialectSimilarity {
        val n = distances.size
        val scores = Array(n) { i ->
            DoubleArray(n) { j -> if (i == j) 1.0 else 1.0 - 2.0 * distances[i][j] }
        }
        return DialectSimilarity(
            langs = (0 until n).map { "点$it" },
            scores = scores,
            distances = distances,
            commonTags = Array(n) { IntArray(n) },
        )
    }

    private fun candidate(
        k: Int,
        clusters: List<List<Int>>,
        silhouette: Double,
    ): PartitionCandidate {
        val labels = IntArray((clusters.maxOf { it.max() } + 1))
        clusters.forEachIndexed { index, cluster -> for (point in cluster) labels[point] = index }
        return PartitionCandidate(
            partition = ClusterPartition(k = k, clusters = clusters, silhouette = silhouette.toFloat()),
            labels = labels.toList(),
            source = PartitionSource.UPGMA,
        )
    }
}
