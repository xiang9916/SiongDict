package org.siongdict.app.analysis

/**
 * 聚类分析的纯数据模型（纯 Kotlin，禁止引用 android.*）。
 *
 * 坐标、分区与统计量一次性算好后交给界面层；界面层不再回头做聚合，
 * 因此这里把「参与聚类的点」「各 k 的分区」「最优 k」「排除统计」全部显式列出。
 */

/** 参与聚类的一个方言点：画像 + 同源词统计 + 力导向布局坐标（均在 [0,1] 内）。 */
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
    /** 各 k 的分区，按 k 升序；k 从 2 到 min(maxK, 点数 - 1)。 */
    val partitions: List<ClusterPartition>,
    /** 轮廓系数最大的 k；没有分区时为 1。 */
    val optimalK: Int,
    /** 最优分区对应的平均轮廓系数；没有分区时为 0。 */
    val optimalSilhouette: Float,
    /** 参与点收录的义项并集大小（distinct semanticTag）。 */
    val totalTags: Int,
    /** 最优轮廓系数低于 [SPARSE_SILHOUETTE] 时为 true，提示结果不宜当真。 */
    val sparse: Boolean,
    /** 可见方言点数（profiles.size，含未参与聚类的点）。 */
    val visibleDialects: Int,
    /** 没有任何同源词记录、因而不参与聚类的点数。 */
    val excludedNoCognates: Int,
    /** 有同源词记录但义项数不足 minTags、因而不参与聚类的点数。 */
    val excludedSparse: Int,
) {
    companion object {
        /** 轮廓系数低于该值视为「数据稀疏，聚类不可信」。 */
        const val SPARSE_SILHOUETTE: Float = 0.25f
    }
}
