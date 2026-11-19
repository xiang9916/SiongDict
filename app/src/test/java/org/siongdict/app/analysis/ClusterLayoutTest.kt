package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterLayoutTest {

    @Test
    fun layoutIsDeterministicAndStaysInUnitSquare() {
        val distances = arrayOf(
            doubleArrayOf(0.0, 0.05, 0.7, 0.7, 0.7),
            doubleArrayOf(0.05, 0.0, 0.7, 0.7, 0.7),
            doubleArrayOf(0.7, 0.7, 0.0, 0.7, 0.7),
            doubleArrayOf(0.7, 0.7, 0.7, 0.0, 0.1),
            doubleArrayOf(0.7, 0.7, 0.7, 0.1, 0.0),
        )

        val layout = forceLayout(distances)

        assertEquals(5, layout.size)
        // 无随机数：同一距离矩阵必得同一坐标。
        assertEquals(layout, forceLayout(distances))
        for ((x, y) in layout) {
            assertTrue("x=$x", x.isFinite() && x in 0f..1f)
            assertTrue("y=$y", y.isFinite() && y in 0f..1f)
        }
        assertTrue("points must not all overlap", layout.toSet().size > 1)

        // 退化输入：空矩阵 → 空列表；单点 → 画布中心。
        assertTrue(forceLayout(emptyArray()).isEmpty())
        assertEquals(listOf(0.5f to 0.5f), forceLayout(arrayOf(doubleArrayOf(0.0))))
    }
}
