package org.siongdict.app.data

data class SearchResult(
    val chars: String,
    val lang: String,
    val ipa: String,
    val note: String
)

data class DialectInfo(
    val name: String = "",
    val shortName: String = "",
    val location: String = "",
    val division: String = "",
    val ydDivision: String = "",
    val color: String = "",
    val charCount: String = ""
)

enum class SearchMode(val label: String) {
    CHAR("搜字"),
    PRON("搜音"),
    MEANING("搜釋義")
}
