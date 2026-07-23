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
                val raw = when (mode) {
                    SearchMode.CHAR -> db.searchByChar(query)
                    SearchMode.PRON -> db.searchByPron(query)
                    SearchMode.MEANING -> db.searchByMeaning(query)
                }
                val results = groupResults(raw)
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
}
