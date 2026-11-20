package org.siongdict.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import org.siongdict.app.analysis.groupedLayout3D
import org.siongdict.app.analysis.lerpLayout
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

/** 单指旋转灵敏度：每像素多少度。 */
private const val ROTATE_DEGREES_PER_PX = 0.4f

/** 双击判定窗口（毫秒）。 */
private const val DOUBLE_TAP_WINDOW_MS = 300L

/** 轻点判定窗口（毫秒）：超过则视为长按，不选中。 */
private const val TAP_WINDOW_MS = 400L

/** 标签数量上限（点数多时按深度取最近的一批）。 */
private const val MAX_LABELS = 80

/** 点数不超过该值时标签全显示、不做去重叠。 */
private const val ALL_LABELS_THRESHOLD = 40

/** 布局过渡动画时长（毫秒）：切换分类数或重算后，点从旧位置滑向新分群。 */
private const val LAYOUT_ANIMATION_MS = 700

/**
 * 聚类三维力导向画布。
 *
 * 画序：各类凸包（投影后的 2D 形状，调色盘填色 0.10、虚线 0.55）→ 每点前 2 条正相似边
 * → 节点（按深度从远到近画，音典颜色，双色用线性渐变，选中点加白圈）→ 标签。
 *
 * 手势：**单指拖动旋转**（左右改 yaw、上下改 pitch，pitch 夹在 ±80°）、
 * **双指捏合缩放 0.6–4 并拖动平移**、**轻点选中 16dp 内最近节点**、**双击复位视角**。
 * 标签默认显示：点数 ≤ 40 全显；否则按深度从近到远贪心去重叠，上限 80 个；选中点始终显示。
 */
@Composable
internal fun ClusterMap(
    result: ClusterAnalysisResult,
    partition: ClusterPartition?,
    edges: List<ClusterEdge>,
    selectedLang: String?,
    highlightedCluster: Int?,
    showLabels: Boolean,
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

    // 结果变化时重置视角，避免沿用上一批点的视窗。
    val points = result.points
    // 分群锚点布局：类间按 Fibonacci 球面均布、类内保留力导向形状（见 groupedLayout3D）。
    // 切换分类数或重算时用 700ms 过渡，点会明显「聚拢成团」而不是原地不动。
    val labels = remember(points, partition) { clusterLabelsOf(points, partition) }
    val targetLayout = remember(points, labels) {
        groupedLayout3D(points, labels)
            .mapIndexed { index, position -> points[index].lang to position }
            .toMap()
    }
    var displayed by remember(points) {
        mutableStateOf<Map<String, Triple<Float, Float, Float>>?>(null)
    }
    LaunchedEffect(targetLayout) {
        val start = displayed ?: points.associate { it.lang to Triple(it.x, it.y, it.z) }
        if (start == targetLayout) {
            displayed = targetLayout
            return@LaunchedEffect
        }
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = LAYOUT_ANIMATION_MS, easing = FastOutSlowInEasing),
        ) { value, _ ->
            displayed = lerpLayout(start, targetLayout, value)
        }
    }
    val animatedPoints = remember(points, displayed) {
        val layout = displayed
        if (layout == null) {
            points
        } else {
            points.map { point ->
                val position = layout[point.lang]
                if (position == null) {
                    point
                } else {
                    point.copy(x = position.first, y = position.second, z = position.third)
                }
            }
        }
    }
    val currentPointsState = rememberUpdatedState(animatedPoints)
    var yaw by remember(points) { mutableFloatStateOf(DEFAULT_YAW_DEG) }
    var pitch by remember(points) { mutableFloatStateOf(DEFAULT_PITCH_DEG) }
    var zoom by remember(points) { mutableFloatStateOf(1f) }
    var pan by remember(points) { mutableStateOf(Offset.Zero) }
    var lastTapAt by remember(points) { mutableLongStateOf(0L) }

    val tapRadius = with(density) { 16.dp.toPx() }
    val mapPadding = with(density) { 8.dp.toPx() }

    fun project(point: ClusterPoint, canvas: Size, currentYaw: Float, currentPitch: Float): ProjectedPoint =
        projectPoint(
            x = point.x,
            y = point.y,
            z = point.z,
            yawDeg = currentYaw,
            pitchDeg = currentPitch,
            width = canvas.width,
            height = canvas.height,
            padding = mapPadding,
            zoom = zoom,
            panX = pan.x,
            panY = pan.y,
        )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startTime = System.currentTimeMillis()
                        var lastCentroid = down.position
                        var moved = false
                        var pointerCount = 1
                        var previousPointerCount = 1
                        var pinchStartSpan = -1f
                        var pinchStartZoom = zoom

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            pointerCount = pressed.size
                            var sum = Offset.Zero
                            for (change in pressed) sum += change.position
                            val centroid = sum / pointerCount.toFloat()
                            if (!moved && (centroid - down.position).getDistance() > touchSlop) {
                                moved = true
                            }
                            if (pointerCount > 1) moved = true

                            // 手指数量变化的这一帧质心会跳一下（例如双指抬起一指），
                            // 直接把基准重置到当前质心，避免视角/平移被拽飞。
                            if (pointerCount != previousPointerCount) {
                                previousPointerCount = pointerCount
                                lastCentroid = centroid
                                pinchStartSpan = -1f
                            }

                            if (moved) {
                                val delta = centroid - lastCentroid
                                if (pointerCount == 1) {
                                    yaw += delta.x * ROTATE_DEGREES_PER_PX
                                    pitch = (pitch - delta.y * ROTATE_DEGREES_PER_PX)
                                        .coerceIn(-MAX_PITCH_DEG, MAX_PITCH_DEG)
                                } else {
                                    val span = if (pressed.size >= 2) {
                                        (pressed[0].position - pressed[1].position).getDistance()
                                    } else {
                                        0f
                                    }
                                    if (span > 0f) {
                                        if (pinchStartSpan <= 0f) {
                                            pinchStartSpan = span
                                            pinchStartZoom = zoom
                                        }
                                        zoom = (pinchStartZoom * (span / pinchStartSpan))
                                            .coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    }
                                    pan += delta
                                }
                                event.changes.forEach { it.consume() }
                            }
                            lastCentroid = centroid
                        }

                        val now = System.currentTimeMillis()
                        val duration = now - startTime
                        if (!moved && duration <= TAP_WINDOW_MS) {
                            if (now - lastTapAt <= DOUBLE_TAP_WINDOW_MS) {
                                // 双击：复位视角。
                                yaw = DEFAULT_YAW_DEG
                                pitch = DEFAULT_PITCH_DEG
                                zoom = 1f
                                pan = Offset.Zero
                                lastTapAt = 0L
                            } else {
                                lastTapAt = now
                                val canvas = Size(size.width.toFloat(), size.height.toFloat())
                                var bestIndex = -1
                                var bestDistance = Float.MAX_VALUE
                                for ((index, point) in currentPointsState.value.withIndex()) {
                                    val projected = project(point, canvas, yaw, pitch)
                                    val distance = (Offset(projected.x, projected.y) - down.position)
                                        .getDistance()
                                    if (distance < bestDistance) {
                                        bestDistance = distance
                                        bestIndex = index
                                    }
                                }
                                onSelect(
                                    if (bestIndex >= 0 && bestDistance <= tapRadius) {
                                        currentPointsState.value[bestIndex].lang
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                }
        ) {
            val drawPoints = animatedPoints
            val count = drawPoints.size
            if (count == 0) return@Canvas
            val canvas = size
            val projected = drawPoints.map { project(it, canvas, yaw, pitch) }
            val baseRadius = sqrt(size.width * size.height / count)
                .coerceIn(2.5.dp.toPx(), 6.dp.toPx())
            val radiusOf = { index: Int ->
                baseRadius * depthRadiusScale(projected[index].depth)
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
            val selectedIndex = drawPoints.indexOfFirst { it.lang == selectedLang }

            // 1) 各类凸包：高亮类别填色更深、描边更实。
            partition?.clusters?.forEachIndexed { index, cluster ->
                if (cluster.size < 3) return@forEachIndexed
                val hull = convexHull(cluster.mapNotNull { projected.getOrNull(it)?.let { p -> Offset(p.x, p.y) } })
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

            // 2) 正相似边：权重越高越明显，深度越远越淡。
            for (edge in edges) {
                val from = projected.getOrNull(edge.a) ?: continue
                val to = projected.getOrNull(edge.b) ?: continue
                val depthFactor = depthRadiusScale((from.depth + to.depth) / 2f) / 1.35f
                val alpha = ((0.10f + 0.30f * edge.weight.coerceIn(0f, 1f)) * depthFactor)
                    .coerceIn(0f, 0.5f)
                drawLine(
                    color = edgeColor.copy(alpha = alpha),
                    start = Offset(from.x, from.y),
                    end = Offset(to.x, to.y),
                    strokeWidth = 0.8.dp.toPx(),
                )
            }

            // 3) 节点：按深度从远到近画（近的盖住远的）。
            val drawOrder = drawPoints.indices.sortedBy { projected[it].depth }
            for (index in drawOrder) {
                val point = drawPoints[index]
                val center = Offset(projected[index].x, projected[index].y)
                val nodeRadius = radiusOf(index)
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

            // 4) 标签。
            if (showLabels) {
                val ordered = drawPoints.indices.sortedByDescending { projected[it].depth }
                val placed = mutableListOf<Rect>()
                var drawn = 0
                for (index in ordered) {
                    if (count > ALL_LABELS_THRESHOLD && drawn >= MAX_LABELS) break
                    val anchor = Offset(
                        projected[index].x,
                        projected[index].y + radiusOf(index) + 2.dp.toPx(),
                    )
                    val layout = textMeasurer.measure(drawPoints[index].lang, labelStyle)
                    val rect = Rect(
                        offset = Offset(anchor.x - layout.size.width / 2f, anchor.y),
                        size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
                    ).inflate(1.dp.toPx())
                    if (count > ALL_LABELS_THRESHOLD && placed.any { it.overlaps(rect) }) continue
                    placed.add(rect)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y),
                    )
                    drawn++
                }
            } else {
                // 隐藏名称时：类别质心标编号，选中点标名字（保持旧版行为）。
                partition?.clusters?.forEachIndexed { index, cluster ->
                    val clusterPoints = cluster.mapNotNull { projected.getOrNull(it) }
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
            }
            if (selectedIndex >= 0) {
                drawNodeLabel(
                    textMeasurer = textMeasurer,
                    text = drawPoints[selectedIndex].lang,
                    anchor = Offset(
                        projected[selectedIndex].x,
                        projected[selectedIndex].y + radiusOf(selectedIndex) + 2.dp.toPx(),
                    ),
                    style = labelStyle,
                )
            }
        }
    }
}

/** 把分区展开成按点序的类号；分区缺失或覆盖不全时返回 null（不聚拢）。 */
private fun clusterLabelsOf(points: List<ClusterPoint>, partition: ClusterPartition?): IntArray? {
    if (partition == null || partition.clusters.isEmpty()) return null
    val labels = IntArray(points.size) { -1 }
    partition.clusters.forEachIndexed { clusterIndex, members ->
        for (index in members) {
            if (index in labels.indices) labels[index] = clusterIndex
        }
    }
    return if (labels.any { it < 0 }) null else labels
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
