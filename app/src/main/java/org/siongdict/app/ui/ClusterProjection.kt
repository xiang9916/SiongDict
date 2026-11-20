package org.siongdict.app.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D 画布的透视投影（纯 Kotlin，禁止引用 android.*，便于 JVM 单测）。
 *
 * 相机在 z 轴正方向看向原点：先把归一化坐标平移到立方体中心，再绕 Y 轴转 yaw、
 * 绕 X 轴转 pitch，最后做透视除法 scale = focal / (distance − z)。
 * 由于归一化坐标每个分量都在 [0,1]，旋转后 z ∈ [−0.87, 0.87]，
 * 分母恒 ≥ distance − 0.87 = 1.93，不会除零。
 */

/** 相机到立方体中心的距离（归一化单位）。 */
internal const val CAMERA_DISTANCE: Float = 2.8f

/** 焦距：决定透视强弱与画布占比。 */
internal const val CAMERA_FOCAL: Float = 1.6f

/** 透视分母的下限（防御性，正常数据不会触及）。 */
private const val MIN_CAMERA_DENOM: Float = 0.5f

/** 默认视角：略微俯视 + 侧转，一眼能看出立体感。 */
internal const val DEFAULT_YAW_DEG: Float = -24f
internal const val DEFAULT_PITCH_DEG: Float = 18f

/** 俯仰限制：避免翻到背面导致上下颠倒。 */
internal const val MAX_PITCH_DEG: Float = 80f

/** 缩放范围。 */
internal const val MIN_ZOOM: Float = 0.6f
internal const val MAX_ZOOM: Float = 4f

/** 投影结果：屏幕坐标、相机空间深度（越大越近）与透视缩放系数。 */
internal data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val depth: Float,
    val scale: Float,
)

/**
 * 把一个归一化 3D 点投影到画布像素坐标。
 *
 * @param width 画布宽度（px）
 * @param height 画布高度（px）
 * @param padding 画布内边距（px）
 * @param zoom 用户缩放
 * @param panX／[panY] 用户平移（px）
 */
internal fun projectPoint(
    x: Float,
    y: Float,
    z: Float,
    yawDeg: Float,
    pitchDeg: Float,
    width: Float,
    height: Float,
    padding: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
): ProjectedPoint {
    val yaw = yawDeg * PI / 180.0
    val pitch = pitchDeg * PI / 180.0
    val cx = (x - 0.5f).toDouble()
    val cy = (y - 0.5f).toDouble()
    val cz = (z - 0.5f).toDouble()

    // 绕 Y 轴（先）再绕 X 轴（后）。
    val x1 = cx * cos(yaw) + cz * sin(yaw)
    val z1 = -cx * sin(yaw) + cz * cos(yaw)
    val y2 = cy * cos(pitch) - z1 * sin(pitch)
    val z2 = cy * sin(pitch) + z1 * cos(pitch)

    val denominator = (CAMERA_DISTANCE - z2.toFloat()).coerceAtLeast(MIN_CAMERA_DENOM)
    val scale = CAMERA_FOCAL / denominator
    val radius = minOf(width, height) / 2f - padding
    return ProjectedPoint(
        x = width / 2f + x1.toFloat() * scale * radius * zoom + panX,
        y = height / 2f + y2.toFloat() * scale * radius * zoom + panY,
        depth = z2.toFloat(),
        scale = scale,
    )
}

/** 深度 → 节点半径系数：近大远小，但幅度收敛，避免远处点看不见。 */
internal fun depthRadiusScale(depth: Float): Float =
    (0.75f + 0.5f * ((depth + 0.9f) / 1.8f)).coerceIn(0.6f, 1.35f)
