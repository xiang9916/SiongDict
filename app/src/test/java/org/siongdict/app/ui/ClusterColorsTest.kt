package org.siongdict.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 聚类画布配色：调色盘循环与音典颜色解析（纯函数，JVM 测试）。 */
class ClusterColorsTest {

    @Test
    fun paletteWrapsAroundAndStaysOpaque() {
        assertEquals(10, ClusterPalette.size)
        assertEquals(ClusterPalette[0], paletteColor(0))
        assertEquals(ClusterPalette[9], paletteColor(9))
        assertEquals(ClusterPalette[0], paletteColor(10))
        assertEquals(ClusterPalette[0], paletteColor(-10))
        ClusterPalette.forEach { color ->
            assertEquals("调色盘颜色必须不透明", 0xFF000000L, color and 0xFF000000L)
        }
    }

    @Test
    fun parseColorSpecHandlesHexAlphaGradientAndGarbage() {
        assertEquals(listOf(0xFF1E88E5L), parseColorSpec("#1E88E5"))
        assertEquals(listOf(0xFF1E88E5L), parseColorSpec("  #1e88e5  "))
        assertEquals(listOf(0x801E88E5L), parseColorSpec("#801E88E5"))
        assertEquals(
            listOf(0xFFD32F2FL, 0xFFFFCDD2L),
            parseColorSpec("#D32F2F,#FFCDD2")
        )
        // 全角逗号也当分隔符
        assertEquals(
            listOf(0xFFD32F2FL, 0xFFFFCDD2L),
            parseColorSpec("#D32F2F， #FFCDD2")
        )
        // 任何非法写法都回落到中性灰，避免坏数据让画布崩掉
        assertEquals(listOf(CLUSTER_GRAY), parseColorSpec(""))
        assertEquals(listOf(CLUSTER_GRAY), parseColorSpec("1E88E5"))
        assertEquals(listOf(CLUSTER_GRAY), parseColorSpec("#12345"))
        assertEquals(listOf(CLUSTER_GRAY), parseColorSpec("#GGGGGG"))
        assertEquals(listOf(CLUSTER_GRAY), parseColorSpec("#1E88E5,#ZZ0000"))
    }
}
