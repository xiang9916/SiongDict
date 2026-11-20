package org.siongdict.app.analysis

/**
 * 聚类分析的纯数据模型（纯 Kotlin，禁止引用 android.*）。
 *
 * 坐标、分区与统计量一次性算好后交给界面层；界面层不再回头做聚合，
 * 因此这里把「参与聚类的点」「各 k 的分区」「社群分区」「排除统计」全部显式列出。
 */

/** 参与聚类的一个方言点：画像 + 同源词统计 + 三维力导向布局坐标（均在 [0,1] 内）。 */
data class ClusterPoint(
    val lang: String,
    val division: String,
    val colorHex: String,
    /** 该点收录的同源词义项数（distinct semanticTag）。 */
    val tagCount: Int,
    /** 该点在 cognates 表中的记录条数（含同义项多音类组）。 */
    val entryCount: Int,
    val x: Float,
    val y: Float,
    /** 第三维：3D 力导向布局的深度轴（画布用透视投影渲染）。 */
    val z: Float,
    /** 方言点经度（info 表「經緯度」的左半）；缺失时为 null，地图页会跳过该点。 */
    val lon: Double? = null,
    /** 方言点纬度（info 表「經緯度」的右半）；缺失时为 null。 */
    val lat: Double? = null,
)

/**
 * 某个聚类数 k 下的一个分区。
 *
 * [clusters] 内每个丛集的成员索引升序，丛集之间按「最小成员索引」升序；
 * 索引对应 [ClusterAnalysisResult.points] 的下标。
 */
data class ClusterPartition(
    val k: Int,
    val clusters: List<List<Int>>,
    /** 该分区的平均轮廓系数（用原始距离矩阵计算，范围约 -1..1）。 */
    val silhouette: Float,
)

/** 聚类分析的一次完整结果。 */
data class ClusterAnalysisResult(
    /** 参与聚类的点，按输入 profiles 的原顺序。 */
    val points: List<ClusterPoint>,
    /**
     * 候选分区（UPGMA 各 k + Louvain 多解析度），按 k 升序、每个 k 只留轮廓系数最高者。
     * 供「分類數」滑桿手動選擇；滑桿只切換分區、不重算。
     */
    val partitions: List<ClusterPartition>,
    /**
     * 「自動」分群：候選裡所有叢集都不小於 minClusterSize 且輪廓係數最高的分區
     * （見 `PartitionSearch.autoPartition`）；沒有點時退化为單簇 k = 1。
     */
    val communities: ClusterPartition,
    /** 自動分區的模组度 Q（范围约 -0.5..1，僅供對照）；無正相似邊時為 0。 */
    val modularity: Float,
    /** 自动分群的类数；没有点时退化为 1。 */
    val optimalK: Int,
    /** 自动分区的平均轮廓系数（選擇分區的依據）；没有点时 0。 */
    val optimalSilhouette: Float,
    /** 参与点收录的义项并集大小（distinct semanticTag）。 */
    val totalTags: Int,
    /** 自动分区的轮廓系数低于 [SPARSE_SILHOUETTE] 时为 true，提示分群结构偏弱、结果不宜当真。 */
    val sparse: Boolean,
    /** 可见方言点数（profiles.size，含未参与聚类的点）。 */
    val visibleDialects: Int,
    /** 没有任何同源词记录、因而不参与聚类的点数。 */
    val excludedNoCognates: Int,
    /** 有同源词记录但义项数不足 minTags、因而不参与聚类的点数。 */
    val excludedSparse: Int,
) {
    companion object {
        /** 自動分区的輪廓係數低于该值视为「分群結構偏弱」。 */
        const val SPARSE_SILHOUETTE: Float = 0.10f
    }
}
