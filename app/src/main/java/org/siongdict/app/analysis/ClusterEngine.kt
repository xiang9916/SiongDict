package org.siongdict.app.analysis

import org.siongdict.app.data.CognateMembership
import org.siongdict.app.data.DialectProfile
import kotlin.math.ceil

/**
 * 聚类分析引擎（纯 Kotlin，禁止引用 android.*）。
 *
 * 输入是数据端口给出的「可见方言点画像 + 全库同源词成员」，输出一次性包含
 * 参与点（带三维坐标与统计）、候选分区（供「分類數」滑桿使用）、自动分区、
 * 稀疏标记与排除统计。
 *
 * 数据处理顺序：
 * 1. 按 lang 聚合成员：tagCount = distinct semanticTag，entryCount = 记录条数；
 * 2. 有成员者为 active，其中 tagCount >= [minTags] 者才参与聚类；
 * 3. 对参与点算相似度矩阵，再做三维力导向布局；
 * 4. 候選分區 = UPGMA 各 k 切割 + Louvain 多解析度分區（[searchPartitions]）；
 * 5. 「自動」分群取候選裡「所有叢集都夠大」且輪廓係數最高者（[autoPartition]）；
 *    輪廓係數低於 [ClusterAnalysisResult.SPARSE_SILHOUETTE] 時標記 sparse。
 *
 * 纯函数：不持有状态、不做 IO；同一输入永远返回同一结果。
 *
 * 「一次分析」唯一入口是 [analyzeComputation]：它在内部复用同一份相似度矩阵，
 * 同时产出分析结果、画布连线与「最小义项数」的上限／实际值，调用方不再重算。
 */
object ClusterEngine {

    /**
     * [defaultMinTags] 的覆蓋率下限：預設門檻至少要留下這麼多比例的有同源詞點。
     * 輪廓係數偏好小樣本，不設下限時真實庫會挑到只剩 10 點的門檻。
     */
    internal const val MIN_DEFAULT_COVERAGE = 0.5

    /** 「最小義項數」滑桿上限的硬顶：再多也只是让滑桿步数失控，超过后没有区分意义。 */
    internal const val MAX_MIN_TAGS = 10

    /** 候選分區搜索使用的最大類數（與介面滑桿上限一致）。 */
    private const val DEFAULT_MAX_K = 10

    /**
     * 跑一次完整分析（只要分析结果时用）。
     *
     * @param profiles 可见方言点画像（决定 points 的顺序与可见数）
     * @param memberships 全库同源词成员；不在 [profiles] 里的方言点会被忽略
     * @param minTags 参与聚类所需的最少义项数（默认 1）
     * @param maxK 候選分區的最大類數（默认 10）
     */
    fun analyze(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>,
        minTags: Int = 1,
        maxK: Int = DEFAULT_MAX_K,
    ): ClusterAnalysisResult =
        analyzePrepared(prepare(profiles, memberships, minTags), profiles.size, maxK)

    /**
     * 「一次分析」唯一入口：分析结果 + 画布连线 + 门槛上限与实际门槛一起产出。
     *
     * [useDefaultMinTags] 为 true 时不采用 [minTags]，改由 [defaultMinTags] 在
     * 1..上限 内挑轮廓系数最大的门槛作为实际门槛。连线使用与布局、分区同一份
     * 相似度矩阵，避免调用方再算一次 O(n²)。
     */
    internal fun analyzeComputation(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>,
        minTags: Int = 1,
        useDefaultMinTags: Boolean = false,
        maxK: Int = DEFAULT_MAX_K,
    ): ClusterComputation {
        val upper = maxMinTags(profiles, memberships)
        val effective = if (useDefaultMinTags) {
            defaultMinTags(profiles, memberships, upper)
        } else {
            minTags.coerceIn(1, upper)
        }
        val prepared = prepare(profiles, memberships, effective)
        return ClusterComputation(
            result = analyzePrepared(prepared, profiles.size, maxK),
            edges = buildClusterEdges(prepared.similarity),
            maxMinTags = upper,
            minTags = effective,
        )
    }

    /**
     * 「最小義項數」的穩定上限：快照里所有有同源词成员的方言点中，distinct
     * [CognateMembership.semanticTag] 数的最大值，再夹到 1..[MAX_MIN_TAGS]。
     *
     * 与当前 [minTags] 及本次聚类结果无关：调高门槛只会让参与聚类的点变少，
     * 但这把尺子始终按完整快照量，所以上限不会缩水，滑桿范围恒包含当前值。
     */
    private fun maxMinTags(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>,
    ): Int {
        if (profiles.isEmpty()) return 1
        val aggregate = aggregate(profiles, memberships)
        val max = aggregate.tagsByLang.values.maxOfOrNull { it.size } ?: 1
        return max.coerceIn(1, MAX_MIN_TAGS)
    }

    /**
     * 「最小義項數」的預設值：在 1..[maxMinTags] 內挑「自動分群」輪廓係數最大的門檻，
     * 平手取較小者。只算相似度、候選分區與輪廓係數，不做佈局，比 [analyze] 便宜。
     *
     * 候選門檻另受**覆蓋率下限**約束：參與點數不得少於全部有同源詞點數的 [MIN_DEFAULT_COVERAGE]
     * （至少 2 點）。輪廓係數對小樣本天然偏大——2026-11-20 真實庫若不設限，最大值落在門檻 24，
     * 只剩 10 個點參與，圖上幾乎沒東西可看；加下限後落在門檻 4（209/383 點），才是有意義的預設。
     *
     * 參與點集合相同的相鄰門檻結果必然相同，直接跳過重算（同時保持「平手取小」的語意）。
     *
     * 為控制開啟頁面的等待時間，這裡只跑候選池的粗搜索（γ 網格 + UPGMA，不做每 k 的二分細化）；
     * [analyze] 之後仍會二分細化，所以實際輪廓係數只會 ≥ 這裡的估計值，挑出的門檻偶爾可能
     * 不是細化後的嚴格最大值。2026-11-20 真實庫實測兩者一致（門檻 4、輪廓 0.1972）；
     * 若改成與 [analyze] 完全相同的搜索，模擬器上開啟延遲由約 6.5 秒增至約 20.5 秒。
     */
    fun defaultMinTags(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>,
        maxMinTags: Int,
    ): Int {
        val activeCount = aggregate(profiles, memberships).activeCount
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
            val candidates = searchPartitions(
                similarity = prepared.similarity,
                distances = prepared.similarity.distances,
                maxK = DEFAULT_MAX_K,
                bisect = false,
            )
            val auto = autoPartition(candidates, prepared.langs.size) ?: continue
            val silhouette = auto.partition.silhouette.toDouble()
            if (silhouette > bestSilhouette) {
                bestSilhouette = silhouette
                best = candidate
            }
        }
        return best
    }

    /** 已切好参与点、算好相似度的中间产物 → 分析结果。 */
    private fun analyzePrepared(
        prepared: Prepared,
        visibleDialects: Int,
        maxK: Int,
    ): ClusterAnalysisResult {
        val tagsByLang = prepared.tagsByLang
        val participating = prepared.participating
        val similarity = prepared.similarity
        val layout = forceLayout3D(similarity.distances)

        // 候選分區：UPGMA 各 k + Louvain 多解析度；每個 k 只留輪廓係數最高者。
        val candidates = searchPartitions(similarity, similarity.distances, maxK)
        val auto = autoPartition(candidates, participating.size)

        val autoPartition = auto?.partition ?: ClusterPartition(
            k = 1,
            clusters = if (participating.isEmpty()) emptyList() else listOf(participating.indices.toList()),
            silhouette = 0f,
        )
        val modularity = if (auto != null) modularity(similarity, auto.labels, 1.0) else 0.0

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
                lon = profile.lon,
                lat = profile.lat,
            )
        }

        return ClusterAnalysisResult(
            points = points,
            partitions = candidates.map { it.partition },
            communities = autoPartition,
            modularity = modularity.toFloat(),
            optimalK = autoPartition.k.coerceAtLeast(1),
            optimalSilhouette = autoPartition.silhouette,
            totalTags = totalTags,
            sparse = autoPartition.silhouette < ClusterAnalysisResult.SPARSE_SILHOUETTE,
            visibleDialects = visibleDialects,
            excludedNoCognates = visibleDialects - prepared.activeCount,
            excludedSparse = prepared.activeCount - participating.size,
        )
    }

    /** 一次分析的「点集 + 相似度」中间产物；供 [analyzePrepared] 与 [defaultMinTags] 共用。 */
    private class Prepared(
        val participating: List<DialectProfile>,
        val tagsByLang: Map<String, MutableSet<String>>,
        val entriesByLang: Map<String, Int>,
        val activeCount: Int,
        val similarity: DialectSimilarity,
    ) {
        val langs: List<String> = participating.map { it.lang }
    }

    /** 按 lang 聚合成员（义项集合与条数），不依赖门槛；供 [prepare] 与门槛上限共用。 */
    private class Aggregate(
        val tagsByLang: Map<String, MutableSet<String>>,
        val entriesByLang: Map<String, Int>,
        val activeCount: Int,
    )

    private fun aggregate(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>,
    ): Aggregate {
        val visibleLangs = profiles.mapTo(HashSet(profiles.size * 2)) { it.lang }
        val tagsByLang = HashMap<String, MutableSet<String>>()
        val entriesByLang = HashMap<String, Int>()
        for (membership in memberships) {
            if (membership.lang !in visibleLangs) continue
            tagsByLang.getOrPut(membership.lang) { HashSet() }.add(membership.semanticTag)
            entriesByLang[membership.lang] = (entriesByLang[membership.lang] ?: 0) + 1
        }
        val activeCount = profiles.count { tagsByLang.containsKey(it.lang) }
        return Aggregate(tagsByLang, entriesByLang, activeCount)
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
        val aggregate = aggregate(profiles, memberships)
        val participating = profiles.filter { profile ->
            val tags = aggregate.tagsByLang[profile.lang] ?: return@filter false
            tags.size >= minTags
        }

        return Prepared(
            participating = participating,
            tagsByLang = aggregate.tagsByLang,
            entriesByLang = aggregate.entriesByLang,
            activeCount = aggregate.activeCount,
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
