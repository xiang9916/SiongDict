package org.siongdict.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 界面回归测试（真机/模拟器）：首帧渲染（含内联图标与只读库打开）、
 * **重复字检索不再因重复 key 崩溃**、三种模式、方言筛选菜单、关于面板。
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
    fun dialectFilterMenuOpens() {
        compose.onNodeWithContentDescription("方言篩選").performClick()
        compose.onNodeWithText("湘贛").assertExists()
        compose.onNodeWithText("中上江和藍青").assertExists()
        compose.onNodeWithText("鄉話和土話").assertExists()
        // 整行可点（本次修复）：点文字行不应抛异常
        compose.onNodeWithText("湘贛").performClick()
    }

    @Test
    fun aboutDialogOpensAndCloses() {
        compose.onNodeWithContentDescription("關於").performClick()
        compose.onNodeWithText("渲染為簡體中文 (Beta)").assertExists()
        compose.onNodeWithText("重置資料庫").assertExists()
        compose.onNodeWithText("關閉").performClick()
    }
}
