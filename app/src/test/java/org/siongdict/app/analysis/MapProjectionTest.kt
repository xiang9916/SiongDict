package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class MapProjectionTest {

    private fun hunanSquare(): List<ProvinceShape> = listOf(
        ProvinceShape(
            name = "湖南",
            polygons = listOf(
                listOf(
                    LonLat(109.0, 25.0),
                    LonLat(114.0, 25.0),
                    LonLat(114.0, 30.0),
                    LonLat(109.0, 30.0),
                ),
            ),
        ),
    )

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

    /**
     * 內容等比縮放：經度與 Mercator 縱座標換算成同一單位（弧度）後共用同一個 scale。
     * 這條關係原本寫成「度跨度 × 弧度高跨度 ÷ 度跨度」，等於把縱向壓掉 57.3 倍，
     * 真實 App 的地圖因此被壓成十幾像素高的橫帶（2026-11-20 修正，別改回去）。
     */
    @Test
    fun fitTransformUsesShapeBoundsAndKeepsAspectRatio() {
        val transform = fitTransform(emptyList(), hunanSquare(), width = 360f, height = 640f, padding = 12f)

        val left = transform.project(109.0, 30.0).first
        val right = transform.project(114.0, 30.0).first
        val top = transform.project(109.0, 30.0).second
        val bottom = transform.project(109.0, 25.0).second
        assertTrue(left >= 12f - 0.01f)
        assertTrue(right <= 360f - 12f + 0.01f)
        assertTrue(top >= 12f - 0.01f)
        assertTrue(bottom <= 640f - 12f + 0.01f)
        // 寬度受畫布限制：5° 經度鋪滿可用寬度。
        assertEquals(360f - 24f, right - left, 0.01f)

        val lonSpanRad = ((transform.maxLon - transform.minLon) * PI / 180.0).toFloat()
        val mercatorSpan = (transform.maxMercatorY - transform.minMercatorY).toFloat()
        assertEquals((right - left) * mercatorSpan / lonSpanRad, bottom - top, 0.5f)

        // 內容框要與實際投影範圍一致，且佔滿可用高度的一半以上（不能是橫帶）。
        assertEquals(right - left, transform.contentWidth, 0.01f)
        assertEquals(bottom - top, transform.contentHeight, 0.5f)
        assertTrue(
            "內容高度 " + transform.contentHeight + " 太小，投影把縱向壓扁了",
            transform.contentHeight > 320f,
        )
    }

    @Test
    fun projectedAxesUseSameScale() {
        val transform = fitTransform(emptyList(), hunanSquare(), width = 360f, height = 640f, padding = 12f)
        val oneDegreeRad = (PI / 180.0).toFloat()

        val lonStep = transform.project(110.0, 27.0).first - transform.project(109.0, 27.0).first
        val latStep = abs(transform.project(109.0, 26.0).second - transform.project(109.0, 25.0).second)
        val mercatorStep = (mercatorY(26.0) - mercatorY(25.0)).toFloat()

        val tolerance = transform.scale * 0.01f
        assertEquals(transform.scale, lonStep / oneDegreeRad, tolerance)
        assertEquals(transform.scale, latStep / mercatorStep, tolerance)
    }

    @Test
    fun threeSitesOnDifferentLatitudesDoNotCollapse() {
        val transform = fitTransform(emptyList(), hunanSquare(), width = 360f, height = 640f, padding = 12f)

        val ys = listOf(25.0, 27.5, 30.0).map { transform.project(111.0, it).second }
        val span = ys.maxOrNull()!! - ys.minOrNull()!!
        assertTrue("不同緯度的點幾乎同一行（跨度 " + span + " px），投影比例不對", span > 100f)
        // y 軸向下，緯度越高 y 越小：相鄰兩點（低緯 → 高緯）縱向落差要夠大。
        assertTrue(ys.zipWithNext().all { (lowerLatY, higherLatY) -> lowerLatY - higherLatY > 30f })
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
        val transform = fitTransform(emptyList(), hunanSquare(), width = 300f, height = 300f)

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
        val bounds = transform.contentBounds()
        assertTrue(bounds.right > bounds.left && bounds.bottom > bounds.top)
        assertTrue(bounds.left.isFinite() && bounds.bottom.isFinite())
    }
}
