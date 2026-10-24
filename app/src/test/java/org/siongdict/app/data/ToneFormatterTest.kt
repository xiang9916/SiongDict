package org.siongdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ToneFormatterTest {

    /** 构造一个「5 字段」的声调样式表：[0] 是调值，其余留空（与音典数据结构一致）。 */
    private fun system(vararg contours: Pair<String, String>): ToneSystem =
        ToneSystem(contours.associate { (tone, value) -> tone to List(5) { i -> if (i == 0) value else "" } })

    @Test
    fun convertsToneValueToContour() {
        assertEquals("tən˧˧˦1", ToneFormatter.format("tən1", system("1" to "334")))
    }

    @Test
    fun keepsInputWithoutToneSystem() {
        assertEquals("tən1", ToneFormatter.format("tən1", null))
    }

    @Test
    fun keepsInputWithoutToneNumber() {
        assertEquals("tən", ToneFormatter.format("tən", system("1" to "55")))
    }

    @Test
    fun fallsBackWhenToneCategoryMissing() {
        assertEquals("tən3", ToneFormatter.format("tən3", system("1" to "55")))
    }

    @Test
    fun fallsBackWhenStylesHaveWrongShape() {
        assertEquals("tən1", ToneFormatter.format("tən1", ToneSystem(mapOf("1" to listOf("334")))))
    }

    @Test
    fun usesFullKeyWhenDigitKeyMissing() {
        assertEquals("tɕi˨˩˧2a", ToneFormatter.format("tɕi2a", system("2a" to "213")))
    }

    @Test
    fun digitKeyWinsOverFullKey() {
        assertEquals("tɕi˦˥2a", ToneFormatter.format("tɕi2a", system("2" to "45", "2a" to "213")))
    }

    @Test
    fun convertsAlternativeToneValues() {
        assertEquals("tən˥˥/˧˧1", ToneFormatter.format("tən1", system("1" to "55/33")))
    }

    @Test
    fun toneZeroDropsDigitOnlyWhenLookupFails() {
        assertEquals("ma", ToneFormatter.format("ma0", system("1" to "55")))
        assertEquals("ma˩0", ToneFormatter.format("ma0", system("0" to "1")))
    }

    @Test
    fun formattingIsIdempotent() {
        val toneSystem = system("1" to "334")
        val once = ToneFormatter.format("tən1", toneSystem)
        assertEquals(once, ToneFormatter.format(once, toneSystem))
    }
}
