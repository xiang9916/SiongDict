package org.siongdict.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.siongdict.app.ui.LocalDisplayPreferences

/** 品牌红：主题主色与顶栏共用，避免十六进制字面量散落各处。 */
val SiongDarkRed = Color(0xFF8B0000)
val SiongCrimson = Color(0xFFDC143C)

/** 护眼模式的淡黄底色；关闭时界面用纯白。 */
val EyeCareBackground = Color(0xFFF2EEE6)

private fun lightColors(eyeCare: Boolean) = lightColorScheme(
    primary = SiongDarkRed,
    background = if (eyeCare) EyeCareBackground else Color.White,
)

private val DarkColors = darkColorScheme(
    primary = SiongCrimson,
)

/**
 * 全 App 主题入口：跟随系统深浅色，只覆盖品牌色，其余沿用 Material 3 默认值。
 *
 * 护眼开关在组合里直接读 [LocalDisplayPreferences]（不通过参数传），开关一拨，
 * 所有订阅 [MaterialTheme.colorScheme] 的界面（含弹窗与全屏页）一起重绘；深色模式不受影响。
 */
@Composable
fun SiongTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val eyeCare = LocalDisplayPreferences.current.eyeCareEnabled
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else lightColors(eyeCare),
        content = content
    )
}
