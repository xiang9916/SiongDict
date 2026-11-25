package org.siongdict.app.ui

import org.siongdict.app.analysis.PlaneRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 畫布視口數值（[OrbitViewport]／[MapViewport]）與輕點命中（純函數）。 */
class ViewportTest {

    @Test
    fun rotatedMovesYawProportionallyAndAccumulates() {
        val start = defaultOrbitViewport(yawDeg = 20f, pitchDeg = 10f)
        val moved = start.rotated(deltaX = 100f, deltaY = 0f, degreesPerPx = 0.4f, maxPitchDeg = 80f)
        assertEquals(60f, moved.yawDeg, 1e-4f)
        assertEquals(10f, moved.pitchDeg, 1e-4f)
    }

    @Test
    fun rotatedClampsPitch() {
        val start = defaultOrbitViewport(yawDeg = 0f, pitchDeg = 0f)
        val up = start.rotated(deltaX = 0f, deltaY = -1000f, degreesPerPx = 0.4f, maxPitchDeg = 80f)
        assertEquals(80f, up.pitchDeg, 1e-4f)
        val down = start.rotated(deltaX = 0f, deltaY = 1000f, degreesPerPx = 0.4f, maxPitchDeg = 80f)
        assertEquals(-80f, down.pitchDeg, 1e-4f)
    }

    @Test
    fun zoomedToClampsIntoRange() {
        val start = defaultOrbitViewport(yawDeg = 0f, pitchDeg = 0f)
        assertEquals(4f, start.zoomedTo(99f, minZoom = 0.6f, maxZoom = 4f).zoom, 1e-4f)
        assertEquals(0.6f, start.zoomedTo(0.01f, minZoom = 0.6f, maxZoom = 4f).zoom, 1e-4f)
    }

    @Test
    fun pannedAddsOffsets() {
        val start = defaultOrbitViewport(yawDeg = 0f, pitchDeg = 0f).panned(5f, -3f).panned(1f, 1f)
        assertEquals(6f, start.panX, 1e-4f)
        assertEquals(-2f, start.panY, 1e-4f)
    }

    @Test
    fun defaultViewportIsIdentity() {
        val orbit = defaultOrbitViewport(yawDeg = 15f, pitchDeg = 25f)
        assertEquals(1f, orbit.zoom, 1e-4f)
        assertEquals(0f, orbit.panX, 1e-4f)
        assertEquals(0f, orbit.panY, 1e-4f)
        val map = MapViewport()
        assertEquals(1f, map.zoom, 1e-4f)
        assertEquals(0f, map.panX, 1e-4f)
        assertEquals(0f, map.panY, 1e-4f)
    }

    @Test
    fun nearestProjectedIndexFindsClosestWithinRadius() {
        val projected = listOf(
            ProjectedPoint(10f, 10f, 0f, 1f),
            ProjectedPoint(20f, 10f, 0f, 1f),
            ProjectedPoint(100f, 100f, 0f, 1f),
        )
        assertEquals(1, nearestProjectedIndex(projected, tapX = 21f, tapY = 11f, radius = 16f))
        assertEquals(0, nearestProjectedIndex(projected, tapX = 9f, tapY = 9f, radius = 16f))
    }

    @Test
    fun nearestProjectedIndexRejectsOutsideRadiusAndEmptyInput() {
        val projected = listOf(ProjectedPoint(10f, 10f, 0f, 1f))
        assertNull(nearestProjectedIndex(projected, tapX = 100f, tapY = 100f, radius = 16f))
        assertNull(nearestProjectedIndex(emptyList(), tapX = 0f, tapY = 0f, radius = 16f))
    }

    @Test
    fun transformedKeepsAnchorUnderCentroid() {
        val start = MapViewport(zoom = 1f, panX = 0f, panY = 0f)
        val centroidX = 120f
        val centroidY = 80f
        // 手勢中心下的基準座標在變換前後必須映射到同一個螢幕位置。
        val baseX = (centroidX - start.panX) / start.zoom
        val baseY = (centroidY - start.panY) / start.zoom
        val next = start.transformed(
            centroidX = centroidX,
            centroidY = centroidY,
            panDeltaX = 0f,
            panDeltaY = 0f,
            gestureZoom = 2f,
            minZoom = 0.6f,
            maxZoom = 8f,
        )
        assertEquals(centroidX, baseX * next.zoom + next.panX, 1e-3f)
        assertEquals(centroidY, baseY * next.zoom + next.panY, 1e-3f)
    }

    @Test
    fun transformedClampsZoom() {
        val start = MapViewport(zoom = 7f, panX = 0f, panY = 0f)
        val next = start.transformed(0f, 0f, 0f, 0f, gestureZoom = 10f, minZoom = 0.6f, maxZoom = 8f)
        assertEquals(8f, next.zoom, 1e-4f)
    }

    @Test
    fun nearestSiteIndexAppliesViewportTransform() {
        val base = listOf(10f to 10f, 50f to 50f)
        val viewport = MapViewport(zoom = 2f, panX = 20f, panY = 0f)
        // 第二個站點在螢幕上的位置 = 50*2+20 = 120, 100。
        assertEquals(1, nearestSiteIndex(base, viewport, tapX = 121f, tapY = 101f, radius = 16f))
        assertEquals(0, nearestSiteIndex(base, viewport, tapX = 40f, tapY = 20f, radius = 16f))
    }

    @Test
    fun screenPositionAppliesZoomAndPan() {
        val viewport = MapViewport(zoom = 2f, panX = 5f, panY = -3f)
        val screen = screenPosition(10f to 20f, viewport)
        assertEquals(25f, screen.first, 1e-4f)
        assertEquals(37f, screen.second, 1e-4f)
    }

    @Test
    fun screenPositionIsIdentityAtDefaultViewport() {
        val screen = screenPosition(12.5f to -7f, MapViewport())
        assertEquals(12.5f, screen.first, 1e-4f)
        assertEquals(-7f, screen.second, 1e-4f)
    }

    @Test
    fun visibleSiteIndicesKeepsOnlyPointsInsideBounds() {
        val bounds = PlaneRect(left = 0f, top = 0f, right = 100f, bottom = 50f)
        val positions = listOf(
            10f to 10f, // 0 框內
            -1f to 10f, // 1 左外
            10f to -1f, // 2 上外
            101f to 10f, // 3 右外
            10f to 51f, // 4 下外
            0f to 0f, // 5 左上角（邊界算框內）
            100f to 50f, // 6 右下角（邊界算框內）
        )
        assertEquals(listOf(0, 5, 6), visibleSiteIndices(positions, bounds))
    }

    @Test
    fun visibleSiteIndicesFiltersEverythingOutsideBounds() {
        val bounds = PlaneRect(left = 100f, top = 100f, right = 200f, bottom = 200f)
        val positions = listOf(0f to 0f, 400f to 400f, 150f to 0f)
        assertTrue(visibleSiteIndices(positions, bounds).isEmpty())
        assertTrue(visibleSiteIndices(emptyList(), bounds).isEmpty())
    }

    @Test
    fun nearestSiteIndexHitRadiusIsScreenPixelsNotScaled() {
        // 放大 4 倍後站點在螢幕 (40, 40)：命中半徑仍是螢幕 16px，
        // 若誤用「16/zoom」或把半徑畫進 scale 內，這裡的判定就會不同。
        val viewport = MapViewport(zoom = 4f, panX = 0f, panY = 0f)
        val base = listOf(10f to 10f)
        assertEquals(0, nearestSiteIndex(base, viewport, tapX = 55f, tapY = 40f, radius = 16f))
        assertNull(nearestSiteIndex(base, viewport, tapX = 57f, tapY = 40f, radius = 16f))
        // 縮小到 0.5 倍後站點在螢幕 (5, 5)，命中半徑不變。
        val zoomedOut = MapViewport(zoom = 0.5f, panX = 0f, panY = 0f)
        assertEquals(0, nearestSiteIndex(base, zoomedOut, tapX = 5f, tapY = 20f, radius = 16f))
        assertNull(nearestSiteIndex(base, zoomedOut, tapX = 5f, tapY = 22f, radius = 16f))
    }

    @Test
    fun nearestSiteIndexOnFilteredCandidatesIgnoresHiddenSites() {
        val bounds = PlaneRect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        val base = listOf(10f to 10f, 150f to 10f)
        assertEquals(listOf(0), visibleSiteIndices(base, bounds))
        // 框外站點（150, 10）離輕點更近，但先被剔除：命中只能落在框內站點上。
        assertEquals(1, nearestSiteIndex(base, MapViewport(), tapX = 149f, tapY = 10f, radius = 16f))
        val visibleOnly = visibleSiteIndices(base, bounds).map { base[it] }
        assertNull(nearestSiteIndex(visibleOnly, MapViewport(), tapX = 149f, tapY = 10f, radius = 16f))
    }

    @Test
    fun nearestSiteIndexReturnsNullOutsideRadiusOrEmpty() {
        val base = listOf(10f to 10f)
        assertNull(nearestSiteIndex(base, MapViewport(), tapX = 500f, tapY = 500f, radius = 16f))
        assertNull(nearestSiteIndex(emptyList(), MapViewport(), tapX = 0f, tapY = 0f, radius = 16f))
        assertNotNull(nearestSiteIndex(base, MapViewport(), tapX = 10f, tapY = 10f, radius = 16f))
    }
}
