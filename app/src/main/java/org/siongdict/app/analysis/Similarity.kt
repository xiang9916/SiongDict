package org.siongdict.app.analysis

import org.siongdict.app.data.CognateMembership

/**
 * 方言点两两相似度（纯 Kotlin，禁止引用 android.*）。
 *
 * [langs] 决定矩阵下标；[scores]、[distances]、[commonTags] 都是对称方阵。
 * 相似度的语义单位是**义项**（semanticTag），不是记录条数：
 * 一个点在同一义项下可能收录多个音类组，比较时只看「是否共享至少一个组」。
 */
/** 证据量收缩常数：等价于给每一对点先放两个「中性义项」。 */
internal const val SHRINKAGE_LAMBDA = 2.0

class DialectSimilarity(
    val langs: List<String>,
    /** 相似度 s ∈ [-1,1]；对角为 1（无义项的点为 0）。 */
    val scores: Array<DoubleArray>,
    /** 距离 d = (1 - s) / 2；对角恒为 0。 */
    val distances: Array<DoubleArray>,
    /** 两点的共同义项数（无义项时对角为 0）。 */
    val commonTags: Array<IntArray>,
)

/**
 * 计算两两相似度。
 *
 * 规则（与任务说明一致）：
 * - 义项 = semanticTag。对每一对点，取两者都收录的义项集合 T；
 * - 某个共同义项下共享至少一个 cognate_group 记 +1，都收录但无共享组记 -1；
 * - 只要有一边缺该义项就不计入 T（缺义项不惩罚，避免「收录少＝相似度虚高」）；
 * - s = sum / (|T| + λ)，λ = 2：除以 |T| 会让「20 个义项全共享」与「3 个义项全共享」
 *   都等于 1，丢掉证据量；加两个中性义项的收缩项后，共同义项越多越接近 ±1
 *   （20/20 → 0.909，3/3 → 0.600，1/1 → 0.333），|T| = 0 时 s = 0；
 * - 距离 d = (1 - s) / 2，|T| = 0 时 d = 0.5。
 *
 * 复杂度 O(n² + 成员条数)，n 为 [langs] 大小（全库 491 点、2.7k 条记录，毫秒级）。
 */
fun computeSimilarity(
    memberships: List<CognateMembership>,
    langs: List<String>,
): DialectSimilarity {
    val n = langs.size
    val indexOf = HashMap<String, Int>(n * 2)
    langs.forEachIndexed { index, lang -> indexOf.putIfAbsent(lang, index) }

    // 每个点：义项 → 该义项下收录的音类组集合。
    val tagsByPoint = Array(n) { HashMap<String, MutableSet<String>>() }
    for (membership in memberships) {
        val point = indexOf[membership.lang] ?: continue
        tagsByPoint[point]
            .getOrPut(membership.semanticTag) { HashSet() }
            .add(membership.groupId)
    }

    val scores = Array(n) { DoubleArray(n) }
    val distances = Array(n) { DoubleArray(n) }
    val commonTags = Array(n) { IntArray(n) }

    for (i in 0 until n) {
        for (j in i until n) {
            val tagsI = tagsByPoint[i]
            val tagsJ = tagsByPoint[j]
            var common = 0
            var sum = 0
            if (i == j) {
                // 自身：每个义项都算共享（同一组与自身相交），相似度 1；无义项时 0。
                common = tagsI.size
                sum = common
            } else {
                for ((tag, groupsI) in tagsI) {
                    val groupsJ = tagsJ[tag] ?: continue
                    common++
                    sum += if (groupsI.any { it in groupsJ }) 1 else -1
                }
            }
            val score = if (common > 0) sum.toDouble() / (common + SHRINKAGE_LAMBDA) else 0.0
            val distance = if (common > 0) (1.0 - score) / 2.0 else 0.5
            scores[i][j] = score
            scores[j][i] = score
            commonTags[i][j] = common
            commonTags[j][i] = common
            distances[i][j] = distance
            distances[j][i] = distance
        }
        // 对角距离恒为 0（无义项时上面的公式会给出 0.5）。
        distances[i][i] = 0.0
    }

    return DialectSimilarity(
        langs = langs,
        scores = scores,
        distances = distances,
        commonTags = commonTags,
    )
}
