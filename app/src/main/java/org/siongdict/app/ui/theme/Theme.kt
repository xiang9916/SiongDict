package org.siongdict.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 品牌红：主题主色与顶栏共用，避免十六进制字面量散落各处。 */
val SiongDarkRed = Color(0xFF8B0000)
val SiongCrimson = Color(0xFFDC143C)

private val LightColors = lightColorScheme(
    primary = SiongDarkRed,
    background = Color(0xFFF2EEE6),
)

private val DarkColors = darkColorScheme(
    primary = SiongCrimson,
)

/** 全 App 主题入口：跟随系统深浅色，只覆盖品牌色，其余沿用 Material 3 默认值。 */
@Composable
fun SiongTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
