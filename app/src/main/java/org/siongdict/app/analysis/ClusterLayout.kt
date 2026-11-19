package org.siongdict.app.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 每个点最多连向的正相似度邻点数。 */
private const val MAX_NEIGHBORS = 8

/** 斥力系数（库仑式 1/d²）。 */
private const val REPULSION = 0.0025

/** 弹簧系数与静止长度；权重取相似度，相似度越高拉得越紧。 */
private const val SPRING = 0.8
private const val REST_LENGTH = 0.32

/** 向心力：把整体拉回画布中心。 */
private const val CENTER = 0.02

/** 单次迭代的位移上限、步长缩放与冷却，保证数值稳定且收敛。 */
private const val MAX_STEP = 0.05
private const val STEP_SCALE = 0.5
private const val COOLING = 0.985
private const val MIN_STEP = 0.05

/**
 * 确定性力导向布局（纯 Kotlin，禁止引用 android.*）。
 *
 * 初始位置固定在半径 0.4、圆心 (0.5, 0.5) 的圆周上（第 i 个点角度 2πi/n），之后每轮：
 * 1. O(n²) 斥力（1/d²）；
 * 2. 每个点连向「正相似度最高的 8 个邻点」作弹簧，权重取相似度 s = 1 - 2d；
 * 3. 向画布中心 (0.5, 0.5) 的向心力。
 *
 * 全程无随机数：同一距离矩阵永远得到同一组坐标。最后按整体跨度等比缩放到 [0,1]，
 * 保证点不越界且尽量铺满画布；n = 0 返回空列表，n = 1 返回 (0.5, 0.5)，
 * 所有点完全重合（跨度为 0）时统一给 (0.5, 0.5)。
 *
 * @param distances 对称距离方阵（对角 0），来自 [computeSimilarity]
 * @param iterations 迭代轮数，默认 200
 */
fun forceLayout(
    distances: Array<DoubleArray>,
    iterations: Int = 200,
): List<Pair<Float, Float>> {
    val n = distances.size
    if (n == 0) return emptyList()
    if (n == 1) return listOf(0.5f to 0.5f)

    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    for (i in 0 until n) {
        val angle = 2.0 * PI * i / n
        xs[i] = 0.5 + 0.4 * cos(angle)
        ys[i] = 0.5 + 0.4 * sin(angle)
    }

    // 相似度 s = 1 - 2d，与 Similarity.kt 的 d = (1 - s) / 2 互逆。
    val similarity = Array(n) { i ->
        DoubleArray(n) { j -> (1.0 - 2.0 * distances[i][j]).coerceIn(-1.0, 1.0) }
    }
    val edges = buildSpringEdges(similarity)

    var step = 1.0
    for (iteration in 0 until iterations) {
        val fx = DoubleArray(n)
        val fy = DoubleArray(n)

        // 斥力：O(n²)。完全重合的点对按索引给确定性微小位移，避免除零。
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = xs[i] - xs[j]
                var dy = ys[i] - ys[j]
                var squared = dx * dx + dy * dy
                if (squared < 1e-9) {
                    dx = 1e-3 * (i + 1)
                    dy = 1e-3 * (j + 1)
                    squared = dx * dx + dy * dy
                }
                val distance = sqrt(squared)
                val force = REPULSION / squared
                val ux = dx / distance
                val uy = dy / distance
                fx[i] += ux * force
                fy[i] += uy * force
                fx[j] -= ux * force
                fy[j] -= uy * force
            }
        }

        // 弹簧：沿正相似度邻边吸引。
        for ((a, b, weight) in edges) {
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
            val force = SPRING * weight * (distance - REST_LENGTH)
            val ux = dx / distance
            val uy = dy / distance
            fx[a] += ux * force
            fy[a] += uy * force
            fx[b] -= ux * force
            fy[b] -= uy * force
        }

        // 向心 + 雅可比式更新（全部基于旧坐标计算，与点数顺序无关）。
        val nextX = DoubleArray(n)
        val nextY = DoubleArray(n)
        for (i in 0 until n) {
            val totalX = (fx[i] + CENTER * (0.5 - xs[i])).coerceIn(-MAX_STEP, MAX_STEP)
            val totalY = (fy[i] + CENTER * (0.5 - ys[i])).coerceIn(-MAX_STEP, MAX_STEP)
            nextX[i] = (xs[i] + totalX * step * STEP_SCALE).coerceIn(0.0, 1.0)
            nextY[i] = (ys[i] + totalY * step * STEP_SCALE).coerceIn(0.0, 1.0)
        }
        xs.indices.forEach { i ->
            xs[i] = nextX[i]
            ys[i] = nextY[i]
        }
        step = (step * COOLING).coerceAtLeast(MIN_STEP)
    }

    return normalizeToUnitSquare(xs, ys)
}

/** 边集合：每个点取正相似度最高的 8 个邻点，按无向边去重，权重取较大者。 */
private fun buildSpringEdges(similarity: Array<DoubleArray>): List<Triple<Int, Int, Double>> {
    val n = similarity.size
    val weights = LinkedHashMap<Long, Double>()
    for (i in 0 until n) {
        val neighbors = (0 until n)
            .filter { j -> j != i && similarity[i][j] > 0.0 }
            .sortedWith(compareByDescending<Int> { j -> similarity[i][j] }.thenBy { it })
            .take(MAX_NEIGHBORS)
        for (j in neighbors) {
            val a = minOf(i, j)
            val b = maxOf(i, j)
            val key = a.toLong() * n + b
            val weight = similarity[a][b].coerceAtLeast(0.0)
            val previous = weights[key]
            if (previous == null || weight > previous) weights[key] = weight
        }
    }
    return weights.map { (key, weight) ->
        Triple((key / n).toInt(), (key % n).toInt(), weight)
    }
}

/** 等比缩放到 [0,1]（保持相对形状）；跨度为 0 时全部给 0.5。 */
private fun normalizeToUnitSquare(xs: DoubleArray, ys: DoubleArray): List<Pair<Float, Float>> {
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (i in xs.indices) {
        minX = minOf(minX, xs[i])
        maxX = maxOf(maxX, xs[i])
        minY = minOf(minY, ys[i])
        maxY = maxOf(maxY, ys[i])
    }
    val span = maxOf(maxX - minX, maxY - minY)
    if (!span.isFinite() || span < 1e-9) return List(xs.size) { 0.5f to 0.5f }

    val scale = 1.0 / span
    val centerX = (minX + maxX) / 2.0
    val centerY = (minY + maxY) / 2.0
    return xs.indices.map { i ->
        val x = 0.5 + (xs[i] - centerX) * scale
        val y = 0.5 + (ys[i] - centerY) * scale
        if (!x.isFinite() || !y.isFinite()) {
            0.5f to 0.5f
        } else {
            x.toFloat().coerceIn(0f, 1f) to y.toFloat().coerceIn(0f, 1f)
        }
    }
}
