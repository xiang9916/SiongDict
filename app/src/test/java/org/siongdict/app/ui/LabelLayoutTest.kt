package org.siongdict.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标签贪心避让（[placeLabels]）：两块画布共用的纯几何规则。 */
class LabelLayoutTest {

    private fun label(left: Float, top: Float, width: Float = 10f, height: Float = 10f) =
        LabelRequest(left = left, top = top, width = width, height = height)

    @Test
    fun placesAllLabelsWhenNothingOverlaps() {
        val requests = listOf(label(0f, 0f), label(20f, 0f), label(40f, 20f))
        assertEquals(listOf(0, 1, 2), placeLabels(requests, maxLabels = 80))
    }

    @Test
    fun skipsLabelsOverlappingAnEarlierOne() {
        val requests = listOf(label(0f, 0f), label(5f, 5f), label(30f, 0f))
        assertEquals(listOf(0, 2), placeLabels(requests, maxLabels = 80))
    }

    @Test
    fun touchingEdgesDoNotCountAsOverlap() {
        val requests = listOf(label(0f, 0f), label(10f, 0f), label(0f, 10f))
        assertEquals(listOf(0, 1, 2), placeLabels(requests, maxLabels = 80))
    }

    @Test
    fun forcedLabelIsPlacedEvenWhenOverlapping() {
        val requests = listOf(
            label(0f, 0f),
            label(2f, 2f).copy(force = true),
            label(4f, 4f),
        )
        assertEquals(listOf(0, 1), placeLabels(requests, maxLabels = 80))
    }

    @Test
    fun honorsMaxLabels() {
        val requests = List(6) { index -> label(index * 12f, 0f) }
        assertEquals(listOf(0, 1, 2), placeLabels(requests, maxLabels = 3))
    }

    @Test
    fun forcedLabelsDoNotBypassTheCap() {
        val requests = listOf(
            label(0f, 0f),
            label(20f, 0f).copy(force = true),
            label(40f, 0f).copy(force = true),
        )
        assertEquals(listOf(0, 1), placeLabels(requests, maxLabels = 2))
    }

    @Test
    fun avoidOverlapFalseKeepsEverything() {
        val requests = listOf(label(0f, 0f), label(1f, 1f), label(2f, 2f))
        assertEquals(listOf(0, 1, 2), placeLabels(requests, maxLabels = 80, avoidOverlap = false))
    }

    @Test
    fun resultPreservesRequestOrder() {
        val requests = listOf(label(0f, 0f), label(15f, 0f), label(30f, 0f))
        val placed = placeLabels(requests, maxLabels = 80)
        assertEquals(placed.sorted(), placed)
        assertTrue(placed.all { it in requests.indices })
    }

    @Test
    fun emptyOrZeroCapReturnsEmpty() {
        assertTrue(placeLabels(emptyList(), maxLabels = 10).isEmpty())
        assertTrue(placeLabels(listOf(label(0f, 0f)), maxLabels = 0).isEmpty())
        assertTrue(placeLabels(listOf(label(0f, 0f)), maxLabels = -1).isEmpty())
    }
}
