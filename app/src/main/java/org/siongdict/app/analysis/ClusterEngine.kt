package org.siongdict.app.analysis

import org.siongdict.app.data.CognateMembership
import org.siongdict.app.data.DialectProfile
import kotlin.math.ceil

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
     * [defaultMinTags] 的覆蓋率下限：預設門檻至少要留下這麼多比例的有同源詞點。
     * 輪廓係數偏好小樣本，不設下限時真實庫會挑到只剩 10 點的門檻。
     */
    internal const val MIN_DEFAULT_COVERAGE = 0.5

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
        val prepared = prepare(profiles, memberships, minTags)
        val tagsByLang = prepared.tagsByLang
        val participating = prepared.participating
        val similarity = prepared.similarity
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
                entryCount = prepared.entriesByLang[profile.lang] ?: 0,
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
            excludedNoCognates = profiles.size - prepared.activeCount,
            excludedSparse = prepared.activeCount - participating.size,
        )
    }

    /**
     * 「最小義項數」的預設值：在 1..[maxMinTags] 內挑「自動分群」（社群偵測）輪廓係數最大的門檻，
     * 平手取較小者。只算相似度與社群偵測，不做佈局、不跑 UPGMA，比 [analyze] 便宜得多。
     *
     * 候選門檻另受**覆蓋率下限**約束：參與點數不得少於全部有同源詞點數的 [MIN_DEFAULT_COVERAGE]
     * （至少 2 點）。輪廓係數對小樣本天然偏大——2026-11-20 真實庫若不設限，最大值落在門檻 24，
     * 只剩 10 個點參與，圖上幾乎沒東西可看；加下限後落在門檻 4（209/383 點），才是有意義的預設。
     *
     * 參與點集合相同的相鄰門檻結果必然相同，直接跳過重算（同時保持「平手取小」的語意）。
     */
    fun defaultMinTags(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>,
        maxMinTags: Int,
    ): Int {
        val activeCount = prepare(profiles, memberships, 1).activeCount
        val minPoints = maxOf(2, ceil(activeCount * MIN_DEFAULT_COVERAGE).toInt())
        var best = 1
        var bestSilhouette = Double.NEGATIVE_INFINITY
        var previousLangs: List<String>? = null
        for (candidate in 1..maxMinTags.coerceAtLeast(1)) {
            val prepared = prepare(profiles, memberships, candidate)
            if (prepared.langs == previousLangs) continue
            previousLangs = prepared.langs
            // 參與點數隨門檻單調不增：跌破覆蓋率下限即可停止。
            if (prepared.langs.size < minPoints) break
            val community = detectCommunities(prepared.similarity)
            val clusters = clustersFromLabels(community.labels)
            val silhouette = averageSilhouette(prepared.similarity.distances, clusters).toDouble()
            if (silhouette > bestSilhouette) {
                bestSilhouette = silhouette
                best = candidate
            }
        }
        return best
    }

    /** 一次分析的「點集 + 相似度」中間產物；供 [analyze] 與 [defaultMinTags] 共用。 */
    private class Prepared(
        val participating: List<DialectProfile>,
        val tagsByLang: Map<String, MutableSet<String>>,
        val entriesByLang: Map<String, Int>,
        val activeCount: Int,
        val similarity: DialectSimilarity,
    ) {
        val langs: List<String> = participating.map { it.lang }
    }

    /**
     * 按 [minTags] 切出參與聚類的方言點並算好相似度矩陣。
     * active（有同源詞成員）與 participating（active 且義項數達標）都保持 [profiles] 原順序。
     */
    private fun prepare(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>,
        minTags: Int,
    ): Prepared {
        val visibleLangs = profiles.mapTo(HashSet(profiles.size * 2)) { it.lang }
        val tagsByLang = HashMap<String, MutableSet<String>>()
        val entriesByLang = HashMap<String, Int>()
        for (membership in memberships) {
            if (membership.lang !in visibleLangs) continue
            tagsByLang.getOrPut(membership.lang) { HashSet() }.add(membership.semanticTag)
            entriesByLang[membership.lang] = (entriesByLang[membership.lang] ?: 0) + 1
        }

        val activeCount = profiles.count { tagsByLang.containsKey(it.lang) }
        val participating = profiles.filter { profile ->
            val tags = tagsByLang[profile.lang] ?: return@filter false
            tags.size >= minTags
        }

        return Prepared(
            participating = participating,
            tagsByLang = tagsByLang,
            entriesByLang = entriesByLang,
            activeCount = activeCount,
            similarity = computeSimilarity(memberships, participating.map { it.lang }),
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
