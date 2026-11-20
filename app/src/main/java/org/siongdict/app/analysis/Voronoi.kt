package org.siongdict.app.analysis

/**
 * 泰森多邊形（Voronoi）——半平面裁剪版（純 Kotlin，禁止引用 android.*）。
 *
 * 對每個站點，從整個矩形開始，依序用「到本站點不比到其他站點遠」的半平面裁剪，
 * 剩下的多邊形就是它的勢力範圍（Sutherland–Hodgman 逐邊裁剪）。
 * 站點數 n ≤ 400，O(n²) 在手機上毫秒級；不需要外接矩形之外的無限單元格。
 */

/** 畫布上的一個軸對齊矩形（像素座標）。 */
data class PlaneRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** 一個站點在畫布上的位置。 */
data class PlanePoint(val x: Float, val y: Float)

/**
 * 計算 [sites] 在 [bounds] 內的泰森多邊形，回傳與 [sites] 同序的多邊形列表。
 *
 * 重合站點（距離恆等）不互相裁剪；單一站點時整個矩形都是它的單元格。
 * 退化情形（多邊形被裁到少於 3 點）回傳空列表，呼叫端跳過填色。
 */
internal fun voronoiCells(sites: List<PlanePoint>, bounds: PlaneRect): List<List<PlanePoint>> {
    if (sites.isEmpty()) return emptyList()
    val rectangle = listOf(
        PlanePoint(bounds.left, bounds.top),
        PlanePoint(bounds.right, bounds.top),
        PlanePoint(bounds.right, bounds.bottom),
        PlanePoint(bounds.left, bounds.bottom),
    )
    return sites.mapIndexed { index, site ->
        var polygon = rectangle
        for (other in 0 until sites.size) {
            if (other == index) continue
            val rival = sites[other]
            // 半平面：2(rx-sx)·p <= |r|² - |s|²（到 s 不比到 r 遠）。
            val a = 2f * (rival.x - site.x)
            val b = 2f * (rival.y - site.y)
            val c = (rival.x * rival.x + rival.y * rival.y) - (site.x * site.x + site.y * site.y)
            if (a == 0f && b == 0f) continue
            polygon = clipHalfPlane(polygon, a, b, c)
            if (polygon.isEmpty()) break
        }
        if (polygon.size < 3) emptyList() else polygon
    }
}

/** 保留 a·p <= c 的部分；與邊界相交時補上交點。 */
private fun clipHalfPlane(
    polygon: List<PlanePoint>,
    a: Float,
    b: Float,
    c: Float,
): List<PlanePoint> {
    if (polygon.isEmpty()) return polygon
    val output = ArrayList<PlanePoint>(polygon.size + 2)
    for (index in polygon.indices) {
        val current = polygon[index]
        val next = polygon[(index + 1) % polygon.size]
        val currentDistance = a * current.x + b * current.y - c
        val nextDistance = a * next.x + b * next.y - c
        if (currentDistance <= 0f) output.add(current)
        val crossing = (currentDistance < 0f && nextDistance > 0f) ||
            (currentDistance > 0f && nextDistance < 0f)
        if (crossing) {
            val t = currentDistance / (currentDistance - nextDistance)
            output.add(
                PlanePoint(
                    current.x + (next.x - current.x) * t,
                    current.y + (next.y - current.y) * t,
                )
            )
        }
    }
    return output
}
