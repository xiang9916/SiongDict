package org.siongdict.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 界面回归测试（真机/模拟器）：首帧渲染（含内联图标与只读库打开）、
 * **重复字检索不再因重复 key 崩溃**、三种模式、方言筛选对话框、关于面板。
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
        compose.onNodeWithText("方言篩選").assertExists()
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
        compose.onNodeWithText("全不選").assertExists()
        // 点「全不選」清空全部叶子选中状态，关闭后再检索：结果为空但不得崩。
        compose.onNodeWithText("全不選").performClick()
        compose.onNodeWithText("關閉").performClick()
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
        compose.onNodeWithText("渲染為簡體中文 (Beta)").assertExists()
        compose.onNodeWithText("重置資料庫").assertExists()
        compose.onNodeWithText("關閉").performClick()
    }

    /**
     * 研究入口是二级结构：研究图示 →「研究」对话框 → 点「聚類分析」→ 读同源词库并计算聚类 →
     * 出现「最小義項數」参数面板（任务说明写作「最少義項」，实际生产文案为「最小義項數」）→ 关闭。
     *
     * 聚类要先从 info／cognates 读全量数据再在 IO 上计算，模拟器上明显慢于检索，
     * 因此用 30s 的 waitUntil 轮询 Compose 语义树；swiftshader 截图全黑不影响语义断言。
     */
    @Test
    fun researchOpensClusterAnalysis() {
        compose.onNodeWithContentDescription("研究").performClick()
        compose.onNodeWithText("研究").assertExists()
        compose.onNodeWithText("依同源詞比較方言點相似度").assertExists()

        compose.onNodeWithText("聚類分析").performClick()

        // 参数面板只在聚类结果算完后出现，是「已加载完成」的可靠信号。
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("最小義項", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("最少義項", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "聚類分析应显示「最小義項數」参数",
            compose.onAllNodesWithText("最小義項", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("最少義項", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithText("聚類分析").assertExists()

        // 关闭后回到检索主界面，研究入口仍在（对话框确实被销毁）。
        compose.onNodeWithText("關閉").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("聚類分析").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "关闭后聚類分析对话框应消失",
            compose.onAllNodesWithText("聚類分析").fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithContentDescription("研究").assertExists()
    }
}
