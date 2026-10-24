package org.siongdict.app.data

/**
 * 一个方言点的声调系统：调类键（"1"、"2a"…）→ 音典声调样式数组。
 *
 * 数组固定 5 个字段（[0] 是调值，如 "334"），但本对象不解释字段含义，也不做格式化——
 * 解析在 Lexicon 的本地实现里完成，格式化在 [ToneFormatter] 里完成。
 * 见 docs/adr/0006-data-layer-ports.md。
 */
data class ToneSystem(val styles: Map<String, List<String>>)
