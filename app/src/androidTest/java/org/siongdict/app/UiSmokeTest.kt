package org.siongdict.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 界面回归测试（真机/模拟器）：首帧渲染（含内联图标与只读库打开）、
 * **重复字检索不再因重复 key 崩溃**、三种模式、方言筛选对话框、关于面板、
 * 研究对话框，以及 0.5-alpha.3 起聚类分析改为全屏页（不再套 Dialog）。
 *
 * 本轮起「聚類分析 → 在地圖展示」是再下一层的全屏页（ClusterMapScreen）：
 * 地图页回收站式关闭（cluster_map_close）只退一层，聚类页仍在；再按 cluster_close
 * 才回检索主界面。地图底图缺失时只画站点，测试只断言开关与页面存在，不碰渲染像素。
 *
 * 关闭按钮一律按 testTag 定位：多个面板并存时「關閉」文本会重复，
 * 依次为 info_close／filter_close／research_close／cluster_close／cluster_map_close。
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWithTitleAndModes() {
        compose.onNodeWithText("湘典", substring = true).assertExists()
        compose.onNodeWithText("搜字音").assertExists()
        compose.onNodeWithText("搜釋義").assertExists()
        compose.onNodeWithText("搜同源").assertExists()
    }

    @Test
    fun repeatedCharSearchDoesNotCrash() {
        // 修复前：同一个字检索两次会产生两张 key 相同的卡片，LazyColumn 抛 IllegalArgumentException
        compose.onNode(hasSetTextAction()).performTextInput("湘湘")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("點", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "重复字检索应渲染出方言点数",
            compose.onAllNodesWithText("點", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun searchByCharShowsResults() {
        compose.onNode(hasSetTextAction()).performTextInput("湘")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("點", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun searchByMeaningShowsCard() {
        compose.onNodeWithText("搜釋義").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("彎腰")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("彎腰", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun dialectFilterDialogOpensAndClearsSelection() {
        // 右上筛选图示打开分区树对话框：顶层分区 湘贛 及其子级 岳州 均应列出。
        compose.onNodeWithContentDescription("方言篩選").performClick()
        compose.onNodeWithTag("filter_dialog").assertExists()
        // 首次安装／资料被重置时，SearchViewModel 要先 prepare() 复制约 57MB 的 siongdict.db
        // 才载入分区树，对话框会先显示「暫無分區資料」；先等树列表真正出现，不能放寬断言。
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("暫無分區資料").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog()))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        // 分区树按（音典排序, 簡稱）排序后，顶层首个分区是「藍青」，湘贛不在对话框首屏；
        // LazyColumn 只组合可见行，所以先在分区树列表里滚动到目标行，再按原断言核对。
        val dialogTree = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
        dialogTree.performScrollToNode(hasText("湘贛"))
        compose.onNodeWithText("湘贛").assertExists()
        dialogTree.performScrollToNode(hasText("岳州"))
        compose.onNodeWithText("岳州").assertExists()
        // 「全選／全不選」与关闭按钮都按 testTag 定位，避免与其它面板的「關閉」文本重名。
        compose.onNodeWithTag("filter_select_all").assertExists()
        compose.onNodeWithTag("filter_select_none").performClick()
        compose.onNodeWithTag("filter_close").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("湘")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("無結果").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "全不選后检索应渲染空结果且不崩",
            compose.onAllNodesWithText("無結果").fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithText("湘典", substring = true).assertExists()
    }

    @Test
    fun aboutDialogOpensAndCloses() {
        compose.onNodeWithContentDescription("關於").performClick()
        // 「檢查更新」列必定存在；是否「已是最新」取决于网络与 GitHub，测试不断言网络结果。
        compose.onNodeWithTag("info_update_row").assertExists()
        compose.onNodeWithText("渲染為簡體中文 (Beta)").assertExists()
        compose.onNodeWithText("重置資料庫").assertExists()
        // 護眼模式开关位于滚动内容末段：先滚动到它，再点两下（开→关，恢复原状，避免影响后续用例）。
        compose.onNodeWithTag("eye_care_switch").performScrollTo().performClick()
        compose.onNodeWithTag("eye_care_switch").performScrollTo().performClick()
        compose.onNodeWithTag("info_close").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("info_update_row").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("搜字音").assertExists()
    }

    /**
     * 研究入口是二级结构：研究图示 →「研究」对话框 → 点「聚類分析」→ 读同源词库并计算聚类。
     * 0.5-alpha.3 起「聚類分析」不再是 Dialog，而是整页替换检索界面的全屏页（ClusterAnalysisScreen）。
     */
    @Test
    fun researchDialogOpensAndCloses() {
        compose.onNodeWithContentDescription("研究").performClick()
        compose.onNodeWithTag("research_dialog").assertExists()
        compose.onNodeWithText("依同源詞比較方言點相似度").assertExists()
        compose.onNodeWithTag("research_close").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("research_dialog").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "关闭后研究对话框应消失",
            compose.onAllNodesWithTag("research_dialog").fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithContentDescription("研究").assertExists()
    }

    /**
     * 聚類分析是全屏页：进入后检索主界面（Scaffold、模式切换、研究图标）整页消失，
     * 只剩聚類分析页；关闭后主界面恢复。
     */
    @Test
    fun clusterAnalysisIsFullScreen() {
        compose.onNodeWithContentDescription("研究").performClick()
        compose.onNodeWithTag("research_dialog").assertExists()
        compose.onNodeWithText("聚類分析").performClick()

        // 聚类要先从 info／cognates 读全量数据再在 IO 上计算，模拟器上明显慢于检索，
        // 因此用 30s 的 waitUntil 轮询全屏页 testTag；swiftshader 截图全黑不影响语义断言。
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("cluster_screen").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "进入聚類分析后应显示全屏页",
            compose.onAllNodesWithTag("cluster_screen").fetchSemanticsNodes().isNotEmpty()
        )
        // 全屏页替换检索界面：主界面的模式按钮与研究图标不应再出现在语义树里。
        assertTrue(
            "全屏页生效时主界面模式按钮不应存在",
            compose.onAllNodesWithText("搜字音").fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "全屏页生效时主界面研究图标不应存在",
            compose.onAllNodesWithContentDescription("研究").fetchSemanticsNodes().isEmpty()
        )

        compose.onNodeWithTag("cluster_close").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("cluster_screen").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("搜字音").assertExists()
        compose.onNodeWithContentDescription("研究").assertExists()
    }

    /**
     * 聚類分析摘要是「一行加粗的模組度／輪廓係數」，旧的三行摘要（可見方言點／自動分群 K／
     * 同分群輪廓係數）已删除；「在地圖展示」打开地图全屏页，关闭只退一层回到聚類分析页。
     */
    @Test
    fun clusterMapScreenOpensAndCloses() {
        compose.onNodeWithContentDescription("研究").performClick()
        compose.onNodeWithTag("research_dialog").assertExists()
        compose.onNodeWithText("聚類分析").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("cluster_screen").fetchSemanticsNodes().isNotEmpty()
        }
        // 全屏页先显示「正在計算」，真实库聚类要十几秒；等摘要出现再断言。
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText("模組度", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 摘要只剩一行：「模組度 X · 輪廓係數 Y」；旧三行不应再出现在语义树里。
        compose.onNodeWithText("模組度", substring = true).assertExists()
        assertTrue(
            "旧摘要「可見方言點」应已删除",
            compose.onAllNodesWithText("可見方言點", substring = true).fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "旧摘要「自動分群 K」应已删除",
            compose.onAllNodesWithText("自動分群 K", substring = true).fetchSemanticsNodes().isEmpty()
        )

        // 「在地圖展示」位于「顯示名稱」下方，先滚动到按钮再点。
        compose.onNodeWithTag("cluster_map_open").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("cluster_map_screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("map_labels_switch").assertExists()
        compose.onNodeWithTag("map_voronoi_switch").assertExists()

        // 关闭地图页只退一层：聚類分析页仍在。
        compose.onNodeWithTag("cluster_map_close").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("cluster_map_screen").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "关闭地图页后聚類分析页应仍在",
            compose.onAllNodesWithTag("cluster_screen").fetchSemanticsNodes().isNotEmpty()
        )

        compose.onNodeWithTag("cluster_close").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("cluster_screen").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("搜字音").assertExists()
        compose.onNodeWithContentDescription("研究").assertExists()
    }
}
