package org.siongdict.app.analysis

import kotlin.math.sqrt
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

    private fun point(lang: String, x: Float, y: Float, z: Float) = ClusterPoint(
        lang = lang,
        division = "測試",
        colorHex = "#000000",
        tagCount = 1,
        entryCount = 1,
        x = x,
        y = y,
        z = z
    )

    @Test
    fun groupedLayout3DIsDeterministicAndInUnitCube() {
        // 三个紧密小团、彼此远离：聚拢后类内距应明显小于类间质心距。
        val points = listOf(
            point("a1", 0.08f, 0.10f, 0.10f),
            point("a2", 0.12f, 0.12f, 0.10f),
            point("b1", 0.88f, 0.10f, 0.12f),
            point("b2", 0.92f, 0.12f, 0.10f),
            point("c1", 0.50f, 0.88f, 0.10f),
            point("c2", 0.52f, 0.92f, 0.12f),
        )
        val labels = intArrayOf(0, 0, 1, 1, 2, 2)

        val grouped = groupedLayout3D(points, labels)

        assertEquals(points.size, grouped.size)
        // 无随机数：同一输入必得同一输出。
        assertEquals("同一输入必得同一输出", grouped, groupedLayout3D(points, labels))
        for ((x, y, z) in grouped) {
            assertTrue("x=$x", x.isFinite() && x in 0f..1f)
            assertTrue("y=$y", y.isFinite() && y in 0f..1f)
            assertTrue("z=$z", z.isFinite() && z in 0f..1f)
        }
        // 同一类内不应全叠到同一点（类内形状按 spread 保留）。
        assertTrue("group 0 must keep shape", grouped[0] != grouped[1])
        assertTrue("group 1 must keep shape", grouped[2] != grouped[3])

        fun dist(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>): Double {
            val dx = (a.first - b.first).toDouble()
            val dy = (a.second - b.second).toDouble()
            val dz = (a.third - b.third).toDouble()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val meanIntra = listOf(0 to 1, 2 to 3, 4 to 5)
            .map { (i, j) -> dist(grouped[i], grouped[j]) }
            .average()
        val centroids = listOf(0..1, 2..3, 4..5).map { range ->
            Triple(
                range.map { grouped[it].first }.average().toFloat(),
                range.map { grouped[it].second }.average().toFloat(),
                range.map { grouped[it].third }.average().toFloat(),
            )
        }
        val meanBetween = listOf(0 to 1, 0 to 2, 1 to 2)
            .map { (i, j) -> dist(centroids[i], centroids[j]) }
            .average()
        assertTrue(
            "类内平均距 $meanIntra 应小于类间质心平均距 $meanBetween",
            meanIntra < meanBetween,
        )
    }

    @Test
    fun groupedLayout3DNullLabelsReturnsIdentity() {
        val points = listOf(
            point("a", 0.2f, 0.3f, 0.4f),
            point("b", 0.7f, 0.6f, 0.5f),
        )
        val identity = listOf(
            Triple(0.2f, 0.3f, 0.4f),
            Triple(0.7f, 0.6f, 0.5f),
        )
        // labels 为 null：原样返回（不分组）。
        assertEquals(identity, groupedLayout3D(points, null))
        // 长度不符：同样原样返回，不得越界或错位。
        assertEquals(identity, groupedLayout3D(points, intArrayOf(0)))
        assertEquals(identity, groupedLayout3D(points, intArrayOf(0, 1, 1)))
        // 空输入：空输出。
        assertTrue(groupedLayout3D(emptyList(), null).isEmpty())
    }

    @Test
    fun groupedLayout3DSingleClassDoesNotCollapse() {
        val points = listOf(
            point("a", 0.1f, 0.1f, 0.1f),
            point("b", 0.9f, 0.5f, 0.5f),
            point("c", 0.5f, 0.9f, 0.9f),
        )
        // 只有一类时原样返回，不得把全部点塌缩到一个锚点。
        assertEquals(
            listOf(Triple(0.1f, 0.1f, 0.1f), Triple(0.9f, 0.5f, 0.5f), Triple(0.5f, 0.9f, 0.9f)),
            groupedLayout3D(points, intArrayOf(0, 0, 0)),
        )
    }

    @Test
    fun lerpLayoutEndpoints() {
        val from = mapOf("a" to Triple(0f, 0f, 0f), "b" to Triple(0.2f, 0.4f, 0.6f))
        val to = mapOf("a" to Triple(1f, 1f, 1f), "b" to Triple(0.8f, 0.2f, 0f))
        assertEquals("t=0 应等于起点", from, lerpLayout(from, to, 0f))
        assertEquals("t=1 应等于终点", to, lerpLayout(from, to, 1f))
        // t 会夹到 0..1。
        assertEquals(from, lerpLayout(from, to, -5f))
        assertEquals(to, lerpLayout(from, to, 5f))
    }

    @Test
    fun lerpLayoutMidpointAndMissingKey() {
        val from = mapOf("a" to Triple(0f, 0f, 0f), "b" to Triple(0.2f, 0.2f, 0.2f))
        val to = mapOf("a" to Triple(1f, 1f, 1f), "c" to Triple(0.6f, 0.6f, 0.6f))
        val mid = lerpLayout(from, to, 0.5f)
        assertEquals("两侧都有的 key 取中点", Triple(0.5f, 0.5f, 0.5f), mid["a"])
        assertEquals("只在一侧的 key 取存在的那一侧", Triple(0.2f, 0.2f, 0.2f), mid["b"])
        assertEquals("只在一侧的 key 取存在的那一侧", Triple(0.6f, 0.6f, 0.6f), mid["c"])
        assertEquals("key 取并集", setOf("a", "b", "c"), mid.keys)
    }
}
