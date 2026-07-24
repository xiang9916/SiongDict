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
import org.siongdict.app.data.CognateEntry
import org.siongdict.app.data.ToneFormatter

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.CHAR,
    val results: List<CharGroup> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
    val filterXiangGan: Boolean = true,
    val filterZhongShangJiang: Boolean = true,
    val filterXiangHuaTuHua: Boolean = true
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

    fun updateFilters(xiangGan: Boolean, zhongShangJiang: Boolean, xiangHuaTuHua: Boolean) {
        _uiState.value = _uiState.value.copy(
            filterXiangGan = xiangGan,
            filterZhongShangJiang = zhongShangJiang,
            filterXiangHuaTuHua = xiangHuaTuHua
        )
    }

   fun search() {
       val query = _uiState.value.query.trim()
       if (query.isEmpty()) return
       val mode = _uiState.value.mode
        val s = _uiState.value
        val filterEnabled = !s.filterXiangGan || !s.filterZhongShangJiang || !s.filterXiangHuaTuHua
       _uiState.value = _uiState.value.copy(loading = true, searched = true, error = null)
       viewModelScope.launch(Dispatchers.IO) {
           try {
               val results = when (mode) {
                  SearchMode.CHAR -> {
                       // 搜字：逐字查询繁简异体变体，每种分别成卡，按方言数排序
                       query.map { ch ->
                           val variants = db.getVariants(ch.toString())
                           variants.map { variant ->
                               val raw = db.searchByChar(variant)
                               .let { if (filterEnabled) filterResults(it) else it }
                               groupCharResults(raw, variant)
                           }.filter { it.isNotEmpty() }
                               .sortedByDescending { it.first().entries.size }
                               .flatten()
                       }.flatten()
                  }
                  SearchMode.COGNATE -> {
                      val cogGroups = db.searchCognates(query)
                      .let { if (filterEnabled) filterCognateGroups(it) else it }
                      groupCognateResults(cogGroups)
                  }
                   SearchMode.MEANING -> {
                       val raw = db.searchByMeaning(query)
                       .let { if (filterEnabled) filterResults(it) else it }
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

   // Filter raw search results by dialect division category
   private fun isDialectVisible(lang: String): Boolean {
       val s = _uiState.value
       val div = db.getDialectDivision(lang)
       return when {
           div.startsWith("湘贛") -> s.filterXiangGan
           div.startsWith("中上江") || div.startsWith("藍青") -> s.filterZhongShangJiang
           div.startsWith("湘南") || div == "道州" || div == "鄉話" -> s.filterXiangHuaTuHua
            div.startsWith("嶺東") || div.startsWith("嶺南") || div.startsWith("閩") -> s.filterXiangHuaTuHua
            div == "戲劇" -> s.filterXiangGan || s.filterZhongShangJiang
           else -> true
       }
   }

    private fun filterResults(results: List<SearchResult>): List<SearchResult> {
        return results.filter { r ->
            isDialectVisible(r.lang)
        }
    }

    private fun filterCognateGroups(groups: List<CognateGroup>): List<CognateGroup> {
        return groups.mapNotNull { g ->
            val filtered = g.members.filter { isDialectVisible(it.lang) }
            if (filtered.isEmpty()) null else g.copy(members = filtered)
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
                        val toneSystem = db.getToneSystem(lang)
                        val cog = prons.firstOrNull()?.let { p ->
                            try { db.getCognateGroup(lang, p.ipa) } catch (_: Exception) { null }
                        }?.let { formatCognate(it) }
                        DialectEntry(
                            lang = lang,
                            sortKey = prons.first().sortKey,
                            prons = prons.map { PronEntry(ToneFormatter.format(it.ipa, toneSystem), it.note) },
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
                val toneSystem = db.getToneSystem(lang)
                val cog = prons.firstOrNull()?.let { p ->
                    try { db.getCognateGroup(lang, p.ipa) } catch (_: Exception) { null }
                }?.let { formatCognate(it) }
                DialectEntry(
                    lang = lang,
                    sortKey = prons.first().sortKey,
                    prons = prons.map { PronEntry(ToneFormatter.format(it.ipa, toneSystem), it.note) },
                    cognate = cog
                )
            }
            .sortedBy { it.sortKey }
        return listOf(CharGroup(query, dialects))
    }

    // Format cognate member IPAs using each member's dialect tone system
    private fun formatCognate(cog: CognateGroup): CognateGroup {
        val formattedMembers = cog.members.map { m ->
            val ts = db.getToneSystem(m.lang)
            m.copy(ipa = ToneFormatter.format(m.ipa, ts))
        }
        return cog.copy(members = formattedMembers)
    }

    // 搜同源：每個 cognate group 成為一張卡片
    private fun groupCognateResults(groups: List<CognateGroup>): List<CharGroup> {
        return groups.map { g ->
            val dialects = g.members
                .groupBy { it.lang }
                .map { (lang, members) ->
                    val toneSystem = db.getToneSystem(lang)
                    DialectEntry(
                        lang = lang,
                        sortKey = members.first().sortKey,
                        prons = members.map { PronEntry(ToneFormatter.format(it.ipa, toneSystem), it.note) },
                        cognate = null
                    )
                }
                .sortedBy { it.sortKey }
            CharGroup(g.semanticLabel, dialects, g.groupId)
        }
    }
}
