package org.siongdict.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.CognateGroup
import org.siongdict.app.data.DialectEntry
import org.siongdict.app.data.DictDatabase
import org.siongdict.app.data.PronEntry
import org.siongdict.app.data.SearchMode
import org.siongdict.app.data.SearchResult
import org.siongdict.app.data.ToneFormatter

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

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "SearchViewModel"
    }

    private val db = DictDatabase(app)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        val app = getApplication<Application>()
        val outdated = db.isDatabaseOutdated()
        _uiState.update {
            it.copy(dbOutdated = outdated, appVersion = DictDatabase.appVersion(app))
        }
        if (outdated) {
            viewModelScope.launch(Dispatchers.IO) {
                db.forceReset()
                _uiState.update { it.copy(dbOutdated = false) }
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
        _uiState.update { it.copy(loading = true, searched = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = when (state.mode) {
                    SearchMode.CHAR -> searchChars(query, state.filters)
                    SearchMode.COGNATE -> groupCognateResults(
                        filterCognateGroups(db.searchCognates(query), state.filters)
                    )
                    SearchMode.MEANING -> groupResults(
                        filterResults(db.searchByMeaning(query), state.filters)
                    )
                }
                _uiState.update { it.copy(results = results, loading = false) }
            } catch (e: Exception) {
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

    fun resetDatabases() {
        viewModelScope.launch(Dispatchers.IO) {
            db.forceReset()
            _uiState.update {
                it.copy(results = emptyList(), searched = false, error = null, dbOutdated = false)
            }
        }
    }

    // ─── 三种检索模式 ───

    /** 搜字音：每个字的每个异体各成一张卡，卡内按方言点合并，卡间按方言数降序。 */
    private fun searchChars(query: String, filters: DialectFilters): List<CharGroup> =
        query.flatMap { ch ->
            db.getVariants(ch.toString())
                .map { variant -> groupCharResults(filterResults(db.searchByChar(variant), filters), variant) }
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.first().entries.size }
                .flatten()
        }

    /** 搜释义：按字組分组。 */
    private fun groupResults(results: List<SearchResult>): List<CharGroup> =
        results.groupBy { it.chars }
            .map { (chars, entries) -> CharGroup(chars, entries.groupByDialect()) }
            .sortedByDescending { it.entries.size }

    /** 搜字音的单字卡片：不按字組分组，全部合并为一张卡。 */
    private fun groupCharResults(results: List<SearchResult>, query: String): List<CharGroup> =
        if (results.isEmpty()) emptyList() else listOf(CharGroup(query, results.groupByDialect()))

    /** 搜同源：每个 cognate group 成为一张卡片（成员已在库内按音典排序）。 */
    private fun groupCognateResults(groups: List<CognateGroup>): List<CharGroup> =
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
    private fun List<SearchResult>.groupByDialect(withCognate: Boolean = true): List<DialectEntry> =
        groupBy { it.lang }
            .map { (lang, prons) ->
                val cognate = if (!withCognate) null else prons.firstOrNull()?.let { p ->
                    runCatching { db.getCognateGroup(lang, p.ipa) }.getOrNull()
                }?.let(::formatCognate)
                DialectEntry(
                    lang = lang,
                    sortKey = prons.first().sortKey,
                    prons = formatProns(lang, prons.map { it.ipa to it.note }),
                    cognate = cognate
                )
            }
            .sortedBy { it.sortKey }

    /** 把原库读音按该点声调系统格式化成显示用的 (IPA, 注释)。 */
    private fun formatProns(lang: String, entries: List<Pair<String, String>>): List<PronEntry> {
        val toneSystem = db.getToneSystem(lang)
        return entries.map { (ipa, note) ->
            PronEntry(ToneFormatter.format(ipa, toneSystem), note)
        }
    }

    /** Format cognate member IPAs using each member's dialect tone system. */
    private fun formatCognate(cog: CognateGroup): CognateGroup = cog.copy(
        members = cog.members.map { m ->
            m.copy(ipa = ToneFormatter.format(m.ipa, db.getToneSystem(m.lang)))
        }
    )

    // ─── 方言筛选 ───

    private fun filterResults(results: List<SearchResult>, filters: DialectFilters): List<SearchResult> =
        if (!filters.anyHidden) results else results.filter { isDialectVisible(it.lang, filters) }

    private fun filterCognateGroups(
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
    private fun isDialectVisible(lang: String, filters: DialectFilters): Boolean {
        val div = db.getDialectDivision(lang)
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
