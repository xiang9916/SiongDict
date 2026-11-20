package org.siongdict.app.analysis

import kotlin.math.abs

/**
 * 社群偵測（純 Kotlin，禁止引用 android.*）。
 *
 * 為何不用「UPGMA + 輪廓係數選 k」：真實 382 點資料裡 58.7% 的點對沒有共同義項，
 * 距離被鎖在 0.5；輪廓係數的 b 值獎勵「切出一個遠處小簇」，於是 k = 2（一大簇 + 一個小簇）
 * 永遠最高。改用加權圖上的模組度（Louvain）後，382 點實測得 4 類、Q ≈ 0.2841（類別規模 177/115/42/48，2026-11-20 儀器化日志）。
 *
 * 圖的定義與畫布連線一致：相似度 s > 0 的點對為邊，權重 = s；孤立點自成一類。
 * 全程確定性：節點順序 = 輸入下標，平手取最小類號，最多聚合 3 層。
 */

/** 社群偵測結果；[labels] 是每個點（下標對應相似度矩陣）的類別，按最小成員索引升序編號。 */
data class CommunityResult(
    val labels: List<Int>,
    val count: Int,
    val modularity: Double,
)

/** 增益比較容差：小於它視為平手（平手取最小類號，保證確定性）。 */
private const val GAIN_EPSILON = 1e-9

/** 最多聚合層數：再往上只會把類別合併成極少數大類，失去意義。 */
private const val MAX_LEVELS = 3

/** 單層局部移動的最大掃描輪數（防禦性上限，正常資料 2–3 輪即收斂）。 */
private const val MAX_PASSES_PER_LEVEL = 100

/**
 * 對相似度矩陣做確定性 Louvain 社群偵測。
 *
 * @param similarity 相似度矩陣（只用到 scores；正相似度為邊權）
 * @param resolution 解析度：> 1 偏向更多小類，< 1 偏向更少大類
 */
fun detectCommunities(
    similarity: DialectSimilarity,
    resolution: Double = 1.0,
): CommunityResult {
    val n = similarity.langs.size
    if (n == 0) return CommunityResult(emptyList(), 0, 0.0)
    if (n == 1) return CommunityResult(listOf(0), 1, 0.0)

    // 正相似度邊（與 ClusterMap.buildClusterEdges 同一判據）。
    var adjacency = Array(n) { HashMap<Int, Double>() }
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val weight = similarity.scores[i][j]
            if (weight > 0.0) {
                adjacency[i][j] = weight
                adjacency[j][i] = weight
            }
        }
    }
    var selfLoops = DoubleArray(n)
    // 原始節點 → 當前層節點。
    var membership = IntArray(n) { it }

    var bestLabels: IntArray? = null
    var bestModularity = -Double.MAX_VALUE
    var level = 0

    while (true) {
        val labels = localMoving(adjacency, selfLoops, resolution)
        val levelModularity = modularityOf(adjacency, selfLoops, labels, resolution)
        val mapped = IntArray(n) { index -> labels[membership[index]] }

        // 第一层无条件接受：完全均匀的图各分区的 Q 都是 0，若要求「严格提升」就永远
        // 选不上「全归一类」这个正确答案。之后只在严格提升时替换，避免来回震荡。
        if (bestLabels == null || levelModularity > bestModularity + GAIN_EPSILON) {
            bestModularity = levelModularity
            bestLabels = mapped
        } else if (level > 0) {
            break
        }

        level++
        if (level >= MAX_LEVELS) break

        val aggregated = aggregate(adjacency, selfLoops, labels) ?: break
        adjacency = aggregated.first
        selfLoops = aggregated.second
        membership = mapped
    }

    val renumbered = renumber(bestLabels ?: IntArray(n) { it })
    val count = (renumbered.maxOrNull() ?: -1) + 1
    return CommunityResult(renumbered.toList(), count, bestModularity)
}

/**
 * 給定分區計算模組度（供測試與除錯用）。
 *
 * Q = Σ_c [ Σ_in,c / 2m − resolution · (Σ_tot,c / 2m)² ]；無邊時為 0。
 */
fun modularity(
    similarity: DialectSimilarity,
    labels: List<Int>,
    resolution: Double = 1.0,
): Double {
    val n = similarity.langs.size
    if (n == 0 || labels.size != n) return 0.0
    val adjacency = Array(n) { HashMap<Int, Double>() }
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val weight = similarity.scores[i][j]
            if (weight > 0.0) {
                adjacency[i][j] = weight
                adjacency[j][i] = weight
            }
        }
    }
    return modularityOf(adjacency, DoubleArray(n), labels.toIntArray(), resolution)
}

/** 一層局部移動：逐點搬到增益最大的鄰社群，平手取最小類號。 */
private fun localMoving(
    adjacency: Array<HashMap<Int, Double>>,
    selfLoops: DoubleArray,
    resolution: Double,
): IntArray {
    val m = adjacency.size
    val degree = DoubleArray(m) { i -> adjacency[i].values.sum() + 2.0 * selfLoops[i] }
    val twoM = degree.sum()
    val labels = IntArray(m) { it }
    if (twoM <= 0.0) return labels

    val totals = degree.copyOf()
    var pass = 0
    while (pass < MAX_PASSES_PER_LEVEL) {
        var moved = false
        for (i in 0 until m) {
            val own = labels[i]
            totals[own] -= degree[i]

            // 依類號升序比較，避免 HashMap 迭代順序影響平手判定。
            val byCommunity = HashMap<Int, Double>()
            for ((j, weight) in adjacency[i]) {
                val community = labels[j]
                byCommunity[community] = (byCommunity[community] ?: 0.0) + weight
            }
            var bestCommunity = own
            var bestGain = 0.0
            for ((community, weight) in byCommunity.entries.sortedBy { it.key }) {
                val gain = weight - resolution * totals[community] * degree[i] / twoM
                val better = gain > bestGain + GAIN_EPSILON ||
                    (abs(gain - bestGain) <= GAIN_EPSILON && gain > GAIN_EPSILON && community < bestCommunity)
                if (better) {
                    bestGain = gain
                    bestCommunity = community
                }
            }
            if (bestCommunity != own) {
                labels[i] = bestCommunity
                moved = true
            }
            totals[bestCommunity] += degree[i]
        }
        if (!moved) break
        pass++
    }
    return renumber(labels)
}

/** 當前層分區的模組度。 */
private fun modularityOf(
    adjacency: Array<HashMap<Int, Double>>,
    selfLoops: DoubleArray,
    labels: IntArray,
    resolution: Double,
): Double {
    val m = adjacency.size
    val degree = DoubleArray(m) { i -> adjacency[i].values.sum() + 2.0 * selfLoops[i] }
    val twoM = degree.sum()
    if (twoM <= 0.0) return 0.0

    val totals = DoubleArray(m)
    for (i in 0 until m) totals[labels[i]] += degree[i]

    // internal[c] = 社群 c 內部的 2 × 邊權和（含自環）。
    val internal = DoubleArray(m)
    for (i in 0 until m) internal[labels[i]] += 2.0 * selfLoops[i]
    for (i in 0 until m) {
        for ((j, weight) in adjacency[i]) {
            if (labels[i] == labels[j]) internal[labels[i]] += weight
        }
    }

    var total = 0.0
    for (c in 0 until m) {
        val fraction = totals[c] / twoM
        total += internal[c] / twoM - resolution * fraction * fraction
    }
    return total
}

/**
 * 把社群聚合成新圖：每個社群一個節點（節點號 = 社群號），內部邊權收進自環。
 * 沒有真正合併（社群數不減）時返回 null，讓呼叫端停止。
 */
private fun aggregate(
    adjacency: Array<HashMap<Int, Double>>,
    selfLoops: DoubleArray,
    labels: IntArray,
): Pair<Array<HashMap<Int, Double>>, DoubleArray>? {
    val count = (labels.maxOrNull() ?: -1) + 1
    if (count >= adjacency.size) return null

    val next = Array(count) { HashMap<Int, Double>() }
    val nextLoops = DoubleArray(count)
    for (i in adjacency.indices) {
        val from = labels[i]
        nextLoops[from] += selfLoops[i]
        for ((j, weight) in adjacency[i]) {
            val to = labels[j]
            if (from == to) {
                nextLoops[from] += weight / 2.0
            } else {
                next[from][to] = (next[from][to] ?: 0.0) + weight
            }
        }
    }
    return next to nextLoops
}

/** 依「最小成員索引」重新編號為 0..k-1（升序），保證同一輸入同一編號。 */
private fun renumber(labels: IntArray): IntArray {
    val representatives = labels.indices.groupBy { labels[it] }.values
        .map { it.first() }
        .sorted()
    val mapping = HashMap<Int, Int>(representatives.size * 2)
    representatives.forEachIndexed { index, representative -> mapping[labels[representative]] = index }
    return IntArray(labels.size) { mapping.getValue(labels[it]) }
}
