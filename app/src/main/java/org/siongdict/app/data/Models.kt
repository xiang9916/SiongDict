package org.siongdict.app.data

data class SearchResult(
    val chars: String,
    val lang: String,
    val ipa: String,
    val note: String,
    val sortKey: String = ""
)

data class PronEntry(
    val ipa: String,
    val note: String
)

data class DialectEntry(
    val lang: String,
    val sortKey: String,
    val prons: List<PronEntry>,
    val cognate: CognateGroup? = null
)

data class CharGroup(
    val chars: String,
    val entries: List<DialectEntry>,
    val subtitle: String = ""
)

enum class SearchMode(val label: String) {
    CHAR("搜字"),
    MEANING("搜釋義"),
    COGNATE("搜同源")
}

data class CognateEntry(
    val lang: String,
    val ipa: String,
    val note: String,
    val sortKey: String
)

data class CognateGroup(
    val groupId: String,
    val semanticLabel: String,
    val members: List<CognateEntry>
)
