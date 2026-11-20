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

/**
 * 方言点画像（info 表）：簡稱、音典分区、音典颜色（#RRGGBB）与音典排序。
 *
 * 供聚类分析建立「方言点」画布：分区与颜色直接取自音典元数据，
 * 解析层（analysis）只消费这个纯值对象，不接触数据库。
 */
data class DialectProfile(
    val lang: String,
    val division: String,
    val colorHex: String,
    val sortKey: String,
    /** 經度（info 表「經緯度」左半）；缺失為 null。 */
    val lon: Double? = null,
    /** 緯度（info 表「經緯度」右半）；缺失為 null。 */
    val lat: Double? = null
)

/**
 * 同源词成员关系：某个方言点在某义项（[semanticTag]）下的某个音类组（[groupId]）里有一条记录。
 *
 * 这是聚类相似度的最小输入：一个 (lang, semanticTag) 可能对应多个 groupId
 * （同义项多音类组），相似度按义项聚合后再判断是否共享组。
 */
data class CognateMembership(
    val lang: String,
    val groupId: String,
    val semanticTag: String
)
