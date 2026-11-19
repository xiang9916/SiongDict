package org.siongdict.app.ui

/**
 * 聚类画布配色：音典颜色解析与固定调色盘（纯 Kotlin，可在 JVM 上测试）。
 *
 * 音典 info 表的「音典顏色」有三种写法：
 * - `#RRGGBB`（不透明）；
 * - `#AARRGGBB`（自带 alpha）；
 * - 逗号分隔的双色（如 `#D32F2F,#FFCDD2`），表示该点用线性渐变。
 *
 * 任何解析失败都回落到中性灰，避免一个坏值让画布崩掉。
 */

/** 解析失败时的中性灰（0xFF9E9E9E）。 */
internal const val CLUSTER_GRAY: Long = 0xFF9E9E9EL

/** 聚类类别调色盘：固定 10 色，按类别序号取模使用。 */
internal val ClusterPalette: List<Long> = listOf(
    0xFF1E88E5L, // 蓝
    0xFFE53935L, // 红
    0xFF43A047L, // 绿
    0xFFFDD835L, // 黄
    0xFF8E24AAL, // 紫
    0xFFFB8C00L, // 橙
    0xFF00ACC1L, // 青
    0xFF6D4C41L, // 棕
    0xFF546E7AL, // 蓝灰
    0xFFD81B60L, // 玫红
)

/** 调色盘取色：序号超出 10 时循环。 */
internal fun paletteColor(index: Int): Long {
    val size = ClusterPalette.size
    val normalized = ((index % size) + size) % size
    return ClusterPalette[normalized]
}

/**
 * 解析一个音典颜色描述，返回 0xAARRGGBB 列表（1 个或 2 个元素）。
 *
 * - `#RRGGBB` 补足不透明 alpha（FF）；
 * - `#AARRGGBB` 原样保留 alpha；
 * - 逗号（半角或全角）分隔的双色按出现顺序返回；
 * - 空串、缺 `#`、位数不对、含非十六进制字符，或任一子色非法，都回 [CLUSTER_GRAY]。
 */
internal fun parseColorSpec(raw: String): List<Long> {
    val tokens = raw.replace('，', ',').split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return listOf(CLUSTER_GRAY)
    val parsed = tokens.map { parseSingleColor(it) }
    if (parsed.any { it == null }) return listOf(CLUSTER_GRAY)
    return parsed.filterNotNull()
}

/** 单个颜色：仅接受 6 位或 8 位十六进制，且必须以 `#` 开头。 */
private fun parseSingleColor(token: String): Long? {
    if (!token.startsWith("#")) return null
    val hex = token.substring(1)
    if (hex.length != 6 && hex.length != 8) return null
    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    val value = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) 0xFF000000L or value else value
}
