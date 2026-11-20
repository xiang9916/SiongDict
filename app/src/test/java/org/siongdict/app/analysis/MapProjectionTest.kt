package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProjectionTest {

    @Test
    fun mercatorIsZeroAtEquatorAndMonotonic() {
        assertEquals(0.0, mercatorY(0.0), 1e-9)
        assertTrue(mercatorY(30.0) > mercatorY(20.0))
        assertTrue(mercatorY(-20.0) < 0.0)
        // 南北对称。
        assertEquals(-mercatorY(25.0), mercatorY(-25.0), 1e-9)
    }

    @Test
    fun mercatorRoundTrips() {
        for (lat in listOf(-80.0, -30.0, 0.0, 22.0, 33.0, 60.0, 80.0)) {
            assertEquals(lat, inverseMercatorY(mercatorY(lat)), 1e-9)
        }
    }

    @Test
    fun fitTransformUsesShapeBoundsAndKeepsAspectRatio() {
        val shapes = listOf(
            ProvinceShape(
                name = "湖南",
                polygons = listOf(
                    listOf(LonLat(109.0, 25.0), LonLat(114.0, 25.0), LonLat(114.0, 30.0), LonLat(109.0, 30.0)),
                ),
            ),
        )

        val transform = fitTransform(emptyList(), shapes, width = 360f, height = 640f, padding = 12f)

        // 内容等比缩放：经度方向与 Mercator 方向共用同一 scale。
        val left = transform.project(109.0, 30.0).first
        val right = transform.project(114.0, 30.0).first
        val top = transform.project(109.0, 30.0).second
        val bottom = transform.project(109.0, 25.0).second
        assertTrue(left >= 12f - 0.01f)
        assertTrue(right <= 360f - 12f + 0.01f)
        assertTrue(top >= 12f - 0.01f)
        assertTrue(bottom <= 640f - 12f + 0.01f)
        assertEquals(360f - 24f, right - left, 0.01f)
        // 高度受宽度限制：5° 经度铺满可用宽度时，高度按比例缩到更小。
        assertEquals((right - left) * (transform.maxMercatorY - transform.minMercatorY).toFloat() /
            (transform.maxLon - transform.minLon).toFloat(), bottom - top, 0.05f)
    }

    @Test
    fun fitTransformFallsBackToSitesWhenNoShapes() {
        val sites = listOf(MapSite("甲", 110.0, 27.0, 0, "#111111"), MapSite("乙", 113.0, 29.0, 1, "#222222"))

        val transform = fitTransform(sites, emptyList(), width = 200f, height = 200f)

        assertTrue(transform.scale > 0f)
        val first = transform.project(110.0, 27.0)
        val second = transform.project(113.0, 29.0)
        assertTrue(first.first in 0f..200f && first.second in 0f..200f)
        assertTrue(second.first in 0f..200f && second.second in 0f..200f)
    }

    @Test
    fun projectUnprojectRoundTrips() {
        val shapes = listOf(
            ProvinceShape("湖南", listOf(listOf(LonLat(109.0, 25.0), LonLat(114.0, 25.0), LonLat(114.0, 30.0), LonLat(109.0, 30.0)))),
        )
        val transform = fitTransform(emptyList(), shapes, width = 300f, height = 300f)

        val (x, y) = transform.project(112.5, 27.5)
        val back = transform.unproject(x, y)

        assertEquals(112.5, back.lon, 1e-4)
        assertEquals(27.5, back.lat, 1e-4)
    }

    @Test
    fun emptyInputGivesFiniteTransform() {
        val transform = fitTransform(emptyList(), emptyList(), width = 100f, height = 100f)

        assertTrue(transform.scale.isFinite() && transform.scale > 0f)
        val point = transform.project(0.0, 0.0)
        assertTrue(point.first.isFinite() && point.second.isFinite())
    }
}
