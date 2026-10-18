package org.siongdict.app.ui

import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.Simplifier

/**
 * 要显示的文字：开了「渲染為簡體中文」就转简体。
 * 读的是 [Simplifier.enabled] 这个 Compose 状态，所以拨动开关会**即时**重组，
 * 不需要重新检索。字組與使用者輸入不走这里（见 CONTEXT.md「簡體渲染」）。
 */
internal fun disp(text: String) = Simplifier.display(text)

/** 卡片标题：去掉字組内部的空格（同音字以空格分隔，标题不显示空格）。 */
internal fun displayTitle(chars: String) = chars.replace(" ", "")

/** 搜同源的卡片标题是義項標籤，要转简体；其余两种模式标题是字組／輸入，不转。 */
internal fun cardTitle(group: CharGroup): String {
    val title = displayTitle(group.chars)
    return if (group.charsIsLabel) disp(title) else title
}
