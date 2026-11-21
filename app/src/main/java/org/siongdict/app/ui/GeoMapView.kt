package org.siongdict.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siongdict.app.analysis.MapSite
import org.siongdict.app.analysis.PlanePoint
import org.siongdict.app.analysis.ProvinceShape
import org.siongdict.app.analysis.fitTransform
import org.siongdict.app.analysis.voronoiCells

/** 地圖四周留白（dp）。 */
private const val MAP_PADDING_DP = 12f

/** 站點半徑（dp）。 */
private const val POINT_RADIUS_DP = 4f

/** 選中站點半徑（dp）。 */
private const val SELECTED_RADIUS_DP = 7f

/** 點擊命中半徑（dp）。 */
private const val TAP_RADIUS_DP = 16f

private const val MAP_MIN_ZOOM = 0.6f
private const val MAP_MAX_ZOOM = 8f

/** 標籤上限：超過就不再畫，避免整張圖糊成一片。 */
private const val MAX_LABELS = 80

/**
 * 離線地圖畫布：省級底圖 + 泰森多邊形 + 方言點。
 *
 * 投影在 [fitTransform] 裡一次算好（經度 → x、Web Mercator 緯度 → y，等比置中）；
 * 手勢只改「縮放 + 平移」，底圖、多邊形、站點、標籤都畫在同一個變換下。
 * - 單指拖動平移、雙指捏合縮放（0.6–8 倍）、雙擊復原；
 * - 輕點站點選中（半徑 16dp），再點空白處取消。
 *
 * 視口數值與命中測試收在 [MapViewport]／[nearestSiteIndex]，標籤避讓收在 [placeLabels]，
 * 手勢循環只負責把每一幀事件折進狀態。
 */
@Composable
internal fun GeoMapView(
    sites: List<MapSite>,
    shapes: List<ProvinceShape>,
    clusterColors: List<Color>,
    selectedLang: String?,
    showLabels: Boolean,
    showVoronoi: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val paddingPx = with(density) { MAP_PADDING_DP.dp.toPx() }
    val pointRadius = with(density) { POINT_RADIUS_DP.dp.toPx() }
    val selectedRadius = with(density) { SELECTED_RADIUS_DP.dp.toPx() }
    val tapRadius = with(density) { TAP_RADIUS_DP.dp.toPx() }

    var viewport by remember(sites) { mutableStateOf(MapViewport()) }
    // 輕點命中要讀「手勢當下」的縮放/平移/選中值；放進 pointerInput 的 key 會讓手勢偵測
    // 在每一次縮放幀被重啟，因此用 rememberUpdatedState 傳值、key 只留站點。
    val currentViewport = rememberUpdatedState(viewport)
    val currentSelected = rememberUpdatedState(selectedLang)

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val transform = remember(widthPx, heightPx, sites, shapes) {
            fitTransform(sites, shapes, widthPx, heightPx, paddingPx)
        }
        val sitePositions = remember(transform, sites) {
            sites.map { transform.project(it.lon, it.lat) }
        }
        // 泰森多邊形只鋪在地圖內容框內（不是整塊畫布），否則內容框外的空白也會被切成
        // 貫穿畫布的長條，看起來像一堆豎線。
        val contentBounds = transform.contentBounds()
        val cells = remember(showVoronoi, sites, transform) {
            if (!showVoronoi || sites.isEmpty()) {
                emptyList()
            } else {
                voronoiCells(
                    sites.map { site ->
                        val point = transform.project(site.lon, site.lat)
                        PlanePoint(point.first, point.second)
                    },
                    contentBounds,
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(sites) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        viewport = viewport.transformed(
                            centroidX = centroid.x,
                            centroidY = centroid.y,
                            panDeltaX = pan.x,
                            panDeltaY = pan.y,
                            gestureZoom = gestureZoom,
                            minZoom = MAP_MIN_ZOOM,
                            maxZoom = MAP_MAX_ZOOM,
                        )
                    }
                }
                .pointerInput(sites, sitePositions) {
                    detectTapGestures(
                        onTap = { tap ->
                            val hit = nearestSiteIndex(
                                basePositions = sitePositions,
                                viewport = currentViewport.value,
                                tapX = tap.x,
                                tapY = tap.y,
                                radius = tapRadius,
                            )
                            val nearest = hit?.let { sites[it] }
                            onSelect(if (nearest?.lang == currentSelected.value) null else nearest?.lang)
                        },
                        onDoubleTap = {
                            viewport = MapViewport()
                        },
                    )
                },
        ) {
            translate(viewport.panX, viewport.panY) {
                scale(viewport.zoom, viewport.zoom, pivot = Offset.Zero) {
                    // ① 底圖：省級輪廓填色 + 描邊。
                    for (shape in shapes) {
                        for (polygon in shape.polygons) {
                            if (polygon.size < 3) continue
                            val path = Path()
                            polygon.forEachIndexed { index, point ->
                                val projected = transform.project(point.lon, point.lat)
                                if (index == 0) path.moveTo(projected.first, projected.second)
                                else path.lineTo(projected.first, projected.second)
                            }
                            path.close()
                            drawPath(path, color = surfaceVariant.copy(alpha = 0.35f))
                            drawPath(
                                path,
                                color = outline.copy(alpha = 0.5f),
                                style = Stroke(width = 0.8f),
                            )
                        }
                    }

                    // ② 泰森多邊形：類別色淡填 + 淡描邊，裁剪在地圖內容框內。
                    clipRect(
                        left = contentBounds.left,
                        top = contentBounds.top,
                        right = contentBounds.right,
                        bottom = contentBounds.bottom,
                    ) {
                        cells.forEachIndexed { index, polygon ->
                            if (polygon.size < 3) return@forEachIndexed
                            // 單元格與站點同序，顏色要按站點所屬類別取，而不是按索引取。
                            val color = clusterColors.getOrElse(sites[index].cluster) { Color.Gray }
                            val path = Path()
                            polygon.forEachIndexed { pointIndex, point ->
                                if (pointIndex == 0) path.moveTo(point.x, point.y)
                                else path.lineTo(point.x, point.y)
                            }
                            path.close()
                            drawPath(path, color = color.copy(alpha = 0.18f))
                            drawPath(
                                path,
                                color = color.copy(alpha = 0.35f),
                                style = Stroke(width = 1f),
                            )
                        }
                    }

                    // ③ 站點：先畫未選中的，再畫選中的（保證在最上層）。
                    sites.forEachIndexed { index, site ->
                        if (site.lang == selectedLang) return@forEachIndexed
                        val base = sitePositions[index]
                        val color = clusterColors.getOrElse(site.cluster) { Color.Gray }
                        drawCircle(color, radius = pointRadius, center = Offset(base.first, base.second))
                        drawCircle(
                            onSurface.copy(alpha = 0.5f),
                            radius = pointRadius,
                            center = Offset(base.first, base.second),
                            style = Stroke(width = 0.8f),
                        )
                    }
                    sites.forEachIndexed { index, site ->
                        if (site.lang != selectedLang) return@forEachIndexed
                        val base = sitePositions[index]
                        val color = clusterColors.getOrElse(site.cluster) { Color.Gray }
                        drawCircle(color, radius = selectedRadius, center = Offset(base.first, base.second))
                        drawCircle(
                            onSurface,
                            radius = selectedRadius,
                            center = Offset(base.first, base.second),
                            style = Stroke(width = 1.5f),
                        )
                    }

                    // ④ 標籤：按站點順序貪心避讓，選中站點強制顯示，上限 80 個。
                    if (showLabels) {
                        val measured = sites.mapIndexed { index, site ->
                            val base = sitePositions[index]
                            val layout = textMeasurer.measure(site.lang, labelStyle)
                            val height = layout.size.height.toFloat()
                            val topLeft = Offset(base.first + pointRadius + 2f, base.second - height / 2f)
                            Triple(
                                layout,
                                topLeft,
                                LabelRequest(
                                    left = topLeft.x,
                                    top = topLeft.y,
                                    width = layout.size.width.toFloat(),
                                    height = height,
                                    force = site.lang == selectedLang,
                                ),
                            )
                        }
                        val placed = placeLabels(
                            requests = measured.map { it.third },
                            maxLabels = MAX_LABELS,
                        )
                        for (placedIndex in placed) {
                            val (layout, topLeft, _) = measured[placedIndex]
                            drawText(
                                textLayoutResult = layout,
                                color = onSurface,
                                topLeft = topLeft,
                            )
                        }
                    }
                }
            }
        }
    }
}
