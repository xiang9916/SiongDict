package org.siongdict.app.ui

import org.siongdict.app.analysis.PlaneRect
import kotlin.math.sqrt

/**
 * 三維散点画布的轨道视口：偏航／俯仰旋转 + 缩放 + 平移。
 *
 * 只负责数值与夹取；手势状态机（多指质心、双击／轻点判定）留在画布里。
 */
internal data class OrbitViewport(
    val yawDeg: Float,
    val pitchDeg: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    /** 单指拖动：左右改 yaw、上下改 pitch（pitch 夹在 ±[maxPitchDeg]）。 */
    fun rotated(deltaX: Float, deltaY: Float, degreesPerPx: Float, maxPitchDeg: Float): OrbitViewport =
        copy(
            yawDeg = yawDeg + deltaX * degreesPerPx,
            pitchDeg = (pitchDeg - deltaY * degreesPerPx).coerceIn(-maxPitchDeg, maxPitchDeg),
        )

    /** 缩放到 [targetZoom] 并夹在 [minZoom]..[maxZoom]。 */
    fun zoomedTo(targetZoom: Float, minZoom: Float, maxZoom: Float): OrbitViewport =
        copy(zoom = targetZoom.coerceIn(minZoom, maxZoom))

    fun panned(deltaX: Float, deltaY: Float): OrbitViewport =
        copy(panX = panX + deltaX, panY = panY + deltaY)
}

/** 初始／双击复位视角。 */
internal fun defaultOrbitViewport(yawDeg: Float, pitchDeg: Float): OrbitViewport =
    OrbitViewport(yawDeg = yawDeg, pitchDeg = pitchDeg)

/** 三維画布轻点命中：返回距离 ([tapX], [tapY]) 最近且在 [radius] 内的投影点下标。 */
internal fun nearestProjectedIndex(
    projected: List<ProjectedPoint>,
    tapX: Float,
    tapY: Float,
    radius: Float,
): Int? {
    var bestIndex = -1
    var bestDistance = Float.MAX_VALUE
    projected.forEachIndexed { index, point ->
        val dx = point.x - tapX
        val dy = point.y - tapY
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return if (bestIndex >= 0 && bestDistance <= radius) bestIndex else null
}

/**
 * 地圖视口：以手势中心为锚点的缩放 + 平移。
 *
 * [transformed] 把一帧 [detectTransformGestures] 的结果折进来：缩放时手指中心
 * 下的地理位置保持不动（先换算回基准坐标，再套新倍率与平移）。
 */
internal data class MapViewport(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun transformed(
        centroidX: Float,
        centroidY: Float,
        panDeltaX: Float,
        panDeltaY: Float,
        gestureZoom: Float,
        minZoom: Float,
        maxZoom: Float,
    ): MapViewport {
        val newZoom = (zoom * gestureZoom).coerceIn(minZoom, maxZoom)
        val baseX = (centroidX - panX) / zoom
        val baseY = (centroidY - panY) / zoom
        return MapViewport(
            zoom = newZoom,
            panX = centroidX - baseX * newZoom + panDeltaX,
            panY = centroidY - baseY * newZoom + panDeltaY,
        )
    }
}

/**
 * 地圖站點的螢幕座標：基準（內容）座標先套 [MapViewport] 的縮放與平移。
 *
 * 站點圓圈、標籤與輕點命中都用這個結果，半徑／字號才不會被畫布的 scale 一起放大。
 */
internal fun screenPosition(
    base: Pair<Float, Float>,
    viewport: MapViewport,
): Pair<Float, Float> = (base.first * viewport.zoom + viewport.panX) to
    (base.second * viewport.zoom + viewport.panY)

/**
 * 地圖內容框內可見站點的下標（保持原順序）。
 *
 * 底圖裁切到經緯度 bbox 後，contentBounds() 就是「地圖框」：框外站點
 * 不畫、不進泰森多邊形、不參與標籤避讓與輕點命中，避免落到框外空白。判定用未套
 * 視口的基準座標，因此與縮放／平移無關；正好落在邊界上的點算框內。
 */
internal fun visibleSiteIndices(
    basePositions: List<Pair<Float, Float>>,
    bounds: PlaneRect,
): List<Int> {
    val result = ArrayList<Int>(basePositions.size)
    basePositions.forEachIndexed { index, position ->
        val x = position.first
        val y = position.second
        if (x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom) {
            result.add(index)
        }
    }
    return result
}

/**
 * 地圖轻点命中：返回距离 ([tapX], [tapY]) 最近且在 [radius] 内的站点下标（先套视口变换）。
 *
 * [radius] 是螢幕像素（呼叫端傳 16dp），不隨縮放放大；候選站點由呼叫端先筛成框內點。
 */
internal fun nearestSiteIndex(
    basePositions: List<Pair<Float, Float>>,
    viewport: MapViewport,
    tapX: Float,
    tapY: Float,
    radius: Float,
): Int? {
    var nearestIndex = -1
    var nearestDistance = radius
    basePositions.forEachIndexed { index, base ->
        val (x, y) = screenPosition(base, viewport)
        val dx = x - tapX
        val dy = y - tapY
        val distance = sqrt(dx * dx + dy * dy)
        if (distance <= nearestDistance) {
            nearestDistance = distance
            nearestIndex = index
        }
    }
    return nearestIndex.takeIf { it >= 0 }
}
