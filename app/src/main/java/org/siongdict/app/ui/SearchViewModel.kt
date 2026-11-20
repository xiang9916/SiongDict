package org.siongdict.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.siongdict.app.analysis.ClusterAnalysisResult
import org.siongdict.app.analysis.ClusterEngine
import org.siongdict.app.analysis.ClusterPartition
import org.siongdict.app.analysis.computeSimilarity
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.CognateBook
import org.siongdict.app.data.CognateGroup
import org.siongdict.app.data.CognateMembership
import org.siongdict.app.data.DatabaseMaintenance
import org.siongdict.app.data.DialectEntry
import org.siongdict.app.data.DialectProfile
import org.siongdict.app.data.Lexicon
import org.siongdict.app.data.PronEntry
import org.siongdict.app.data.SearchMode
import org.siongdict.app.data.SearchResult
import org.siongdict.app.data.ToneFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 方言筛选：叶子分区路径集合。
 *
 * [selected] 为 null 表示不筛选（全部显示），此时 [anyHidden] 为 false，过滤可整段跳过；
 * 非 null 时只显示该集合命中的叶子分区（见 [leafKey]）。
 */
data class DialectFilters(val selected: Set<String>? = null) {
    val anyHidden: Boolean get() = selected != null
}

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.CHAR,
    val results: List<CharGroup> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
    val filters: DialectFilters = DialectFilters(),
    /** 方言分区树（由数据层异步载入；空表示暂缺）。 */
    val dialectTree: List<DialectNode> = emptyList(),
    val dbOutdated: Boolean = false,
    val appVersion: String = ""
)


/**
 * 聚类分析界面状态（「聚類分析」对话框）。
 *
 * [result] 是 [ClusterEngine.analyze] 的产物；[edges] 是画布连线（每点前 2 条正相似边），
 * 由 ViewModel 额外用 [computeSimilarity] 算出——分析结果本身不含相似度矩阵。
 *
 * [selectedK] 为 null 表示「自動」：使用社群偵測给出的分区 [ClusterAnalysisResult.communities]；
 * 非 null 时只切换显示 UPGMA 的分区，不重新跑聚类。
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
     * [ClusterAnalysisResult.communities]；手动选择时取 UPGMA 中 k 相同的分区，
     * 找不到（例如社群数大于层次聚类记录的最大 k）时退回社群分区。
     */
    val partition: ClusterPartition?
        get() {
            val data = result ?: return null
            val manual = selectedK ?: return data.communities
            return data.partitions.firstOrNull { it.k == manual } ?: data.communities
        }

    /** 可选的最大分类数（UPGMA 分区的最大 k 与社群数取大；无结果时为 0）。 */
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

/** 一次聚类计算的三件产物；放在单独的 data class 里便于 onSuccess 一次写回。 */
private data class ClusterComputation(
    val result: ClusterAnalysisResult,
    val edges: List<ClusterEdge>,
    /** 由快照算出的「最小義項數」稳定上限（不随本次 minTags 变化）。 */
    val maxMinTags: Int,
    /** 本次实际采用的「最小義項數」：首次进入时可能是自动挑出的默认值。 */
    val minTags: Int
)

/**
 * 检索、分组与方言筛选。
 *
 * 数据库的串行化与线程调度由数据层（[Lexicon]／[CognateBook]／[DatabaseMaintenance]）负责，
 * 这里只保留状态机与检索代际守卫；端口由构造器注入，因此可以用 fake 在 JVM 上测试。
 */
class SearchViewModel(
    private val maintenance: DatabaseMaintenance,
    private val lexicon: Lexicon,
    private val cognateBook: CognateBook,
    appVersion: String,
    /** 分组/格式化等纯计算也留在 IO：与拆分前 onDb 的行为一致，避免把数百条 IPA 的格式化搬到主线程。 */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    companion object {
        private const val TAG = "SearchViewModel"

        /** 「最小義項數」滑桿上限的硬顶：再多也只是让滑桿步数失控，超过后没有区分意义。 */
        private const val MAX_MIN_TAGS = 10
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** 检索代际：只有最新一代的结果允许写回界面，防止慢查询覆盖新查询。 */
    private val searchSeq = AtomicInteger()

    private val _clusterState = MutableStateFlow(ClusterUiState())

    /** 聚类分析界面状态；懒加载，只有打开对话框时才读同源词资料。 */
    val clusterState: StateFlow<ClusterUiState> = _clusterState.asStateFlow()

    /** 聚类代际：调整最小义项数会重算，慢的旧结果不得覆盖新一轮。 */
    private val clusterSeq = AtomicInteger()

    /** 聚类输入快照：同一份资料反复调整参数时不再重复读库。 */
    private var clusterProfiles: List<DialectProfile> = emptyList()
    private var clusterMemberships: List<CognateMembership> = emptyList()
    private var clusterSnapshotLoaded = false

    /** 未套用方言筛选的全量画像；筛选变化时从它重新切片，不必重读库。 */
    private var clusterAllProfiles: List<DialectProfile> = emptyList()

    /** 当前快照所用的方言筛选；与界面筛选不一致时重新切片并重算。 */
    private var clusterFilters: DialectFilters = DialectFilters()

    init {
        val outdated = maintenance.isOutdated() // 只读偏好，主线程安全
        _uiState.update {
            it.copy(dbOutdated = outdated, appVersion = appVersion)
        }
        viewModelScope.launch {
            if (outdated) {
                maintenance.reset()
                _uiState.update { it.copy(dbOutdated = false) }
            } else {
                // 首装/升级后的 54 MB 复制在数据层的 IO 线程上做，不占主线程
                maintenance.prepare()
            }
            loadDialectTree()
        }
    }

    fun updateQuery(q: String) = _uiState.update { it.copy(query = q, error = null) }

    fun updateMode(mode: SearchMode) = _uiState.update { it.copy(mode = mode, error = null) }

    /** 更新筛选；已经搜过且查询非空时立即用新条件重跑，保证界面与筛选一致。 */
    fun updateFilters(filters: DialectFilters) {
        _uiState.update { it.copy(filters = filters) }
        val state = _uiState.value
        if (state.searched && state.query.isNotBlank()) search()
    }

    fun search() {
        val state = _uiState.value
        val query = state.query.trim()
        if (query.isEmpty()) return
        val seq = searchSeq.incrementAndGet()
        _uiState.update { it.copy(loading = true, searched = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(computeDispatcher) { computeResults(query, state.mode, state.filters) }
            }
            if (seq != searchSeq.get()) return@launch // 已被更新的检索或重置作废
            outcome.onSuccess { results ->
                _uiState.update { it.copy(results = results, loading = false) }
            }.onFailure { e ->
                Log.e(TAG, "Search failed", e)
                _uiState.update {
                    it.copy(
                        results = emptyList(),
                        loading = false,
                        error = e.message ?: e.toString()
                    )
                }
            }
        }
    }

    /** 按当前模式跑检索；纯组合，数据访问全部经注入的端口。 */
    private suspend fun computeResults(
        query: String,
        mode: SearchMode,
        filters: DialectFilters
    ): List<CharGroup> = when (mode) {
        SearchMode.CHAR -> searchChars(query, filters)
        SearchMode.COGNATE -> groupCognateResults(
            filterCognateGroups(cognateBook.search(query), filters)
        )
        SearchMode.MEANING -> groupResults(filterResults(lexicon.searchByMeaning(query), filters))
    }

    fun resetDatabases() {
        searchSeq.incrementAndGet() // 作废在途检索：重置期间旧结果不得回写
        clusterSeq.incrementAndGet() // 同理作废在途聚类
        clusterSnapshotLoaded = false
        clusterProfiles = emptyList()
        clusterMemberships = emptyList()
        clusterAllProfiles = emptyList()
        clusterFilters = DialectFilters()
        _clusterState.value = ClusterUiState() // 換庫後舊的同源詞快照失效，關閉對話框
        viewModelScope.launch {
            maintenance.reset()
            _uiState.update {
                it.copy(
                    results = emptyList(),
                    searched = false,
                    loading = false,
                    error = null,
                    dbOutdated = false
                )
            }
            loadDialectTree() // 重置后 generation 变化，分区树需重新载入
        }
    }

    // ─── 聚类分析（研究入口） ───

    /**
     * 打開「聚類分析」：首次打開時在 IO 上讀入 [Lexicon.dialectProfiles] 與
     * [CognateBook.memberships] 並存成快照，之後反覆調整參數只重算、不再讀庫。
     * 讀取失敗時保留錯誤訊息（對話框顯示），不影響檢索。
     */
    fun openClusterAnalysis() {
        _clusterState.update { it.copy(visible = true) }
        val state = _clusterState.value
        if (state.loading) return
        val filters = _uiState.value.filters
        if (clusterSnapshotLoaded) {
            // 结果缺失，或界面的方言筛选已经变了：用内存快照重新切片，不重读库
            if (state.result == null || filters != clusterFilters) {
                // 结果缺失（例如刚重置）时重新挑默认门槛；仅切换方言筛选则保留用户当前选择。
                applyFiltersAndRecompute(filters, state.minTags, useDefaultMinTags = state.result == null)
            }
            return
        }
        val seq = clusterSeq.incrementAndGet()
        _clusterState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(computeDispatcher) {
                    lexicon.dialectProfiles() to cognateBook.memberships()
                }
            }
            if (seq != clusterSeq.get()) return@launch // 已被重置或更新的请求作废
            outcome.onSuccess { (profiles, memberships) ->
                clusterAllProfiles = profiles
                clusterMemberships = memberships
                clusterSnapshotLoaded = true
            }.onFailure { e ->
                Log.e(TAG, "Failed to load cluster input", e)
                _clusterState.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
            if (outcome.isSuccess) {
                // 首次进入：最小义项数默认取轮廓系数最大的门槛。
                applyFiltersAndRecompute(
                    _uiState.value.filters,
                    _clusterState.value.minTags,
                    useDefaultMinTags = true
                )
            }
        }
    }

    /** 關閉對話框；結果留在快照裡，下次打開直接復用（除非拉高最小義項數或重置）。 */
    fun closeClusterAnalysis() {
        _clusterState.update { it.copy(visible = false, selectedLang = null) }
    }

    /** 切換顯示的分類數（null = 自動）；只換分區，不重跑聚類。 */
    fun setClusterCount(k: Int?) {
        val state = _clusterState.value
        val maxK = state.maxK
        val next = if (k == null || maxK < 2) null else k.coerceIn(2, maxK)
        _clusterState.update { it.copy(selectedK = next, selectedLang = null) }
    }

    /** 調整最小義項數：用快照重算，過濾掉義項太少的方言點。 */
    fun setMinTags(minTags: Int) {
        val state = _clusterState.value
        if (!clusterSnapshotLoaded) return
        val clamped = minTags.coerceIn(1, state.maxMinTags.coerceAtLeast(1))
        if (clamped == state.minTags) return
        recomputeClusters(clamped)
    }

    /** 點選 / 取消點選畫布節點（null 表示點空白處）。 */
    fun selectClusterPoint(lang: String?) {
        _clusterState.update { it.copy(selectedLang = lang) }
    }

    /**
     * 用快照重算聚類：`analyze` → 由 `computeSimilarity` 補出畫布連線。
     * 計算全部在 [computeDispatcher] 上；寫回前比對代際，避免慢結果覆蓋新參數。
     *
     * [useDefaultMinTags] 為 true 時（首次進入或結果缺失）不採用傳入值，改由
     * [ClusterEngine.defaultMinTags] 在 1..上限 內挑輪廓係數最大的門檻作為預設。
     */
    private fun recomputeClusters(minTags: Int, useDefaultMinTags: Boolean = false) {
        val profiles = clusterProfiles
        val memberships = clusterMemberships
        val seq = clusterSeq.incrementAndGet()
        _clusterState.update { it.copy(loading = true, error = null, selectedLang = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(computeDispatcher) {
                    val maxMinTags = computeMaxMinTags(profiles, memberships)
                    val effectiveMinTags = if (useDefaultMinTags) {
                        ClusterEngine.defaultMinTags(profiles, memberships, maxMinTags)
                    } else {
                        minTags.coerceIn(1, maxMinTags)
                    }
                    val result = ClusterEngine.analyze(profiles, memberships, effectiveMinTags)
                    val similarity = computeSimilarity(memberships, result.points.map { it.lang })
                    ClusterComputation(
                        result = result,
                        edges = buildClusterEdges(similarity),
                        maxMinTags = maxMinTags,
                        minTags = effectiveMinTags
                    )
                }
            }
            if (seq != clusterSeq.get()) return@launch
            outcome.onSuccess { computed ->
                _clusterState.update {
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
                _clusterState.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * 按当前方言筛选切片快照并重算：selected == null 时全收；否则只留
     * `leafKey(division)` 命中筛选集合的方言点（与检索的方言筛选同一套分区叶子键）。
     *
     * 只筛内存快照、不重读库，因此切换筛选后仍复用同一份同源词资料。
     */
    private fun applyFiltersAndRecompute(
        filters: DialectFilters,
        minTags: Int,
        useDefaultMinTags: Boolean
    ) {
        val selected = filters.selected
        clusterProfiles = if (selected == null) {
            clusterAllProfiles
        } else {
            clusterAllProfiles.filter { leafKey(it.division) in selected }
        }
        clusterFilters = filters
        // 新切片的上限可能比旧门槛小（筛选把义项多的点排除了）；先夹一次再算，保证状态一致
        val maxMinTags = computeMaxMinTags(clusterProfiles, clusterMemberships)
        recomputeClusters(minTags.coerceIn(1, maxMinTags), useDefaultMinTags)
    }

    /**
     * 「最小義項數」滑桿的稳定上限：在快照里取所有有同源词成员的可见方言点，
     * 各自的 distinct [CognateMembership.semanticTag] 数取最大，再夹到 1..[MAX_MIN_TAGS]。
     *
     * 与当前 minTags 及本次聚类结果无关：调高 minTags 只会让参与聚类的点变少，
     * 但这把尺子始终按完整快照量，所以上限不会缩水，滑桿范围恒包含当前值。
     */
    private fun computeMaxMinTags(
        profiles: List<DialectProfile>,
        memberships: List<CognateMembership>
    ): Int {
        if (profiles.isEmpty()) return 1
        val visibleLangs = profiles.mapTo(HashSet()) { it.lang }
        val tagsByLang = HashMap<String, HashSet<String>>()
        memberships.forEach { membership ->
            if (membership.lang in visibleLangs) {
                tagsByLang.getOrPut(membership.lang) { HashSet() }.add(membership.semanticTag)
            }
        }
        val max = tagsByLang.values.maxOfOrNull { it.size } ?: 1
        return max.coerceIn(1, MAX_MIN_TAGS)
    }

    /**
     * 载入全部方言点的音典分区并构建筛选树。数据层重置后 generation 变化，需要重新载入；
     * 失败时保留空树（界面显示「暫無分區資料」），不影响检索本身。
     */
    private suspend fun loadDialectTree() {
        val tree = runCatching {
            withContext(computeDispatcher) { buildDialectTree(lexicon.dialectDivisions()) }
        }.onFailure { Log.e(TAG, "Failed to load dialect tree", it) }.getOrDefault(emptyList())
        _uiState.update { it.copy(dialectTree = tree) }
    }

    // ─── 三种检索模式 ───

    /** 搜字音：每个字的每个异体各成一张卡，卡内按方言点合并，卡间按方言数降序。 */
    private suspend fun searchChars(query: String, filters: DialectFilters): List<CharGroup> =
        query.flatMap { ch ->
            lexicon.variantsOf(ch.toString())
                .map { variant ->
                    groupCharResults(filterResults(lexicon.searchByChar(variant), filters), variant)
                }
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.first().entries.size }
                .flatten()
        }

    /** 搜释义：按字組分组。 */
    private suspend fun groupResults(results: List<SearchResult>): List<CharGroup> =
        results.groupBy { it.chars }
            .map { (chars, entries) -> CharGroup(chars, entries.groupByDialect()) }
            .sortedByDescending { it.entries.size }

    /** 搜字音的单字卡片：不按字組分组，全部合并为一张卡。 */
    private suspend fun groupCharResults(results: List<SearchResult>, query: String): List<CharGroup> =
        if (results.isEmpty()) emptyList() else listOf(CharGroup(query, results.groupByDialect()))

    /** 搜同源：每个 cognate group 成为一张卡片（成员已在库内按音典排序）。 */
    private suspend fun groupCognateResults(groups: List<CognateGroup>): List<CharGroup> =
        groups.map { group ->
            val dialects = group.members.groupBy { it.lang }
                .map { (lang, members) ->
                    DialectEntry(
                        lang = lang,
                        sortKey = members.first().sortKey,
                        prons = formatProns(lang, members.map { it.ipa to it.note })
                    )
                }
                .sortedBy { it.sortKey }
            CharGroup(group.semanticLabel, dialects, group.groupId, charsIsLabel = true)
        }

    // ─── 方言点分组与格式化 ───

    /** 同一个方言点的读音合并成一条 DialectEntry；搜释义／搜字音时顺便挂上该点的同源词组。 */
    private suspend fun List<SearchResult>.groupByDialect(withCognate: Boolean = true): List<DialectEntry> =
        groupBy { it.lang }
            .map { (lang, prons) ->
                val cognate = if (!withCognate) null else prons.firstOrNull()?.let { p ->
                    runCatching { cognateBook.groupFor(lang, p.ipa) }.getOrNull()
                }?.let { formatCognate(it) }
                DialectEntry(
                    lang = lang,
                    sortKey = prons.first().sortKey,
                    prons = formatProns(lang, prons.map { it.ipa to it.note }),
                    cognate = cognate
                )
            }
            .sortedBy { it.sortKey }

    /** 把原库读音按该点声调系统格式化成显示用的 (IPA, 注释)。 */
    private suspend fun formatProns(lang: String, entries: List<Pair<String, String>>): List<PronEntry> {
        val toneSystem = lexicon.toneSystem(lang)
        return entries.map { (ipa, note) ->
            PronEntry(ToneFormatter.format(ipa, toneSystem), note)
        }
    }

    /** Format cognate member IPAs using each member's dialect tone system. */
    private suspend fun formatCognate(cog: CognateGroup): CognateGroup = cog.copy(
        members = cog.members.map { m ->
            m.copy(ipa = ToneFormatter.format(m.ipa, lexicon.toneSystem(m.lang)))
        }
    )

    // ─── 方言筛选 ───

    private suspend fun filterResults(
        results: List<SearchResult>,
        filters: DialectFilters
    ): List<SearchResult> =
        if (!filters.anyHidden) results else results.filter { isDialectVisible(it.lang, filters) }

    private suspend fun filterCognateGroups(
        groups: List<CognateGroup>,
        filters: DialectFilters
    ): List<CognateGroup> {
        if (!filters.anyHidden) return groups
        return groups.mapNotNull { g ->
            val filtered = g.members.filter { isDialectVisible(it.lang, filters) }
            if (filtered.isEmpty()) null else g.copy(members = filtered)
        }
    }

    /** 方言点是否可见：未筛选恒可见；否则按分区叶子键（[leafKey]）匹配。 */
    private suspend fun isDialectVisible(lang: String, filters: DialectFilters): Boolean {
        val selected = filters.selected ?: return true
        return leafKey(lexicon.dialectDivision(lang)) in selected
    }
}
