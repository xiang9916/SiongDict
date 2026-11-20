package org.siongdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较规则（只测纯函数，不联网）：数字段逐段比，再比预发布后缀；
 * 同一数字段下「带后缀（预发布）」比「不带后缀（正式）」旧。
 */
class VersionComparatorTest {

    @Test
    fun comparesNumericSegments() {
        assertTrue(
            "0.4-rc.14 应比 0.5-alpha.1 旧",
            UpdateChecker.compareVersions("0.4-rc.14", "0.5-alpha.1") < 0
        )
        assertTrue(
            "0.5-alpha.2 应比 0.5-alpha.3 旧",
            UpdateChecker.compareVersions("0.5-alpha.2", "0.5-alpha.3") < 0
        )
        assertEquals(
            "同一版本应相等",
            0,
            UpdateChecker.compareVersions("0.5-alpha.3", "0.5-alpha.3")
        )
        assertTrue(
            "0.5-alpha.3 应比 0.5-alpha.10 旧（数字段按数值而非字典序）",
            UpdateChecker.compareVersions("0.5-alpha.3", "0.5-alpha.10") < 0
        )
        assertTrue(
            "0.5-alpha.10 应比 0.5-alpha.3 新",
            UpdateChecker.compareVersions("0.5-alpha.10", "0.5-alpha.3") > 0
        )
    }

    @Test
    fun prereleaseIsOlderThanRelease() {
        assertTrue(
            "0.5 预发布应比同号正式版旧",
            UpdateChecker.compareVersions("0.5-alpha.3", "0.5") < 0
        )
        assertTrue(
            "正式版应比同号预发布新",
            UpdateChecker.compareVersions("0.5", "0.5-alpha.3") > 0
        )
        assertEquals(
            "两个正式版相等应返回 0",
            0,
            UpdateChecker.compareVersions("0.5", "0.5.0")
        )
    }
}