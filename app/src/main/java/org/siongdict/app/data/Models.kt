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
    val subtitle: String = "",
    /**
     * 搜同源的卡片把**義項標籤**放在 [chars] 里（而不是字組）。简体渲染要转它；
     * 字組與使用者輸入一律不转（見 CONTEXT.md 的「字組」「簡體渲染」）。
     */
    val charsIsLabel: Boolean = false
)

enum class SearchMode(val label: String) {
    CHAR("搜字音"),
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
