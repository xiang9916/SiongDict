package org.siongdict.app.analysis

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * Web Mercator 投影與「把經緯度塞進畫布」的變換（純 Kotlin，禁止引用 android.*）。
 *
 * 只做等距圓柱的縱向拉伸（Web Mercator），不引任何地圖庫：
 * 湖南省域尺度下與線上地圖的視覺位置一致，且完全離線。
 *
 * **單位約定（重要）**：經度與 Mercator 縱座標一律用**弧度**作畫布換算，
 * 兩軸共用同一個 [MapTransform.scale]，內容才不會被拉伸變形。
 * 歷史上這裡曾讓 X 用「度」、Y 用「弧度」，兩者相差 180/π ≈ 57.3 倍，
 * 結果整張地圖被壓成十幾像素高的橫帶、泰森多邊形退化成滿屏豎條（2026-11-20 修正）。
 */

/** 度 → 弧度。 */
private const val DEG_TO_RAD = PI / 180.0

/** 弧度 → 度。 */
private const val RAD_TO_DEG = 180.0 / PI

/** Web Mercator 縱座標（單位為弧度）；緯度先夾在 ±85°，避免 tan 爆炸。 */
internal fun mercatorY(lat: Double): Double {
    val clamped = lat.coerceIn(-85.0, 85.0)
    return ln(tan(PI / 4.0 + clamped * PI / 360.0))
}

/** Mercator 縱座標反解回緯度。 */
internal fun inverseMercatorY(y: Double): Double =
    (2.0 * atan(exp(y)) - PI / 2.0) * 180.0 / PI

/**
 * 經緯度 → 畫布像素的仿射變換。
 *
 * [scale] 是「每弧度對應的像素數」；X 用經度（先轉弧度）、Y 用 Mercator 縱座標（本身就是弧度），
 * 兩軸共用同一 [scale] 保證不變形；內容在畫布中置中，尺寸為 [contentWidth] × [contentHeight]。
 */
data class MapTransform(
    val minLon: Double,
    val maxLon: Double,
    val minMercatorY: Double,
    val maxMercatorY: Double,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val contentWidth: Float,
    val contentHeight: Float,
) {
    /** 地圖內容在畫布上的外接矩形（泰森多邊形的裁剪邊界）。 */
    fun contentBounds(): PlaneRect =
        PlaneRect(offsetX, offsetY, offsetX + contentWidth, offsetY + contentHeight)

    /** 經緯度 → 畫布座標（x 向右、y 向下）。 */
    fun project(lon: Double, lat: Double): Pair<Float, Float> {
        val x = offsetX + (((lon - minLon) * DEG_TO_RAD) * scale).toFloat()
        val y = offsetY + ((maxMercatorY - mercatorY(lat)) * scale).toFloat()
        return x to y
    }

    /** 畫布座標 → 經緯度（供點擊命中與手勢換算）。 */
    fun unproject(x: Float, y: Float): LonLat {
        val lon = minLon + (x - offsetX) / scale * RAD_TO_DEG
        val mercator = maxMercatorY - (y - offsetY) / scale
        return LonLat(lon, inverseMercatorY(mercator))
    }
}

/**
 * 依底圖與站點的外接矩形建立變換，內容等比縮放並置中，四周留 [padding] 像素。
 *
 * 優先使用 [shapes] 的範圍（地圖完整可見）；沒有底圖時退回站點範圍（至少給 0.05° 邊距，
 * 避免單點時除零）。
 */
internal fun fitTransform(
    sites: List<MapSite>,
    shapes: List<ProvinceShape>,
    width: Float,
    height: Float,
    padding: Float = 12f,
): MapTransform {
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    var minMercator = Double.MAX_VALUE
    var maxMercator = -Double.MAX_VALUE

    fun include(lon: Double, lat: Double) {
        minLon = min(minLon, lon)
        maxLon = maxOf(maxLon, lon)
        val mercator = mercatorY(lat)
        minMercator = min(minMercator, mercator)
        maxMercator = maxOf(maxMercator, mercator)
    }

    for (shape in shapes) {
        for (polygon in shape.polygons) {
            for (point in polygon) include(point.lon, point.lat)
        }
    }
    if (minLon > maxLon) {
        for (site in sites) include(site.lon, site.lat)
    }
    if (minLon > maxLon) {
        // 完全沒有資料：給一個以 0,0 為中心的小方框，讓呼叫端仍能算出合法變換。
        minLon = -0.05
        maxLon = 0.05
        minMercator = mercatorY(-0.05)
        maxMercator = mercatorY(0.05)
    }
    val lonSpanRad = ((maxLon - minLon) * DEG_TO_RAD).coerceAtLeast(1e-9)
    val mercatorSpan = (maxMercator - minMercator).coerceAtLeast(1e-9)
    val availableWidth = (width - 2f * padding).coerceAtLeast(1f)
    val availableHeight = (height - 2f * padding).coerceAtLeast(1f)
    // 兩軸同單位（弧度）取同一 scale，保證內容不變形。
    val scale = min(
        availableWidth / lonSpanRad.toFloat(),
        availableHeight / mercatorSpan.toFloat(),
    )
    val contentWidth = lonSpanRad.toFloat() * scale
    val contentHeight = mercatorSpan.toFloat() * scale
    return MapTransform(
        minLon = minLon,
        maxLon = maxLon,
        minMercatorY = minMercator,
        maxMercatorY = maxMercator,
        scale = scale,
        offsetX = (width - contentWidth) / 2f,
        offsetY = (height - contentHeight) / 2f,
        contentWidth = contentWidth,
        contentHeight = contentHeight,
    )
}
