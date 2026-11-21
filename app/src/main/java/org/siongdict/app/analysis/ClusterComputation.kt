package org.siongdict.app.analysis

/**
 * 一次聚类分析的完整产物：分析结果 + 画布连线 + 「最小义项数」的上限与实际采用值。
 *
 * [ClusterEngine.analyzeComputation] 是唯一生产点：它在内部复用同一份相似度矩阵算连线，
 * 调用方（会话状态机）只负责调度与写回状态，不再重复聚合或重算 O(n²) 相似度。
 */
internal data class ClusterComputation(
    val result: ClusterAnalysisResult,
    val edges: List<ClusterEdge>,
    /** 由完整快照算出的「最小义项数」稳定上限（与本次门槛无关，滑桿范围恒包含当前值）。 */
    val maxMinTags: Int,
    /** 本次实际采用的门槛：首次进入时可能是自动挑出的默认值。 */
    val minTags: Int,
)
