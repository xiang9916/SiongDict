package org.siongdict.app.fakes

import kotlinx.coroutines.delay
import org.siongdict.app.data.CognateBook
import org.siongdict.app.data.CognateEntry
import org.siongdict.app.data.CognateGroup
import org.siongdict.app.data.DatabaseMaintenance
import org.siongdict.app.data.Lexicon
import org.siongdict.app.data.SearchResult
import org.siongdict.app.data.ToneSystem

/** 可配置的假字音库：每个方法都能注入行为（延迟、异常、返回值）。 */
class FakeLexicon : Lexicon {

    var onSearchByChar: suspend (String) -> List<SearchResult> = { emptyList() }
    var onSearchByMeaning: suspend (String) -> List<SearchResult> = { emptyList() }
    var onVariantsOf: (String) -> List<String> = { listOf(it) }
    var onToneSystem: (String) -> ToneSystem? = { null }
    var onDialectDivision: (String) -> String = { "" }

    val charQueries = mutableListOf<String>()

    override suspend fun searchByChar(hz: String): List<SearchResult> {
        charQueries += hz
        return onSearchByChar(hz)
    }

    override suspend fun searchByMeaning(keyword: String): List<SearchResult> = onSearchByMeaning(keyword)

    override suspend fun variantsOf(ch: String): List<String> = onVariantsOf(ch)

    override suspend fun toneSystem(lang: String): ToneSystem? = onToneSystem(lang)

    override suspend fun dialectDivision(lang: String): String = onDialectDivision(lang)
}

class FakeCognateBook : CognateBook {

    var onSearch: suspend (String) -> List<CognateGroup> = { emptyList() }
    var onGroupFor: suspend (String, String) -> CognateGroup? = { _, _ -> null }

    override suspend fun search(query: String): List<CognateGroup> = onSearch(query)

    override suspend fun groupFor(lang: String, ipa: String): CognateGroup? = onGroupFor(lang, ipa)
}

class FakeMaintenance(var outdated: Boolean = false) : DatabaseMaintenance {

    var prepareCount = 0
    var resetCount = 0
    var prepareDelayMs = 0L
    var resetDelayMs = 0L
    var resetFailure: Throwable? = null

    override fun isOutdated(): Boolean = outdated

    override suspend fun prepare() {
        prepareCount++
        if (prepareDelayMs > 0) delay(prepareDelayMs)
    }

    override suspend fun reset() {
        resetCount++
        if (resetDelayMs > 0) delay(resetDelayMs)
        resetFailure?.let { throw it }
    }
}

// ── 构造小工具 ──

fun result(chars: String, lang: String, ipa: String, note: String = "", sortKey: String = lang) =
    SearchResult(chars, lang, ipa, note, sortKey)

fun cognateEntry(lang: String, ipa: String, note: String = "", sortKey: String = lang) =
    CognateEntry(lang, ipa, note, sortKey)

fun cognateGroup(groupId: String, label: String, vararg members: CognateEntry) =
    CognateGroup(groupId, label, members.toList())
