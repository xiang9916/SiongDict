package org.siongdict.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siongdict.app.data.DictDatabase
import org.siongdict.app.data.SearchMode
import org.siongdict.app.data.SearchResult
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.DialectEntry
import org.siongdict.app.data.PronEntry
import org.siongdict.app.data.CognateGroup

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.CHAR,
    val results: List<CharGroup> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "SearchViewModel"
    }

    private val db = DictDatabase(app)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun updateQuery(q: String) {
        _uiState.value = _uiState.value.copy(query = q, error = null)
    }

    fun updateMode(mode: SearchMode) {
        _uiState.value = _uiState.value.copy(mode = mode, error = null)
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        val mode = _uiState.value.mode
        _uiState.value = _uiState.value.copy(loading = true, searched = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = when (mode) {
                    SearchMode.CHAR -> {
                        // 搜字：查询所有繁简异体变体，每种分别成卡，按方言数排序
                        val variants = db.getVariants(query)
                        variants.map { variant ->
                            val raw = db.searchByChar(variant)
                            groupCharResults(raw, variant)
                       }.filter { it.isNotEmpty() }
                           .sortedByDescending { it.first().entries.size }
                           .flatten()
                    }
                    SearchMode.COGNATE -> {
                        val cogGroups = db.searchCognates(query)
                        groupCognateResults(cogGroups)
                    }
                    SearchMode.MEANING -> {
                        val raw = db.searchByMeaning(query)
                        groupResults(raw)
                    }
                }
                _uiState.value = _uiState.value.copy(results = results, loading = false)
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                _uiState.value = _uiState.value.copy(
                    results = emptyList(),
                    loading = false,
                    error = e.message ?: e.toString()
                )
            }
        }
    }

    fun resetDatabases() {
        viewModelScope.launch(Dispatchers.IO) {
            db.forceReset()
            _uiState.value = _uiState.value.copy(
                results = emptyList(),
                searched = false,
                error = null
            )
        }
    }

    // 搜釋義：按字組分組
    private fun groupResults(results: List<SearchResult>): List<CharGroup> {
        return results
            .groupBy { it.chars }
            .map { (chars, entries) ->
                val dialects = entries
                    .groupBy { it.lang }
                    .map { (lang, prons) ->
                        val cog = prons.firstOrNull()?.let { p ->
                            try { db.getCognateGroup(lang, p.ipa) } catch (_: Exception) { null }
                        }
                        DialectEntry(
                            lang = lang,
                            sortKey = prons.first().sortKey,
                            prons = prons.map { PronEntry(it.ipa, it.note) },
                            cognate = cog
                        )
                    }
                    .sortedBy { it.sortKey }
                CharGroup(chars, dialects)
            }
            .sortedByDescending { it.entries.size }
    }

    // 搜字：不按字組分組，全部合併為一張卡片
    private fun groupCharResults(results: List<SearchResult>, query: String): List<CharGroup> {
        if (results.isEmpty()) return emptyList()
        val dialects = results
            .groupBy { it.lang }
            .map { (lang, prons) ->
                val cog = prons.firstOrNull()?.let { p ->
                    try { db.getCognateGroup(lang, p.ipa) } catch (_: Exception) { null }
                }
                DialectEntry(
                    lang = lang,
                    sortKey = prons.first().sortKey,
                    prons = prons.map { PronEntry(it.ipa, it.note) },
                    cognate = cog
                )
            }
            .sortedBy { it.sortKey }
        return listOf(CharGroup(query, dialects))
    }

    // 搜同源：每個 cognate group 成為一張卡片
    private fun groupCognateResults(groups: List<CognateGroup>): List<CharGroup> {
        return groups.map { g ->
            val dialects = g.members
                .groupBy { it.lang }
                .map { (lang, members) ->
                    DialectEntry(
                        lang = lang,
                        sortKey = members.first().sortKey,
                        prons = members.map { PronEntry(it.ipa, it.note) },
                        cognate = null
                    )
                }
                .sortedBy { it.sortKey }
            CharGroup(g.semanticLabel, dialects, g.groupId)
        }
    }
}
