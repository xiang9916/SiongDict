package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoronoiTest {

    private val bounds = PlaneRect(0f, 0f, 100f, 100f)

    @Test
    fun singleSiteOwnsWholeRectangle() {
        val cells = voronoiCells(listOf(PlanePoint(50f, 50f)), bounds)

        assertEquals(1, cells.size)
        assertEquals(4, cells[0].size)
        assertEquals(10_000.0, area(cells[0]), 1e-3)
    }

    @Test
    fun twoSitesSplitRectangleInHalf() {
        val cells = voronoiCells(listOf(PlanePoint(25f, 50f), PlanePoint(75f, 50f)), bounds)

        assertEquals(2, cells.size)
        assertEquals(5_000.0, area(cells[0]), 1e-3)
        assertEquals(5_000.0, area(cells[1]), 1e-3)
        assertTrue(cells[0].all { it.x <= 50f + 1e-3f })
        assertTrue(cells[1].all { it.x >= 50f - 1e-3f })
    }

    @Test
    fun fourSitesSplitRectangleIntoQuarters() {
        val sites = listOf(
            PlanePoint(25f, 25f),
            PlanePoint(75f, 25f),
            PlanePoint(25f, 75f),
            PlanePoint(75f, 75f),
        )

        val cells = voronoiCells(sites, bounds)

        assertEquals(4, cells.size)
        for (cell in cells) assertEquals(2_500.0, area(cell), 1e-3)
    }

    @Test
    fun cellsStayInsideBounds() {
        val sites = listOf(
            PlanePoint(10f, 10f),
            PlanePoint(30f, 80f),
            PlanePoint(90f, 20f),
            PlanePoint(55f, 55f),
            PlanePoint(70f, 95f),
        )

        val cells = voronoiCells(sites, bounds)

        assertEquals(sites.size, cells.size)
        for (cell in cells) {
            assertTrue(cell.size >= 3)
            for (point in cell) {
                assertTrue(point.x >= -1e-3f && point.x <= 100f + 1e-3f)
                assertTrue(point.y >= -1e-3f && point.y <= 100f + 1e-3f)
            }
        }
    }

    @Test
    fun emptySitesGiveEmptyCells() {
        assertTrue(voronoiCells(emptyList(), bounds).isEmpty())
    }

    @Test
    fun coincidentSitesBothKeepWholeRectangle() {
        val cells = voronoiCells(listOf(PlanePoint(40f, 60f), PlanePoint(40f, 60f)), bounds)

        assertEquals(2, cells.size)
        assertEquals(10_000.0, area(cells[0]), 1e-3)
        assertEquals(10_000.0, area(cells[1]), 1e-3)
    }

    /** 鞋帶公式（絕對值）算多邊形面積。 */
    private fun area(polygon: List<PlanePoint>): Double {
        var sum = 0.0
        for (index in polygon.indices) {
            val current = polygon[index]
            val next = polygon[(index + 1) % polygon.size]
            sum += current.x.toDouble() * next.y - next.x.toDouble() * current.y
        }
        return abs(sum) / 2.0
    }
}
