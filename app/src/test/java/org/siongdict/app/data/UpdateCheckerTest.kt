package org.siongdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GitHub Release JSON 的 `tag_name` 解析（纯函数，不联网）。
 * 只取 `/releases/latest` 的 tag；字段缺失或畸形一律 null，由调用方按「失败静默」处理。
 */
class UpdateCheckerTest {

    @Test
    fun parsesTagName() {
        assertEquals(
            "应解析出 tag_name",
            "0.5-alpha.3",
            UpdateChecker.parseLatestTag("{\"tag_name\": \"0.5-alpha.3\"}")
        )
        assertEquals(
            "应忽略其它字段与空白",
            "0.5-alpha.4",
            UpdateChecker.parseLatestTag(
                "{\"url\": \"https://api.github.com/repos/x/y/releases/1\", " +
                    "\"tag_name\":\"0.5-alpha.4\", \"name\": \"0.5-alpha.4\"}"
            )
        )
    }

    @Test
    fun missingOrMalformedReturnsNull() {
        assertNull("缺少 tag_name 应为 null", UpdateChecker.parseLatestTag("{}"))
        assertNull("空字符串应为 null", UpdateChecker.parseLatestTag(""))
        assertNull("非 JSON 文本应为 null", UpdateChecker.parseLatestTag("not json"))
        assertNull(
            "tag_name 为空值应为 null",
            UpdateChecker.parseLatestTag("{\"tag_name\": \"\"}")
        )
    }
}