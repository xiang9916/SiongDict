package org.siongdict.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3D 画布透视投影的纯函数测试（不依赖 Compose 运行时）。 */
class ClusterProjectionTest {

    @Test
    fun centerProjectsToCanvasCenter() {
        val point = projectPoint(
            x = 0.5f, y = 0.5f, z = 0.5f,
            yawDeg = 0f, pitchDeg = 0f,
            width = 1000f, height = 600f, padding = 0f,
            zoom = 1f, panX = 0f, panY = 0f,
        )
        assertEquals(500f, point.x, 1e-3f)
        assertEquals(300f, point.y, 1e-3f)
        assertEquals(0f, point.depth, 1e-4f)
        assertEquals(CAMERA_FOCAL / CAMERA_DISTANCE, point.scale, 1e-4f)
    }

    @Test
    fun zoomScalesOffsetFromCenterOnly() {
        val normal = projectPoint(0.7f, 0.5f, 0.5f, 0f, 0f, 1000f, 600f, 0f, 1f, 0f, 0f)
        val zoomed = projectPoint(0.7f, 0.5f, 0.5f, 0f, 0f, 1000f, 600f, 0f, 2f, 0f, 0f)
        assertEquals((normal.x - 500f) * 2f, zoomed.x - 500f, 1e-3f)
        assertEquals(normal.y, zoomed.y, 1e-3f)
    }

    @Test
    fun panShiftsBothAxes() {
        val point = projectPoint(0.5f, 0.5f, 0.5f, 0f, 0f, 1000f, 600f, 0f, 1f, 30f, -20f)
        assertEquals(530f, point.x, 1e-3f)
        assertEquals(280f, point.y, 1e-3f)
    }

    @Test
    fun yawNinetyDegreesTurnsZIntoHorizontalAxis() {
        // 只有 z 偏移的点在 yaw = 90° 后应出现在右侧。
        val zPoint = projectPoint(0.5f, 0.5f, 0.7f, 90f, 0f, 1000f, 600f, 0f, 1f, 0f, 0f)
        assertTrue("z 偏移应投影到 x 轴: ${zPoint.x}", zPoint.x > 500f)
        // 只有 x 偏移的点则转到深度轴，屏幕 x 回到中线。
        val xPoint = projectPoint(0.7f, 0.5f, 0.5f, 90f, 0f, 1000f, 600f, 0f, 1f, 0f, 0f)
        assertEquals(500f, xPoint.x, 1e-3f)
        assertTrue("x 偏移应转到深度: ${xPoint.depth}", xPoint.depth < 0f)
    }

    @Test
    fun pitchNinetyDegreesTurnsVerticalAxisIntoDepth() {
        val point = projectPoint(0.5f, 0.7f, 0.5f, 0f, 90f, 1000f, 600f, 0f, 1f, 0f, 0f)
        assertEquals(300f, point.y, 1e-3f)
        assertTrue("上方点应更靠近相机: ${point.depth}", point.depth > 0f)
    }

    @Test
    fun depthRadiusScaleGrowsWithDepthAndStaysBounded() {
        assertTrue(depthRadiusScale(-0.9f) < depthRadiusScale(0f))
        assertTrue(depthRadiusScale(0f) < depthRadiusScale(0.9f))
        assertEquals(0.6f, depthRadiusScale(-5f), 1e-4f)
        assertEquals(1.35f, depthRadiusScale(5f), 1e-4f)
    }
}
