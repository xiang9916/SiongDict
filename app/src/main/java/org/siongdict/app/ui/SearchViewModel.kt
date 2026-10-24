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

/** 三类方言分区筛选项。全部开启时（[anyHidden] 为 false）可以整段跳过过滤。 */
data class DialectFilters(
    val xiangGan: Boolean = true,
    val zhongShangJiang: Boolean = true,
    val xiangHuaTuHua: Boolean = true
) {
    val anyHidden: Boolean get() = !xiangGan || !zhongShangJiang || !xiangHuaTuHua
}

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.CHAR,
    val results: List<CharGroup> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
    val filters: DialectFilters = DialectFilters(),
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
        }
    }

    fun updateQuery(q: String) = _uiState.update { it.copy(query = q, error = null) }

    fun updateMode(mode: SearchMode) = _uiState.update { it.copy(mode = mode, error = null) }

    fun updateFilters(filters: DialectFilters) = _uiState.update { it.copy(filters = filters) }

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
        }
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

    /** Filter raw search results by dialect division category. */
    private suspend fun isDialectVisible(lang: String, filters: DialectFilters): Boolean {
        val div = lexicon.dialectDivision(lang)
        return when {
            div.startsWith("湘贛") -> filters.xiangGan
            div.startsWith("中上江") || div.startsWith("藍青") -> filters.zhongShangJiang
            div.startsWith("湘南") || div == "道州" || div == "鄉話" -> filters.xiangHuaTuHua
            div.startsWith("嶺東") || div.startsWith("嶺南") || div.startsWith("閩") -> filters.xiangHuaTuHua
            div == "戲劇" -> filters.xiangGan || filters.zhongShangJiang
            else -> true
        }
    }
}
