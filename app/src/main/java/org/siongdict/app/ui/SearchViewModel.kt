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
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.CognateBook
import org.siongdict.app.data.CognateGroup
import org.siongdict.app.data.DatabaseMaintenance
import org.siongdict.app.data.DialectEntry
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
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** 检索代际：只有最新一代的结果允许写回界面，防止慢查询覆盖新查询。 */
    private val searchSeq = AtomicInteger()

    /** 聚类分析会话：快照、代际守卫、默认门槛与筛选切片都在 [ClusterSession] 内。 */
    private val clusterSession =
        ClusterSession(lexicon, cognateBook, viewModelScope, computeDispatcher)

    /** 聚类分析界面状态（全屏页读取）。 */
    val clusterState: StateFlow<ClusterUiState> = clusterSession.state

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
        clusterSession.invalidate() // 作废快照与在途聚类，并关闭页面
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

    // ─── 聚类分析（研究入口，状态机在 [ClusterSession]） ───

    /** 打開「聚類分析」全屏頁；首次打開讀入快照，之後復用。 */
    fun openClusterAnalysis() = clusterSession.open(_uiState.value.filters)

    /** 關閉全屏頁；結果留在快照裡。 */
    fun closeClusterAnalysis() = clusterSession.close()

    /** 切換顯示的分類數（null = 自動）；只換分區，不重跑聚類。 */
    fun setClusterCount(k: Int?) = clusterSession.setClusterCount(k)

    /** 調整最小義項數：用快照重算。 */
    fun setMinTags(minTags: Int) = clusterSession.setMinTags(minTags)

    /** 點選 / 取消點選畫布節點（null 表示點空白處）。 */
    fun selectClusterPoint(lang: String?) = clusterSession.selectPoint(lang)

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
