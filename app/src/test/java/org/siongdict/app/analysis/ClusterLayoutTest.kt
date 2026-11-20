package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterLayoutTest {

    @Test
    fun layoutIsDeterministicAndStaysInUnitCube() {
        val distances = arrayOf(
            doubleArrayOf(0.0, 0.05, 0.7, 0.7, 0.7),
            doubleArrayOf(0.05, 0.0, 0.7, 0.7, 0.7),
            doubleArrayOf(0.7, 0.7, 0.0, 0.7, 0.7),
            doubleArrayOf(0.7, 0.7, 0.7, 0.0, 0.1),
            doubleArrayOf(0.7, 0.7, 0.7, 0.1, 0.0),
        )

        val layout = forceLayout3D(distances)

        assertEquals(5, layout.size)
        // 无随机数：同一距离矩阵必得同一组三维坐标。
        assertEquals(layout, forceLayout3D(distances))
        for ((x, y, z) in layout) {
            assertTrue("x=$x", x.isFinite() && x in 0f..1f)
            assertTrue("y=$y", y.isFinite() && y in 0f..1f)
            assertTrue("z=$z", z.isFinite() && z in 0f..1f)
        }
        assertTrue("points must not all overlap", layout.toSet().size > 1)
        // 三维布局不能退化成平面：z 轴必须有跨度。
        assertTrue("z must spread", layout.map { it.third }.toSet().size > 1)

        // 退化输入：空矩阵 → 空列表；单点 → 立方体中心。
        assertTrue(forceLayout3D(emptyArray()).isEmpty())
        assertEquals(
            listOf(Triple(0.5f, 0.5f, 0.5f)),
            forceLayout3D(arrayOf(doubleArrayOf(0.0))),
        )
    }
}
