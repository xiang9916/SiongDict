package org.siongdict.app.analysis

/** 画布上的一条正相似边：两端是 ClusterAnalysisResult.points 的下标。 */
data class ClusterEdge(val a: Int, val b: Int, val weight: Float)

/**
 * 从相似度矩阵挑出「每个点的前 [perPoint] 条正相似边」（无向去重，权重取较大者）。
 * 纯函数，输出顺序由插入顺序固定，方便测试与稳定绘制。
 */
internal fun buildClusterEdges(similarity: DialectSimilarity, perPoint: Int = 2): List<ClusterEdge> {
    val n = similarity.langs.size
    if (n < 2 || perPoint < 1) return emptyList()
    val weights = LinkedHashMap<Long, Float>()
    for (i in 0 until n) {
        val neighbors = (0 until n)
            .filter { j -> j != i && similarity.scores[i][j] > 0.0 }
            .sortedWith(compareByDescending<Int> { j -> similarity.scores[i][j] }.thenBy { it })
            .take(perPoint)
        for (j in neighbors) {
            val a = minOf(i, j)
            val b = maxOf(i, j)
            val key = a.toLong() * n + b
            val weight = similarity.scores[a][b].toFloat()
            val previous = weights[key]
            if (previous == null || weight > previous) weights[key] = weight
        }
    }
    return weights.map { (key, weight) -> ClusterEdge((key / n).toInt(), (key % n).toInt(), weight) }
}
