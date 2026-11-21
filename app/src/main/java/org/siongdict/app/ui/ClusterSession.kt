package org.siongdict.app.ui

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.siongdict.app.analysis.ClusterAnalysisResult
import org.siongdict.app.analysis.ClusterEdge
import org.siongdict.app.analysis.ClusterEngine
import org.siongdict.app.analysis.ClusterPartition
import org.siongdict.app.data.CognateBook
import org.siongdict.app.data.CognateMembership
import org.siongdict.app.data.DialectProfile
import org.siongdict.app.data.Lexicon
import java.util.concurrent.atomic.AtomicInteger

/**
 * 聚类分析界面状态（「聚類分析」全屏頁）。
 *
 * [result] 是 [ClusterEngine.analyzeComputation] 的产物；[edges] 是画布连线
 * （每点前 2 条正相似边），由引擎在同一份相似度矩阵上产出。
 *
 * [selectedK] 为 null 表示「自動」：使用候選搜索選出的分區 [ClusterAnalysisResult.communities]；
 * 非 null 时只切換顯示候選分區（UPGMA 各 k 與 Louvain 多解析度裡每個 k 的最佳者），不重新跑聚類。
 */
data class ClusterUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val result: ClusterAnalysisResult? = null,
    val edges: List<ClusterEdge> = emptyList(),
    val selectedK: Int? = null,
    val minTags: Int = 1,
    /** 「最小義項數」滑桿上限：来自快照的固定值，不随 [minTags] 变化而缩水，恒 ≥ [minTags]。 */
    val maxMinTags: Int = 1,
    val selectedLang: String? = null
) {
    /**
     * 当前显示的分区：「自動」（[selectedK] == null）直接用社群偵測结果
     * [ClusterAnalysisResult.communities]；手动选择时取候选分区中 k 相同者，
     * 找不到（例如社群数大于候选记录的最大 k）时退回社群分区。
     */
    val partition: ClusterPartition?
        get() {
            val data = result ?: return null
            val manual = selectedK ?: return data.communities
            return data.partitions.firstOrNull { it.k == manual } ?: data.communities
        }

    /** 可选的最大分类数（候选分区的最大 k 与社群数取大；无结果时为 0）。 */
    val maxK: Int
        get() {
            val data = result ?: return 0
            return maxOf(data.partitions.maxOfOrNull { it.k } ?: 0, data.optimalK)
        }

    /** 选中方言点所在的类别序号（用于高亮凸包与色块）；未选或找不到时为 null。 */
    val highlightedCluster: Int?
        get() {
            val lang = selectedLang ?: return null
            val data = result ?: return null
            val partitionData = partition ?: return null
            val index = data.points.indexOfFirst { it.lang == lang }
            if (index < 0) return null
            return partitionData.clusters.indexOfFirst { index in it }.takeIf { it >= 0 }
        }
}

/**
 * 聚类分析会话：持有「一次分析」所需的全部可变状态，把快照、代际守卫、
 * 默认门槛、上下限与筛选切片从 [SearchViewModel] 里独立出来。
 *
 * - 输入快照（方言画像 + 同源词成员）只在首次打开时读一次；之后调参只重算、不读库。
 * - 方言筛选变化时只按 [leafKey] 切片内存快照，不重读库。
 * - 调门槛会重算：慢的旧结果不得覆盖新一轮（[generation] 代际守卫）。
 * - 重置数据库时 [invalidate] 作废快照与在途计算，并关闭页面。
 *
 * 纯状态机：不碰 Compose、不持有 UI；调度与写回全部在注入的 [scope] 与
 * [computeDispatcher] 上，因此可用 fake 端口在 JVM 上直接测试。
 */
internal class ClusterSession(
    private val lexicon: Lexicon,
    private val cognateBook: CognateBook,
    private val scope: CoroutineScope,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow(ClusterUiState())

    /** 聚类分析界面状态；懒加载，只有打开页面时才读同源词资料。 */
    val state: StateFlow<ClusterUiState> = _state.asStateFlow()

    /** 聚类代际：调整最小义项数会重算，慢的旧结果不得覆盖新一轮。 */
    private val generation = AtomicInteger()

    /** 未套用方言筛选的全量画像；筛选变化时从它重新切片，不必重读库。 */
    private var allProfiles: List<DialectProfile> = emptyList()

    /** 当前快照切片后的方言画像（按方言筛选过滤）。 */
    private var profiles: List<DialectProfile> = emptyList()

    private var memberships: List<CognateMembership> = emptyList()

    private var snapshotLoaded = false

    /** 当前快照所用的方言筛选；与传入筛选不一致时重新切片并重算。 */
    private var filters: DialectFilters = DialectFilters()

    /**
     * 打開「聚類分析」：首次打開時在 IO 上讀入 [Lexicon.dialectProfiles] 與
     * [CognateBook.memberships] 並存成快照，之後反覆調整參數只重算、不再讀庫。
     * 讀取失敗時保留錯誤訊息（頁面顯示），不影響檢索。
     */
    fun open(filters: DialectFilters) {
        _state.update { it.copy(visible = true) }
        val state = _state.value
        if (state.loading) return
        if (snapshotLoaded) {
            // 结果缺失，或界面的方言筛选已经变了：用内存快照重新切片，不重读库
            if (state.result == null || filters != this.filters) {
                // 结果缺失（例如刚重置）时重新挑默认门槛；仅切换方言筛选则保留用户当前选择。
                applyFiltersAndRecompute(filters, state.minTags, useDefaultMinTags = state.result == null)
            }
            return
        }
        val seq = generation.incrementAndGet()
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val outcome = runCatching {
                withContext(computeDispatcher) {
                    lexicon.dialectProfiles() to cognateBook.memberships()
                }
            }
            if (seq != generation.get()) return@launch // 已被重置或更新的请求作废
            outcome.onSuccess { (loadedProfiles, loadedMemberships) ->
                allProfiles = loadedProfiles
                memberships = loadedMemberships
                snapshotLoaded = true
            }.onFailure { e ->
                Log.e(TAG, "Failed to load cluster input", e)
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
            if (outcome.isSuccess) {
                // 首次进入：最小义项数默认取轮廓系数最大的门槛。
                applyFiltersAndRecompute(
                    filters = filters,
                    minTags = _state.value.minTags,
                    useDefaultMinTags = true,
                )
            }
        }
    }

    /** 關閉頁面；結果留在快照裡，下次打開直接復用（除非拉高最小義項數或重置）。 */
    fun close() {
        _state.update { it.copy(visible = false, selectedLang = null) }
    }

    /** 切換顯示的分類數（null = 自動）；只換分區，不重跑聚類。 */
    fun setClusterCount(k: Int?) {
        val state = _state.value
        val maxK = state.maxK
        val next = if (k == null || maxK < 2) null else k.coerceIn(2, maxK)
        _state.update { it.copy(selectedK = next, selectedLang = null) }
    }

    /** 調整最小義項數：用快照重算，過濾掉義項太少的方言點。 */
    fun setMinTags(minTags: Int) {
        val state = _state.value
        if (!snapshotLoaded) return
        val clamped = minTags.coerceIn(1, state.maxMinTags.coerceAtLeast(1))
        if (clamped == state.minTags) return
        recompute(clamped)
    }

    /** 點選 / 取消點選畫布節點（null 表示點空白處）。 */
    fun selectPoint(lang: String?) {
        _state.update { it.copy(selectedLang = lang) }
    }

    /**
     * 資料庫重置後作廢全部快照與在途計算，並關閉頁面；下次打開會重新讀庫。
     * 由 [SearchViewModel.resetDatabases] 在重置前呼叫。
     */
    fun invalidate() {
        generation.incrementAndGet()
        snapshotLoaded = false
        allProfiles = emptyList()
        profiles = emptyList()
        memberships = emptyList()
        filters = DialectFilters()
        _state.value = ClusterUiState()
    }

    /**
     * 用快照重算聚類：一次分析同时产出结果、画布连线、门槛上限与实际门槛；
     * 计算全部在 [computeDispatcher] 上，写回前比對代際，避免慢結果覆蓋新參數。
     *
     * [useDefaultMinTags] 為 true 時（首次進入或結果缺失）不採用傳入值，改由
     * [ClusterEngine.defaultMinTags] 在 1..上限 內挑輪廓係數最大的門檻作為預設。
     */
    private fun recompute(minTags: Int, useDefaultMinTags: Boolean = false) {
        val currentProfiles = profiles
        val currentMemberships = memberships
        val seq = generation.incrementAndGet()
        _state.update { it.copy(loading = true, error = null, selectedLang = null) }
        scope.launch {
            val outcome = runCatching {
                withContext(computeDispatcher) {
                    ClusterEngine.analyzeComputation(
                        profiles = currentProfiles,
                        memberships = currentMemberships,
                        minTags = minTags,
                        useDefaultMinTags = useDefaultMinTags,
                    )
                }
            }
            if (seq != generation.get()) return@launch
            outcome.onSuccess { computed ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        result = computed.result,
                        edges = computed.edges,
                        // 上限来自快照、与本次 minTags 无关，因此调高门槛后不会缩水；
                        // 这里再夹一次，保证 Slider 范围恒包含当前值。
                        minTags = computed.minTags.coerceIn(1, computed.maxMinTags),
                        maxMinTags = computed.maxMinTags,
                        selectedK = null,
                        selectedLang = null
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "Cluster analysis failed", e)
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * 按当前方言筛选切片快照并重算：selected == null 时全收；否则只留
     * [leafKey] 命中筛选集合的方言点（与检索的方言筛选同一套分区叶子键）。
     *
     * 只筛内存快照、不重读库，因此切换筛选后仍复用同一份同源词资料。
     */
    private fun applyFiltersAndRecompute(
        filters: DialectFilters,
        minTags: Int,
        useDefaultMinTags: Boolean
    ) {
        val selected = filters.selected
        profiles = if (selected == null) {
            allProfiles
        } else {
            allProfiles.filter { leafKey(it.division) in selected }
        }
        this.filters = filters
        recompute(minTags, useDefaultMinTags)
    }

    private companion object {
        const val TAG = "ClusterSession"
    }
}
