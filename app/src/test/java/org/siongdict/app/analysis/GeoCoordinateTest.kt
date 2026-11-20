package org.siongdict.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoCoordinateTest {

    @Test
    fun parsesCommaSeparatedLonLat() {
        assertEquals(113.947296 to 29.974351, parseLonLat("113.947296,29.974351"))
        assertEquals(110.0 to 27.0, parseLonLat(" 110.0 , 27.0 "))
        assertEquals(-73.5 to -33.25, parseLonLat("-73.5,-33.25"))
    }

    @Test
    fun rejectsMissingOrMalformedValues() {
        assertNull(parseLonLat(null))
        assertNull(parseLonLat(""))
        assertNull(parseLonLat("   "))
        assertNull(parseLonLat("113.9"))
        assertNull(parseLonLat("113.9,29.9,1.0"))
        assertNull(parseLonLat("a,b"))
        assertNull(parseLonLat("113.9,"))
        assertNull(parseLonLat(",29.9"))
        assertNull(parseLonLat("NaN,29.9"))
        assertNull(parseLonLat("Infinity,29.9"))
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        assertNull(parseLonLat("180.1,29.9"))
        assertNull(parseLonLat("-180.1,29.9"))
        assertNull(parseLonLat("113.9,90.1"))
        assertNull(parseLonLat("113.9,-90.1"))
        assertEquals(180.0 to 90.0, parseLonLat("180,90"))
    }
}
