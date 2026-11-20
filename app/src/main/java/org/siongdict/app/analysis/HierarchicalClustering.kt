package org.siongdict.app.analysis

import kotlin.math.max
import kotlin.math.min

/** 距离比较与平手判定的容差；层次聚类不做浮点近似之外的随机化。 */
private const val EPSILON = 1e-12

/**
 * UPGMA（平均连接）层次聚类（纯 Kotlin，禁止引用 android.*）。
 *
 * 从「每个点一簇」开始，每次合并平均距离最近的两簇：
 * 合并距离 = 两簇所有点对的平均距离；平手时按两簇「最小成员索引」的字典序取前者，
 * 保证同一输入永远得到同一分区序列。合并到簇数 <= [maxK] 后开始记录，
 * 最终返回 k = 2 .. min([maxK], n - 1) 的分区（按 k 升序）。
 *
 * 轮数 < 3 时返回空列表：少于 3 个点无法同时满足 k >= 2 且 k <= n - 1。
 *
 * @param distances 对称距离方阵，对角为 0
 * @param maxK 最多记录的簇数，默认 10
 */
fun averageLinkage(
    distances: Array<DoubleArray>,
    maxK: Int = 10,
): List<ClusterPartition> {
    val n = distances.size
    if (n < 3 || maxK < 2) return emptyList()
    val limit = min(maxK, n - 1)

    var clusters: MutableList<MutableList<Int>> = MutableList(n) { mutableListOf(it) }
    val recorded = mutableListOf<ClusterPartition>()

    while (clusters.size > 1) {
        var bestA = -1
        var bestB = -1
        var bestDistance = Double.MAX_VALUE
        for (a in 0 until clusters.size) {
            for (b in a + 1 until clusters.size) {
                val distance = averageDistance(distances, clusters[a], clusters[b])
                val better = when {
                    bestA < 0 -> true
                    distance < bestDistance - EPSILON -> true
                    distance > bestDistance + EPSILON -> false
                    // 平手：按 (较小簇的最小索引, 较大簇的最小索引) 字典序取小者。
                    else -> clusterPairLess(clusters[a], clusters[b], clusters[bestA], clusters[bestB])
                }
                if (better) {
                    bestA = a
                    bestB = b
                    bestDistance = distance
                }
            }
        }

        val merged = (clusters[bestA] + clusters[bestB]).sorted()
        val next = mutableListOf<MutableList<Int>>()
        for ((index, cluster) in clusters.withIndex()) {
            if (index != bestA && index != bestB) next.add(cluster)
        }
        next.add(merged.toMutableList())
        // 簇按最小成员索引排序，同时保证簇内升序；分区与平手判定都依赖这一不变量。
        clusters = next.sortedBy { it.first() }.toMutableList()

        if (clusters.size in 2..limit) {
            recorded.add(
                ClusterPartition(
                    k = clusters.size,
                    clusters = clusters.map { it.toList() },
                    silhouette = averageSilhouette(distances, clusters),
                )
            )
        }
    }

    return recorded.sortedBy { it.k }
}

/**
 * 取平均轮廓系数最大的分区；平手取较小 k（更保守的簇数）；没有分区时返回 null。
 *
 * 这是 [ClusterEngine] 选择 optimalK 的唯一依据，单独抽出便于测试。
 */
fun bestPartition(partitions: List<ClusterPartition>): ClusterPartition? =
    partitions
        .sortedWith(compareByDescending<ClusterPartition> { it.silhouette }.thenBy { it.k })
        .firstOrNull()

/** 两个簇所有点对的平均距离。 */
private fun averageDistance(
    distances: Array<DoubleArray>,
    a: List<Int>,
    b: List<Int>,
): Double {
    var sum = 0.0
    for (x in a) {
        val row = distances[x]
        for (y in b) sum += row[y]
    }
    return sum / (a.size * b.size)
}

/** 平手比较：两簇均已按最小成员索引升序排列。 */
private fun clusterPairLess(
    a: List<Int>,
    b: List<Int>,
    bestA: List<Int>,
    bestB: List<Int>,
): Boolean =
    if (a.first() != bestA.first()) a.first() < bestA.first() else b.first() < bestB.first()

/**
 * 用原始距离矩阵计算分区的平均轮廓系数。
 *
 * 单点丛集的点记 s = 0；其余点 a = 到同簇其它点的平均距离，
 * b = 到其它各簇平均距离的最小值，s = (b - a) / max(a, b)，max = 0 时记 0。
 */
internal fun averageSilhouette(
    distances: Array<DoubleArray>,
    clusters: List<List<Int>>,
): Float {
    val n = distances.size
    if (clusters.size < 2) return 0f
    val label = IntArray(n)
    clusters.forEachIndexed { index, cluster -> for (point in cluster) label[point] = index }

    var total = 0.0
    for (point in 0 until n) {
        val own = clusters[label[point]]
        if (own.size <= 1) continue
        var a = 0.0
        for (other in own) {
            if (other != point) a += distances[point][other]
        }
        a /= (own.size - 1)

        var b = Double.MAX_VALUE
        clusters.forEachIndexed { index, cluster ->
            if (index == label[point]) return@forEachIndexed
            var sum = 0.0
            for (other in cluster) sum += distances[point][other]
            b = min(b, sum / cluster.size)
        }

        val denominator = max(a, b)
        if (denominator > 0.0) total += (b - a) / denominator
    }
    return (total / n).toFloat()
}
