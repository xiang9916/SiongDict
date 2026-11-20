package org.siongdict.app.analysis

import org.siongdict.app.data.CognateMembership
import org.siongdict.app.data.DialectProfile

/**
 * 聚类分析引擎（纯 Kotlin，禁止引用 android.*）。
 *
 * 输入是数据端口给出的「可见方言点画像 + 全库同源词成员」，输出一次性包含
 * 参与点（带三维坐标与统计）、UPGMA 各 k 分区、社群偵測分区、稀疏标记与排除统计。
 *
 * 数据处理顺序：
 * 1. 按 lang 聚合成员：tagCount = distinct semanticTag，entryCount = 记录条数；
 * 2. 有成员者为 active，其中 tagCount >= [minTags] 者才参与聚类；
 * 3. 对参与点算相似度矩阵，再做三维力导向布局、UPGMA 层次聚类与 Louvain 社群偵測；
 * 4. 「自動」分群取社群偵測結果（optimalK = 社群数），模组度低于
 *    [ClusterAnalysisResult.SPARSE_MODULARITY] 时标记 sparse。
 *
 * 纯函数：不持有状态、不做 IO；同一输入永远返回同一结果。
 */
object ClusterEngine {

    /**
     * 跑一次完整分析。
     *
     * @param profiles 可见方言点画像（决定 points 的顺序与可见数）
     * @param memberships 全库同源词成员；不在 [profiles] 里的方言点会被忽略
     * @param minTags 参与聚类所需的最少义项数（默认 1）
     * @param maxK 层次聚类最多记录的簇数（透传给 [averageLinkage]，默认 10）
     */
    fun analyze(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>,
        minTags: Int = 1,
        maxK: Int = 10,
    ): ClusterAnalysisResult {
        val visibleLangs = profiles.mapTo(HashSet(profiles.size * 2)) { it.lang }
        val tagsByLang = HashMap<String, MutableSet<String>>()
        val entriesByLang = HashMap<String, Int>()
        for (membership in memberships) {
            if (membership.lang !in visibleLangs) continue
            tagsByLang.getOrPut(membership.lang) { HashSet() }.add(membership.semanticTag)
            entriesByLang[membership.lang] = (entriesByLang[membership.lang] ?: 0) + 1
        }

        // active：有同源词成员；participating：active 且义项数达标。两者都保持 profiles 原顺序。
        val activeCount = profiles.count { tagsByLang.containsKey(it.lang) }
        val participating = profiles.filter { profile ->
            val tags = tagsByLang[profile.lang] ?: return@filter false
            tags.size >= minTags
        }

        val langs = participating.map { it.lang }
        val similarity = computeSimilarity(memberships, langs)
        val layout = forceLayout3D(similarity.distances)
        val partitions = averageLinkage(similarity.distances, maxK)

        // 「自動」分群：加權圖上的模組度最大化（Louvain），而非輪廓係數選 k。
        val communityResult = detectCommunities(similarity)
        val communityClusters = clustersFromLabels(communityResult.labels)
        val communityPartition = ClusterPartition(
            k = communityClusters.size,
            clusters = communityClusters,
            silhouette = averageSilhouette(similarity.distances, communityClusters),
        )

        val totalTags = buildSet {
            for (profile in participating) {
                tagsByLang[profile.lang]?.let { addAll(it) }
            }
        }.size

        val points = participating.mapIndexed { index, profile ->
            val coordinates = layout.getOrNull(index) ?: Triple(0.5f, 0.5f, 0.5f)
            ClusterPoint(
                lang = profile.lang,
                division = profile.division,
                colorHex = profile.colorHex,
                tagCount = tagsByLang[profile.lang]?.size ?: 0,
                entryCount = entriesByLang[profile.lang] ?: 0,
                x = coordinates.first,
                y = coordinates.second,
                z = coordinates.third,
            )
        }

        return ClusterAnalysisResult(
            points = points,
            partitions = partitions,
            communities = communityPartition,
            modularity = communityResult.modularity.toFloat(),
            optimalK = communityPartition.k.coerceAtLeast(1),
            optimalSilhouette = communityPartition.silhouette,
            totalTags = totalTags,
            sparse = communityResult.modularity < ClusterAnalysisResult.SPARSE_MODULARITY,
            visibleDialects = profiles.size,
            excludedNoCognates = profiles.size - activeCount,
            excludedSparse = activeCount - participating.size,
        )
    }
}

/**
 * 把「每点一个类别号」翻成 [ClusterPartition] 的簇列表：
 * 簇内成员索引升序、簇之间按最小成员索引升序。
 */
internal fun clustersFromLabels(labels: List<Int>): List<List<Int>> =
    labels.indices
        .groupBy { labels[it] }
        .values
        .map { it.toList() }
        .sortedBy { it.first() }
