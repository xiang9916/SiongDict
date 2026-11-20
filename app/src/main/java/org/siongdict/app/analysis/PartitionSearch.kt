package org.siongdict.app.analysis

import kotlin.math.ceil

/**
 * 候選分區搜索（純 Kotlin，禁止引用 android.*）。
 *
 * 背景：只取 Louvain 單一解析度（γ = 1）的分區，自動類數可能不是輪廓係數最高的那個；
 * 反過來，只讓使用者手動選 k 時，UPGMA 在 k 上給出的分區也未必比社群偵測好。
 * 這裡把「UPGMA 各 k 切割」與「Louvain 多解析度分區」放在一起當候選，
 * 每個 k 只保留輪廓係數最高者，再從候選裡挑出可接受且輪廓係數最高的分區當「自動」結果。
 *
 * 全程確定性：γ 網格固定、二分固定步數、平手規則固定（輪廓係數 → 較小 k → Louvain → 較小 γ）。
 */

/** 候選分區的來源。 */
internal enum class PartitionSource { LOUVAIN, UPGMA }

/** 一個候選分區：分區本體、每點類號、來源與 Louvain 解析度（UPGMA 固定 1.0）。 */
internal data class PartitionCandidate(
    val partition: ClusterPartition,
    val labels: List<Int>,
    val source: PartitionSource,
    val resolution: Double = 1.0,
)

/** 每個叢集至少要佔全部參與點的比例。 */
internal const val MIN_CLUSTER_SHARE = 0.05

/** 叢集規模下限：即使點數很少，也不接受單點叢集。 */
internal const val MIN_CLUSTER_FLOOR = 2

/** Louvain 解析度網格（> 1 偏多小類，< 1 偏少大類）。 */
internal val GAMMA_GRID: DoubleArray =
    doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0)

internal const val GAMMA_MIN = 0.25
internal const val GAMMA_MAX = 4.0

/** 為「命中某個 k」而二分 γ 的步數；固定步數保證確定性。 */
internal const val BISECTION_STEPS = 6

/** 輪廓係數比較容差：小於它視為平手。 */
private const val SILHOUETTE_EPSILON = 1e-6

/** 叢集規模下限（依參與點數計算）。 */
internal fun minClusterSize(n: Int): Int =
    maxOf(MIN_CLUSTER_FLOOR, ceil(n * MIN_CLUSTER_SHARE).toInt())

/** 候選是否可接受：所有叢集都不小於 [minClusterSize]。 */
internal fun isAdmissible(candidate: PartitionCandidate, n: Int): Boolean {
    val minimum = minClusterSize(n)
    return candidate.partition.clusters.all { it.size >= minimum }
}

/**
 * 收集候選分區。
 *
 * @param bisect 是否為每個 k 再二分 γ 嘗試命中（[ClusterEngine.defaultMinTags] 逐門檻掃描時關掉以省時）
 * @return 按 k 升序、每個 k 至多一個候選（輪廓係數最高者）
 */
internal fun searchPartitions(
    similarity: DialectSimilarity,
    distances: Array<DoubleArray>,
    maxK: Int,
    bisect: Boolean = true,
): List<PartitionCandidate> {
    val n = distances.size
    if (n < 3 || maxK < 2) return emptyList()
    val limit = minOf(maxK, n - 1)
    if (limit < 2) return emptyList()

    val best = HashMap<Int, PartitionCandidate>()

    fun offer(labels: List<Int>, source: PartitionSource, resolution: Double) {
        val normalized = normalizeLabels(labels)
        val k = (normalized.maxOrNull() ?: -1) + 1
        if (k < 2 || k > limit) return
        val clusters = clustersFromLabels(normalized)
        val silhouette = averageSilhouette(distances, clusters)
        val candidate = PartitionCandidate(
            partition = ClusterPartition(k = k, clusters = clusters, silhouette = silhouette),
            labels = normalized,
            source = source,
            resolution = resolution,
        )
        val current = best[k]
        if (current == null || candidateBetter(candidate, current)) best[k] = candidate
    }

    // ① UPGMA 每個 k 的切割。
    for (partition in averageLinkage(distances, maxK)) {
        offer(labelsOf(partition.clusters, n), PartitionSource.UPGMA, 1.0)
    }

    // ② Louvain 解析度網格。
    for (gamma in GAMMA_GRID) {
        val result = detectCommunities(similarity, gamma)
        offer(result.labels, PartitionSource.LOUVAIN, gamma)
    }

    // ③ 對每個 k 二分 γ，補上網格沒掃到的類數（或更好的分區）。
    if (bisect) {
        for (targetK in 2..limit) {
            val found = bisectResolution(similarity, targetK) ?: continue
            offer(found.first, PartitionSource.LOUVAIN, found.second)
        }
    }

    return best.values.sortedBy { it.partition.k }
}

/**
 * 從候選裡挑「自動」分區：只在所有叢集都不小於 [minClusterSize] 的候選中，
 * 取輪廓係數最高者；平手取較小 k（更保守），再取 Louvain、較小 γ。
 *
 * 若沒有任何候選合格（例如點數太少湊不出兩個合格叢集），退回全體候選裡輪廓係數最高者，
 * 避免整張圖變成「無結果」；候選為空時返回 null。
 */
internal fun autoPartition(
    candidates: List<PartitionCandidate>,
    n: Int,
): PartitionCandidate? {
    if (candidates.isEmpty()) return null
    val admissible = candidates.filter { isAdmissible(it, n) }
    val pool = if (admissible.isEmpty()) candidates else admissible
    return pool.minWith(
        compareByDescending<PartitionCandidate> { it.partition.silhouette }
            .thenBy { it.partition.k }
            .thenBy { if (it.source == PartitionSource.LOUVAIN) 0 else 1 }
            .thenBy { it.resolution },
    )
}

/** 候選 a 是否優於 b（輪廓係數 → Louvain 優先 → 較小 γ）。 */
private fun candidateBetter(a: PartitionCandidate, b: PartitionCandidate): Boolean {
    if (a.partition.silhouette > b.partition.silhouette + SILHOUETTE_EPSILON) return true
    if (a.partition.silhouette < b.partition.silhouette - SILHOUETTE_EPSILON) return false
    val rankA = if (a.source == PartitionSource.LOUVAIN) 0 else 1
    val rankB = if (b.source == PartitionSource.LOUVAIN) 0 else 1
    if (rankA != rankB) return rankA < rankB
    return a.resolution < b.resolution - SILHOUETTE_EPSILON
}

/**
 * 固定步數二分 γ，嘗試得到恰好 [targetK] 個叢集。
 * Louvain 的類數對 γ 只是大致單調，二分不保證命中；沒命中就返回 null（由其他候選補位）。
 */
private fun bisectResolution(
    similarity: DialectSimilarity,
    targetK: Int,
): Pair<List<Int>, Double>? {
    var low = GAMMA_MIN
    var high = GAMMA_MAX
    repeat(BISECTION_STEPS) {
        val mid = (low + high) / 2.0
        val labels = normalizeLabels(detectCommunities(similarity, mid).labels)
        val k = (labels.maxOrNull() ?: -1) + 1
        if (k == targetK) return labels to mid
        if (k < targetK) low = mid else high = mid
    }
    return null
}

/** 把分區的叢集列表翻回「每點類號」，類號按叢集順序（最小成員索引升序）。 */
private fun labelsOf(clusters: List<List<Int>>, n: Int): List<Int> {
    val labels = IntArray(n)
    clusters.forEachIndexed { index, cluster -> for (point in cluster) labels[point] = index }
    return labels.toList()
}

/** 依「最小成員索引升序」重編類號，讓不同來源的同一分區得到同一組標籤。 */
private fun normalizeLabels(labels: List<Int>): List<Int> {
    if (labels.isEmpty()) return labels
    val clusters = clustersFromLabels(labels)
    val normalized = IntArray(labels.size)
    clusters.forEachIndexed { index, cluster -> for (point in cluster) normalized[point] = index }
    return normalized.toList()
}
