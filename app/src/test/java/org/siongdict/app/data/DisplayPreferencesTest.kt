package org.siongdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 显示偏好（簡体渲染／護眼）：纯 JVM，内存 store + 假字表。 */
class DisplayPreferencesTest {

    private val phrases = mapOf("漢字" to "汉字", "簡體" to "简体")
    private val chars = mapOf("漢" to "汉", "體" to "体", "簡" to "简", "字" to "字")

    private fun preferences(store: MemoryPrefStore = MemoryPrefStore()) =
        DisplayPreferences(store) { SimplifierTable(phrases, chars) }

    @Test
    fun disabledReturnsOriginalText() {
        val prefs = preferences()
        prefs.init()
        assertEquals("漢字", prefs.display("漢字"))
        assertFalse(prefs.renderSimplifiedEnabled)
    }

    @Test
    fun enabledPrefersPhraseThenFallsBackToChars() {
        val prefs = preferences()
        prefs.init()
        prefs.setRenderSimplified(true)
        assertEquals("汉字", prefs.display("漢字"))
        assertEquals("汉体", prefs.display("漢體"))
        assertEquals("汉X", prefs.display("漢X"))
    }

    @Test
    fun unknownCharsAndBlankInputAreKept() {
        val prefs = preferences()
        prefs.init()
        prefs.setRenderSimplified(true)
        assertEquals("Xyz", prefs.display("Xyz"))
        assertEquals("", prefs.display(""))
    }

    @Test
    fun togglesPersistAcrossInstances() {
        val store = MemoryPrefStore()
        val first = preferences(store)
        first.init()
        first.setRenderSimplified(true)
        first.setEyeCare(true)

        val second = preferences(store)
        second.init()
        assertTrue(second.renderSimplifiedEnabled)
        assertTrue(second.eyeCareEnabled)
        assertTrue(store.getBoolean(AppPrefs.KEY_RENDER_SIMPLIFIED, false))
        assertTrue(store.getBoolean(AppPrefs.KEY_EYE_CARE, false))
    }

    @Test
    fun tableIsLoadedOnceAndCached() {
        var loads = 0
        val prefs = DisplayPreferences(MemoryPrefStore()) {
            loads++
            SimplifierTable(phrases, chars)
        }
        prefs.init()
        assertEquals(1, loads)
        prefs.setRenderSimplified(true)
        assertEquals("汉字", prefs.display("漢字"))
        assertEquals("汉字", prefs.display("漢字"))
        assertEquals(1, loads)
    }

    @Test
    fun emptyTableMakesRenderingNoOp() {
        val prefs = DisplayPreferences(MemoryPrefStore()) { SimplifierTable(emptyMap(), emptyMap()) }
        prefs.init()
        prefs.setRenderSimplified(true)
        assertEquals("漢字", prefs.display("漢字"))
    }
}
