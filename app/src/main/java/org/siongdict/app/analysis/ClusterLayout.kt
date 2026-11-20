package org.siongdict.app.analysis

import kotlin.math.PI
import kotlin.math.acos
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

/** 向心力：把整体拉回立方体中心。 */
private const val CENTER = 0.02

/** 单次迭代的位移上限、步长缩放与冷却，保证数值稳定且收敛。 */
private const val MAX_STEP = 0.05
private const val STEP_SCALE = 0.5
private const val COOLING = 0.985
private const val MIN_STEP = 0.05

/**
 * 确定性三维力导向布局（纯 Kotlin，禁止引用 android.*）。
 *
 * 初始位置固定在半径 0.4、球心 (0.5, 0.5, 0.5) 的球面上，用 Fibonacci 球面均匀撒点
 * （第 i 个点：t = (i + 0.5) / n，φ = acos(1 − 2t)，θ = π(1 + √5)i），无随机数。
 * 之后每轮：
 * 1. O(n²) 斥力（1/d²）；
 * 2. 每个点连向「正相似度最高的 8 个邻点」作弹簧，权重取相似度 s = 1 - 2d；
 * 3. 向立方体中心 (0.5, 0.5, 0.5) 的向心力。
 *
 * 全程无随机数：同一距离矩阵永远得到同一组坐标。最后按三轴最大跨度等比缩放到 [0,1]³，
 * 保证点不越界且尽量铺满画布；n = 0 返回空列表，n = 1 返回 (0.5, 0.5, 0.5)，
 * 所有点完全重合（跨度为 0）时统一给 (0.5, 0.5, 0.5)。
 *
 * @param distances 对称距离方阵（对角 0），来自 [computeSimilarity]
 * @param iterations 迭代轮数，默认 200
 */
fun forceLayout3D(
    distances: Array<DoubleArray>,
    iterations: Int = 200,
): List<Triple<Float, Float, Float>> {
    val n = distances.size
    if (n == 0) return emptyList()
    if (n == 1) return listOf(Triple(0.5f, 0.5f, 0.5f))

    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    val zs = DoubleArray(n)
    val goldenAngle = PI * (1.0 + sqrt(5.0))
    for (i in 0 until n) {
        val t = (i + 0.5) / n
        val phi = acos(1.0 - 2.0 * t)
        val theta = goldenAngle * i
        xs[i] = 0.5 + 0.4 * sin(phi) * cos(theta)
        ys[i] = 0.5 + 0.4 * sin(phi) * sin(theta)
        zs[i] = 0.5 + 0.4 * cos(phi)
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
        val fz = DoubleArray(n)

        // 斥力：O(n²)。完全重合的点对按索引给确定性微小位移，避免除零。
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = xs[i] - xs[j]
                var dy = ys[i] - ys[j]
                var dz = zs[i] - zs[j]
                var squared = dx * dx + dy * dy + dz * dz
                if (squared < 1e-9) {
                    dx = 1e-3 * (i + 1)
                    dy = 1e-3 * (j + 1)
                    dz = 1e-3 * (i + j + 1)
                    squared = dx * dx + dy * dy + dz * dz
                }
                val distance = sqrt(squared)
                val force = REPULSION / squared
                val ux = dx / distance
                val uy = dy / distance
                val uz = dz / distance
                fx[i] += ux * force
                fy[i] += uy * force
                fz[i] += uz * force
                fx[j] -= ux * force
                fy[j] -= uy * force
                fz[j] -= uz * force
            }
        }

        // 弹簧：沿正相似度邻边吸引。
        for ((a, b, weight) in edges) {
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            val dz = zs[b] - zs[a]
            val distance = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
            val force = SPRING * weight * (distance - REST_LENGTH)
            val ux = dx / distance
            val uy = dy / distance
            val uz = dz / distance
            fx[a] += ux * force
            fy[a] += uy * force
            fz[a] += uz * force
            fx[b] -= ux * force
            fy[b] -= uy * force
            fz[b] -= uz * force
        }

        // 向心 + 雅可比式更新（全部基于旧坐标计算，与点数顺序无关）。
        val nextX = DoubleArray(n)
        val nextY = DoubleArray(n)
        val nextZ = DoubleArray(n)
        for (i in 0 until n) {
            val totalX = (fx[i] + CENTER * (0.5 - xs[i])).coerceIn(-MAX_STEP, MAX_STEP)
            val totalY = (fy[i] + CENTER * (0.5 - ys[i])).coerceIn(-MAX_STEP, MAX_STEP)
            val totalZ = (fz[i] + CENTER * (0.5 - zs[i])).coerceIn(-MAX_STEP, MAX_STEP)
            nextX[i] = (xs[i] + totalX * step * STEP_SCALE).coerceIn(0.0, 1.0)
            nextY[i] = (ys[i] + totalY * step * STEP_SCALE).coerceIn(0.0, 1.0)
            nextZ[i] = (zs[i] + totalZ * step * STEP_SCALE).coerceIn(0.0, 1.0)
        }
        for (i in 0 until n) {
            xs[i] = nextX[i]
            ys[i] = nextY[i]
            zs[i] = nextZ[i]
        }
        step = (step * COOLING).coerceAtLeast(MIN_STEP)
    }

    return normalizeToUnitCube(xs, ys, zs)
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

/** 等比缩放到 [0,1]³（保持相对形状）；跨度为 0 时全部给 0.5。 */
private fun normalizeToUnitCube(
    xs: DoubleArray,
    ys: DoubleArray,
    zs: DoubleArray,
): List<Triple<Float, Float, Float>> {
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    var minZ = Double.MAX_VALUE
    var maxZ = -Double.MAX_VALUE
    for (i in xs.indices) {
        minX = minOf(minX, xs[i])
        maxX = maxOf(maxX, xs[i])
        minY = minOf(minY, ys[i])
        maxY = maxOf(maxY, ys[i])
        minZ = minOf(minZ, zs[i])
        maxZ = maxOf(maxZ, zs[i])
    }
    val span = maxOf(maxX - minX, maxY - minY, maxZ - minZ)
    if (!span.isFinite() || span < 1e-9) {
        return List(xs.size) { Triple(0.5f, 0.5f, 0.5f) }
    }

    val scale = 1.0 / span
    val centerX = (minX + maxX) / 2.0
    val centerY = (minY + maxY) / 2.0
    val centerZ = (minZ + maxZ) / 2.0
    return xs.indices.map { i ->
        val x = 0.5 + (xs[i] - centerX) * scale
        val y = 0.5 + (ys[i] - centerY) * scale
        val z = 0.5 + (zs[i] - centerZ) * scale
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            Triple(0.5f, 0.5f, 0.5f)
        } else {
            Triple(
                x.toFloat().coerceIn(0f, 1f),
                y.toFloat().coerceIn(0f, 1f),
                z.toFloat().coerceIn(0f, 1f),
            )
        }
    }
}

/** 分群锚点到立方体中心的距离；决定类间在画布上的分离度。 */
private const val GROUP_ANCHOR_RADIUS = 0.35

/** 类内相对形状的收缩比例；1 保持原形状，0 全部叠到锚点。 */
private const val GROUP_SPREAD = 0.35

/**
 * 分群锚点布局（纯 Kotlin，禁止引用 android.*）。
 *
 * 与 [forceLayout3D] 分工：[forceLayout3D] 的坐标反映整体相似度结构，本函数把每一类搬到
 * 独立的球面锚点上，并保留类内相对形状（按 [spread] 收缩），于是切换分类数时点会明显
 * 「聚拢成团」，而不是原地不动。类号按升序去重后第 i 类锚点用 Fibonacci 球面均布
 * （t = (i + 0.5) / n，φ = acos(1 − 2t)，θ = π(1 + √5)i），半径 [anchorRadius]，球心
 * (0.5, 0.5, 0.5)，全程无随机数，同一输入永远同一输出。
 *
 * 边界：[labels] 为 null、长度不符、或只有一类时原样返回各点坐标（单类不塌缩到中心）；
 * 输出经 [normalizeToUnitCube] 归一到 [0,1]³。
 *
 * @param points 已算好力导向坐标的点，输出顺序与之一致
 * @param labels 每个点所属类号，长度须等于 points；null 表示不分组
 * @param anchorRadius 锚点到立方体中心的距离
 * @param spread 类内相对形状的收缩比例
 */
fun groupedLayout3D(
    points: List<ClusterPoint>,
    labels: IntArray?,
    anchorRadius: Float = GROUP_ANCHOR_RADIUS.toFloat(),
    spread: Float = GROUP_SPREAD.toFloat(),
): List<Triple<Float, Float, Float>> {
    if (points.isEmpty()) return emptyList()
    val identity = points.map { Triple(it.x, it.y, it.z) }
    if (labels == null || labels.size != points.size) return identity
    val clusterIds = labels.toSortedSet().toList()
    if (clusterIds.size <= 1) return identity

    val clusterCount = clusterIds.size
    val indexOf = HashMap<Int, Int>(clusterCount)
    clusterIds.forEachIndexed { index, id -> indexOf[id] = index }

    val anchors = Array(clusterCount) { index ->
        val t = (index + 0.5) / clusterCount
        val phi = acos(1.0 - 2.0 * t)
        val theta = PI * (1.0 + sqrt(5.0)) * index
        Triple(
            0.5 + anchorRadius * (sin(phi) * cos(theta)).toFloat(),
            0.5 + anchorRadius * (sin(phi) * sin(theta)).toFloat(),
            0.5 + anchorRadius * cos(phi).toFloat(),
        )
    }

    // 每类质心取力导向坐标，作为类内形状的原点。
    val sumX = DoubleArray(clusterCount)
    val sumY = DoubleArray(clusterCount)
    val sumZ = DoubleArray(clusterCount)
    val sizes = IntArray(clusterCount)
    for (index in points.indices) {
        val cluster = indexOf.getValue(labels[index])
        sumX[cluster] += points[index].x.toDouble()
        sumY[cluster] += points[index].y.toDouble()
        sumZ[cluster] += points[index].z.toDouble()
        sizes[cluster]++
    }
    val centroidX = DoubleArray(clusterCount) { sumX[it] / sizes[it] }
    val centroidY = DoubleArray(clusterCount) { sumY[it] / sizes[it] }
    val centroidZ = DoubleArray(clusterCount) { sumZ[it] / sizes[it] }

    val xs = DoubleArray(points.size)
    val ys = DoubleArray(points.size)
    val zs = DoubleArray(points.size)
    for (index in points.indices) {
        val cluster = indexOf.getValue(labels[index])
        val anchor = anchors[cluster]
        xs[index] = (anchor.first + (points[index].x - centroidX[cluster]) * spread).toDouble()
        ys[index] = (anchor.second + (points[index].y - centroidY[cluster]) * spread).toDouble()
        zs[index] = (anchor.third + (points[index].z - centroidZ[cluster]) * spread).toDouble()
    }
    return normalizeToUnitCube(xs, ys, zs)
}

/**
 * 两套布局之间的线性插值（纯 Kotlin），用于过渡动画。
 *
 * key 一般取方言点名；只在一侧出现的 key 取存在的那一侧，两侧都缺时给中心点。
 * [t] 会夹到 [0, 1]。
 */
fun lerpLayout(
    from: Map<String, Triple<Float, Float, Float>>,
    to: Map<String, Triple<Float, Float, Float>>,
    t: Float,
): Map<String, Triple<Float, Float, Float>> {
    val ratio = t.coerceIn(0f, 1f)
    val keys = LinkedHashSet<String>(to.size + from.size)
    keys.addAll(to.keys)
    keys.addAll(from.keys)
    val result = LinkedHashMap<String, Triple<Float, Float, Float>>(keys.size)
    for (key in keys) {
        val start = from[key]
        val end = to[key]
        result[key] = when {
            start == null && end == null -> Triple(0.5f, 0.5f, 0.5f)
            start == null -> end!!
            end == null -> start
            else -> Triple(
                start.first + (end.first - start.first) * ratio,
                start.second + (end.second - start.second) * ratio,
                start.third + (end.third - start.third) * ratio,
            )
        }
    }
    return result
}
