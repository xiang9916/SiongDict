package org.siongdict.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 本 App 只用到两个 Material Icon（複製、方言篩選）。为它们各内联一个 [ImageVector]，
 * 从而不必引入 `material-icons-extended`（11,000+ 个图标、AAR 约 34 MB，
 * 其中真正被用到的只有这两个）。路径数据取自 Material Icons（Apache-2.0），
 * 画法与 `Icons.Default.ContentCopy` / `Icons.Default.Visibility` 一致。
 */
object SiongIcons {

    /** 複製（content_copy）。 */
    val ContentCopy: ImageVector by lazy {
        icon(
            name = "ContentCopy",
            pathData = "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11" +
                "c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"
        )
    }

    /** 可見性／方言篩選（visibility）。 */
    val Visibility: ImageVector by lazy {
        icon(
            name = "Visibility",
            pathData = "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5" +
                "C21.27,7.61 17,4.5 12,4.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5" +
                " -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black)
        ).build()
}
