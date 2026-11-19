package org.siongdict.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 本 App 只用到三个图标：複製、方言篩選沿用 Material 路径（立方图标替代 11,000+ 图标、AAR 约 34 MB 的
 * `material-icons-extended`）；研究入口没有贴切的 Material 图标，改为自绘「放大镜＋原子」
 * （双圈透镜、右下把手、镜内 0/60/120 度三条椭圆轨道与中心核点）。
 * 全部图标都在 24dp viewport 内绘制、单色，可通过 `Icon(tint = …)` 着色。
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

    /** 研究入口（放大镜＋原子，自绘；取代原先的 Material scatter_plot）。 */
    val Research: ImageVector by lazy {
        strokedIcon("Research") {
            // 透镜：外圈粗、内圈细，构成双圈
            stroke("M3.9,10 a6.1,6.1 0 1,0 12.2,0 a6.1,6.1 0 1,0 -12.2,0", width = 1.6f)
            stroke("M4.7,10 a5.3,5.3 0 1,0 10.6,0 a5.3,5.3 0 1,0 -10.6,0", width = 1.0f)
            // 右下把手（45 度方向，圆头）
            stroke("M14.4,14.4 L19.6,19.6", width = 2.0f)
            // 镜内原子：三条椭圆轨道，分别在 0 / 60 / 120 度
            orbitalPath()
            rotatedOrbital(60f)
            rotatedOrbital(120f)
            // 中心核点（实心）
            addPath(
                pathData = addPathNodes("M9,10 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0"),
                fill = SolidColor(Color.Black)
            )
        }
    }

    /** 纯填充图标（Material 路径数据）。 */
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

    /** 全部由描边路径组成的图标；stroke 宽度、线帽由 [stroke] 指定。 */
    private fun strokedIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    /** 以 24x24 viewport 下的 SVG 风格路径加一条描边（无填充）。 */
    private fun ImageVector.Builder.stroke(
        pathData: String,
        width: Float,
        cap: StrokeCap = StrokeCap.Round
    ) {
        addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = cap
        )
    }

    /** 水平椭圆轨道（以 10,10 为圆心、rx=3.9、ry=2.0）。 */
    private fun ImageVector.Builder.orbitalPath() {
        stroke("M6.1,10 a3.9,2.0 0 1,0 7.8,0 a3.9,2.0 0 1,0 -7.8,0", width = 0.8f)
    }

    /** 绕透镜中心旋转 [degrees] 度的另一条椭圆轨道（ImageVector 的 group 用开/关而非 lambda）。 */
    private fun ImageVector.Builder.rotatedOrbital(degrees: Float) {
        addGroup(rotate = degrees, pivotX = 10f, pivotY = 10f)
        orbitalPath()
        clearGroup()
    }
}
