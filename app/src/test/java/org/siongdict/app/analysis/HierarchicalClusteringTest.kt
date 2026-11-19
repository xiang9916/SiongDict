package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class HierarchicalClusteringTest {

    /** 两对紧密点：0-1 与 2-3 距离 0.1，跨对距离 0.9。 */
    private val twoPairs: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.0, 0.1, 0.9, 0.9),
        doubleArrayOf(0.1, 0.0, 0.9, 0.9),
        doubleArrayOf(0.9, 0.9, 0.0, 0.1),
        doubleArrayOf(0.9, 0.9, 0.1, 0.0),
    )

    @Test
    fun mergesClosestPairsFirst() {
        val partitions = averageLinkage(twoPairs)

        assertEquals(listOf(2, 3), partitions.map { it.k })
        val byK = partitions.associateBy { it.k }
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), byK.getValue(2).clusters)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), byK.getValue(3).clusters)
    }

    @Test
    fun tiesPickLowestMemberIndices() {
        val equidistant = Array(3) { row ->
            DoubleArray(3) { column -> if (row == column) 0.0 else 0.5 }
        }

        val partitions = averageLinkage(equidistant)

        assertEquals(1, partitions.size)
        assertEquals(2, partitions[0].k)
        assertEquals(listOf(listOf(0, 1), listOf(2)), partitions[0].clusters)
    }

    @Test
    fun silhouettePrefersTwoBalancedPairs() {
        val partitions = averageLinkage(twoPairs)
        val byK = partitions.associateBy { it.k }

        // k=2：每点 a=0.1、b=0.9 → s=8/9；k=3：单点簇记 0，均值 (8/9+8/9)/4=4/9。
        assertEquals(8.0 / 9.0, byK.getValue(2).silhouette.toDouble(), 1e-4)
        assertEquals(4.0 / 9.0, byK.getValue(3).silhouette.toDouble(), 1e-4)
        assertEquals(2, bestPartition(partitions)?.k)
    }
}
