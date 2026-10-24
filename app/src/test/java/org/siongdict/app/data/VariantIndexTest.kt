package org.siongdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VariantIndexTest {

    @Test
    fun unknownCharacterReturnsItself() {
        assertEquals(listOf("湘"), VariantIndex(emptyMap()).variantsOf("湘"))
    }

    @Test
    fun keepsSelfInVariantList() {
        assertEquals(listOf("濕", "湿"), VariantIndex(mapOf("濕" to listOf("濕", "湿"))).variantsOf("濕"))
    }

    @Test
    fun expandsAllCombinations() {
        val index = VariantIndex(mapOf("發" to listOf("發", "发"), "展" to listOf("展")))
        assertEquals(listOf("發展", "发展"), index.expand("發展"))
    }

    @Test
    fun fallsBackWhenCombinationCountExceedsLimit() {
        val table = ('a'..'g').associate { it.toString() to listOf(it.toString(), it.toString().uppercase()) }
        val query = "abcdefg"
        assertEquals(listOf(query), VariantIndex(table).expand(query))
    }

    @Test
    fun emptyQueryReturnsItself() {
        assertEquals(listOf(""), VariantIndex(emptyMap()).expand(""))
    }
}
