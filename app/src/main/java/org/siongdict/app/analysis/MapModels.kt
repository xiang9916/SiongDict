package org.siongdict.app.analysis

/**
 * 地图展示用的纯数据模型（纯 Kotlin，禁止引用 android.*）。
 *
 * 坐标系一律 WGS84 经纬度；投影与泰森多边形都在 [MapProjection]、[Voronoi] 里完成，
 * 界面层只负责画。底图是离线简化后的省级多边形（assets/maps/provinces.json）。
 */

/** 一个经纬度坐标。 */
data class LonLat(val lon: Double, val lat: Double)

/** 一个省级单元的简化轮廓：一个省可能由多块多边形组成（如岛屿）。 */
data class ProvinceShape(
    val name: String,
    val polygons: List<List<LonLat>>,
)

/** 地图上的一個方言點。 */
data class MapSite(
    val lang: String,
    val lon: Double,
    val lat: Double,
    /** 所屬類別號（對應 [ClusterAnalysisResult.points] 的分區類號）。 */
    val cluster: Int,
    /** 節點顏色（音典顏色，解析失敗時由呼叫端給預設色）。 */
    val colorHex: String,
)

/**
 * 解析 info 表「經緯度」欄位（格式 "lon,lat"，例如 "113.947296,29.974351"）。
 * 空白、缺欄、非數字、非有限值一律返回 null。
 */
internal fun parseLonLat(raw: String?): Pair<Double, Double>? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(',')
    if (parts.size != 2) return null
    val lon = parts[0].trim().toDoubleOrNull() ?: return null
    val lat = parts[1].trim().toDoubleOrNull() ?: return null
    if (!lon.isFinite() || !lat.isFinite()) return null
    if (lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) return null
    return lon to lat
}
