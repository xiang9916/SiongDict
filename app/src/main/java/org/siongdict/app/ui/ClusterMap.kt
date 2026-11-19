package org.siongdict.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siongdict.app.analysis.ClusterAnalysisResult
import org.siongdict.app.analysis.ClusterPartition
import org.siongdict.app.analysis.ClusterPoint
import org.siongdict.app.analysis.DialectSimilarity
import kotlin.math.sqrt

/** 画布上的一条正相似边：两端是 ClusterAnalysisResult.points 的下标。 */
data class ClusterEdge(val a: Int, val b: Int, val weight: Float)

/**
 * 从相似度矩阵挑出「每个点的前 [perPoint] 条正相似边」（无向去重，权重取较大者）。
 * 纯函数，输出顺序由插入顺序固定，方便测试与稳定绘制。
 */
internal fun buildClusterEdges(similarity: DialectSimilarity, perPoint: Int = 2): List<ClusterEdge> {
    val n = similarity.langs.size
    if (n < 2 || perPoint < 1) return emptyList()
    val weights = LinkedHashMap<Long, Float>()
    for (i in 0 until n) {
        val neighbors = (0 until n)
            .filter { j -> j != i && similarity.scores[i][j] > 0.0 }
            .sortedWith(compareByDescending<Int> { j -> similarity.scores[i][j] }.thenBy { it })
            .take(perPoint)
        for (j in neighbors) {
            val a = minOf(i, j)
            val b = maxOf(i, j)
            val key = a.toLong() * n + b
            val weight = similarity.scores[a][b].toFloat()
            val previous = weights[key]
            if (previous == null || weight > previous) weights[key] = weight
        }
    }
    return weights.map { (key, weight) -> ClusterEdge((key / n).toInt(), (key % n).toInt(), weight) }
}

/**
 * 聚类力导向画布。
 *
 * 画序：各类凸包（调色盘填色 0.10、虚线 0.55）→ 每点前 2 条正相似边 → 节点
 * （音典颜色，双色用线性渐变）→ 标签（点数 <= 40 显示全部語言名，否则只显示选中点与类别编号）。
 *
 * 手势：双指缩放 1..8、单指平移（缩放以画布中心为基准）、点选 16dp 内的最近节点，
 * 点空白处取消选中。所有坐标都从 ClusterPoint.x/y 的 [0,1] 归一化空间映射到画布像素。
 */
@Composable
internal fun ClusterMap(
    result: ClusterAnalysisResult,
    partition: ClusterPartition?,
    edges: List<ClusterEdge>,
    selectedLang: String?,
    highlightedCluster: Int?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface)
    val clusterLabelStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )

    // 结果变化时重置缩放/平移，避免沿用上一批点的视窗。
    val points = result.points
    var scale by remember(points) { mutableFloatStateOf(1f) }
    var pan by remember(points) { mutableStateOf(Offset.Zero) }
    val currentScale by rememberUpdatedState(scale)
    val currentPan by rememberUpdatedState(pan)
    val currentPoints by rememberUpdatedState(points)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val tapRadius = with(density) { 16.dp.toPx() }
    val mapPadding = with(density) { 8.dp.toPx() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                        var bestIndex = -1
                        var bestDistance = Float.MAX_VALUE
                        currentPoints.forEachIndexed { index, point ->
                            val center = toScreen(point, canvasSize, mapPadding, currentScale, currentPan)
                            val distance = (center - tap).getDistance()
                            if (distance < bestDistance) {
                                bestDistance = distance
                                bestIndex = index
                            }
                        }
                        val lang = if (bestIndex >= 0 && bestDistance <= tapRadius) {
                            currentPoints[bestIndex].lang
                        } else {
                            null
                        }
                        currentOnSelect(lang)
                    }
                }
                .pointerInput(points) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 8f)
                        // 缩回原始大小时同时把平移归零，避免用户迷路。
                        pan = if (next <= 1f) Offset.Zero else pan + panChange
                        scale = next
                    }
                }
        ) {
            val count = points.size
            if (count == 0) return@Canvas
            val screenPoints = points.map { toScreen(it, size, mapPadding, scale, pan) }
            val nodeRadius = sqrt(size.width * size.height / count)
                .coerceIn(2.5.dp.toPx(), 6.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
            val selectedIndex = points.indexOfFirst { it.lang == selectedLang }

            // 1) 各类凸包：高亮类别填色更深、描边更实。
            partition?.clusters?.forEachIndexed { index, cluster ->
                if (cluster.size < 3) return@forEachIndexed
                val hull = convexHull(cluster.mapNotNull { screenPoints.getOrNull(it) })
                if (hull.size < 3) return@forEachIndexed
                val path = Path().apply {
                    moveTo(hull.first().x, hull.first().y)
                    hull.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                val color = Color(paletteColor(index))
                val focus = highlightedCluster
                val fillAlpha = when {
                    focus == index -> 0.20f
                    focus == null -> 0.10f
                    else -> 0.04f
                }
                val strokeAlpha = when {
                    focus == index -> 0.90f
                    focus == null -> 0.55f
                    else -> 0.25f
                }
                drawPath(path, color = color.copy(alpha = fillAlpha), style = Fill)
                drawPath(
                    path,
                    color = color.copy(alpha = strokeAlpha),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                )
            }

            // 2) 正相似边：权重越高越明显。
            for (edge in edges) {
                val from = screenPoints.getOrNull(edge.a) ?: continue
                val to = screenPoints.getOrNull(edge.b) ?: continue
                val alpha = (0.12f + 0.38f * edge.weight.coerceIn(0f, 1f)).coerceIn(0f, 0.5f)
                drawLine(
                    color = edgeColor.copy(alpha = alpha),
                    start = from,
                    end = to,
                    strokeWidth = 0.8.dp.toPx(),
                )
            }

            // 3) 节点：音典颜色；双色用线性渐变；选中点加白圈。
            points.forEachIndexed { index, point ->
                val center = screenPoints[index]
                val colors = parseColorSpec(point.colorHex).map { Color(it) }
                if (colors.size >= 2) {
                    drawCircle(
                        brush = Brush.linearGradient(
                            colors = colors,
                            start = center - Offset(nodeRadius, 0f),
                            end = center + Offset(nodeRadius, 0f),
                        ),
                        radius = nodeRadius,
                        center = center,
                    )
                } else {
                    drawCircle(color = colors.first(), radius = nodeRadius, center = center)
                }
                if (index == selectedIndex) {
                    drawCircle(
                        color = Color.White,
                        radius = nodeRadius + 1.5.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        radius = nodeRadius + 2.5.dp.toPx(),
                        center = center,
                        style = Stroke(width = 0.8.dp.toPx()),
                    )
                }
            }

            // 4) 标签：点数少时全显示；否则只在类别质心标编号与选中点标名字。
            if (count <= 40) {
                points.forEachIndexed { index, point ->
                    drawNodeLabel(
                        textMeasurer = textMeasurer,
                        text = point.lang,
                        anchor = screenPoints[index] + Offset(0f, nodeRadius + 2.dp.toPx()),
                        style = labelStyle,
                    )
                }
            } else {
                partition?.clusters?.forEachIndexed { index, cluster ->
                    val clusterPoints = cluster.mapNotNull { screenPoints.getOrNull(it) }
                    if (clusterPoints.isEmpty()) return@forEachIndexed
                    var sumX = 0f
                    var sumY = 0f
                    for (point in clusterPoints) {
                        sumX += point.x
                        sumY += point.y
                    }
                    val centroid = Offset(sumX / clusterPoints.size, sumY / clusterPoints.size)
                    drawNodeLabel(textMeasurer, (index + 1).toString(), centroid, clusterLabelStyle)
                }
                if (selectedIndex >= 0) {
                    drawNodeLabel(
                        textMeasurer = textMeasurer,
                        text = points[selectedIndex].lang,
                        anchor = screenPoints[selectedIndex] + Offset(0f, nodeRadius + 2.dp.toPx()),
                        style = labelStyle,
                    )
                }
            }
        }
    }
}

/** 归一化坐标 → 画布像素：先按 padding 铺满画布，再以画布中心缩放并平移。 */
private fun toScreen(
    point: ClusterPoint,
    size: Size,
    padding: Float,
    zoom: Float,
    pan: Offset,
): Offset {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val baseX = padding + point.x.coerceIn(0f, 1f) * (size.width - 2f * padding)
    val baseY = padding + point.y.coerceIn(0f, 1f) * (size.height - 2f * padding)
    return Offset(
        centerX + (baseX - centerX) * zoom + pan.x,
        centerY + (baseY - centerY) * zoom + pan.y,
    )
}

/** 在 anchor 处居中画一段文字（水平居中、纵向从 anchor.y 开始）。 */
private fun DrawScope.drawNodeLabel(
    textMeasurer: TextMeasurer,
    text: String,
    anchor: Offset,
    style: TextStyle,
) {
    val layout = textMeasurer.measure(text, style)
    drawText(layout, topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y))
}

/** 单调链凸包：输入屏幕坐标，返回逆时针顶点（剔除共线点）；不足 3 点原样返回。 */
internal fun convexHull(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(origin: Offset, a: Offset, b: Offset): Float =
        (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)

    val lower = mutableListOf<Offset>()
    for (point in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), point) <= 0f) {
            lower.removeAt(lower.size - 1)
        }
        lower.add(point)
    }
    val upper = mutableListOf<Offset>()
    for (point in sorted.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), point) <= 0f) {
            upper.removeAt(upper.size - 1)
        }
        upper.add(point)
    }
    return lower.dropLast(1) + upper.dropLast(1)
}
