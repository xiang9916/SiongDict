package org.siongdict.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siongdict.app.analysis.ClusterAnalysisResult
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 「聚類分析」全屏页（研究入口）：参数调节 + 三维力导向散点图 + 选中点详情。
 *
 * 全屏而非对话框：画布需要尽量大的触控区域，且返回键应与「關閉」一致地退出。
 * 页面只负责展示与转发交互；聚类、连线与代际守卫都在 [SearchViewModel] 里完成。
 * - 分類數滑桿只切换分区（[SearchViewModel.setClusterCount]），不重算；
 * - 最小義項數滑桿在松手时才提交（[SearchViewModel.setMinTags]），拖动期间只改本地草稿，
 *   避免每动一格就重算一次全量聚类。
 */
@Composable
internal fun ClusterAnalysisScreen(
    state: ClusterUiState,
    onDismiss: () -> Unit,
    onClusterCount: (Int?) -> Unit,
    onMinTags: (Int) -> Unit,
    onSelectPoint: (String?) -> Unit
) {
    // 全屏页没有 Dialog 的返回键处理，这里显式接管：返回键 = 關閉。
    BackHandler(onBack = onDismiss)
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .testTag("cluster_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(disp("聚類分析"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("cluster_close")) {
                    Text(disp("關閉"))
                }
            }
            HorizontalDivider()

                when {
                    state.error != null && state.result == null -> MessagePane(
                        title = disp("聚類分析失敗"),
                        message = disp(state.error.orEmpty()),
                        isError = true
                    )
                    state.loading && state.result == null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                disp("正在讀取同源詞資料並計算聚類…"),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    state.result == null -> MessagePane(
                        title = disp("暫無資料"),
                        message = disp("尚未載入同源詞資料。"),
                        isError = false
                    )
                    else -> ClusterAnalysisBody(
                        state = state,
                        result = state.result,
                        onClusterCount = onClusterCount,
                        onMinTags = onMinTags,
                        onSelectPoint = onSelectPoint
                    )
                }
            }
        }
}

/** 页面主体：摘要 → 参数 → 画布 → 选中点 → 类别图例 → 说明。 */
@Composable
private fun ClusterAnalysisBody(
    state: ClusterUiState,
    result: ClusterAnalysisResult,
    onClusterCount: (Int?) -> Unit,
    onMinTags: (Int) -> Unit,
    onSelectPoint: (String?) -> Unit
) {
    val maxK = state.maxK
    val optimalK = result.optimalK
    var showLabels by remember(result) { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 摘要
        Text(
            text = disp(
                "可見方言點 ${result.visibleDialects} 個 · 義項 ${result.totalTags} 個" +
                    " · 無同源詞排除 ${result.excludedNoCognates} · 義項不足排除 ${result.excludedSparse}"
            ),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = disp(
                "自動分群 K = ${optimalK}（社群偵測，模組度 ${formatScore(result.modularity)}）"
            ),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = disp("同分群的輪廓係數 ${formatScore(result.optimalSilhouette)}，僅供對照。"),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (result.sparse) {
            Text(
                text = disp("資料稀疏（模組度 < 0.15），分群結果僅供參考。"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 参数面板
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ClusterCountControl(
                    selectedK = state.selectedK,
                    optimalK = optimalK,
                    maxK = maxK,
                    onClusterCount = onClusterCount
                )
                MinTagsControl(
                    minTags = state.minTags,
                    maxMinTags = state.maxMinTags,
                    onMinTags = onMinTags
                )
            }
        }

        // 画布
        if (result.points.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        // 0 可见点通常是方言筛选把全部点排除了，这里给出可操作的解释
                        disp(
                            if (result.visibleDialects == 0) {
                                "目前篩選下沒有可分析的方言點"
                            } else {
                                "沒有可顯示的方言點"
                            }
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(disp("顯示名稱"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        disp("點依分群聚攏；切換分類數只重新聚攏"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        disp("單指旋轉 · 雙指縮放平移 · 雙擊復位"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = showLabels, onCheckedChange = { showLabels = it })
            }
            ClusterMap(
                result = result,
                partition = state.partition,
                edges = state.edges,
                selectedLang = state.selectedLang,
                highlightedCluster = state.highlightedCluster,
                showLabels = showLabels,
                onSelect = onSelectPoint,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            )
        }

        // 选中点详情
        val selected = result.points.firstOrNull { it.lang == state.selectedLang }
        if (selected != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = disp("${selected.lang}（${selected.division}）"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = disp("義項 ${selected.tagCount} 個 · 同源詞條目 ${selected.entryCount} 條"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = disp("再點一次節點取消選取"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 类别图例
        val clusters = state.partition?.clusters.orEmpty()
        when {
            clusters.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(clusters, key = { index, _ -> index }) { index, cluster ->
                    ClusterChip(
                        index = index,
                        size = cluster.size,
                        highlighted = state.highlightedCluster == index
                    )
                }
            }
            result.points.size >= 3 -> Text(
                text = disp("方言點不足或相似度不足，無法形成 ${maxK} 個以上的分群。"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 说明
        Text(
            text = disp(
                "相似度按共同義項計分：共享同源詞組 +1、無共享 −1，範圍 −1..1；" +
                    "畫布是三維力導向排版後的透視投影：單指拖動旋轉視角、雙指捏合縮放並平移、雙擊復原。" +
                    "「自動」分群用加權圖的社群偵測（模組度最大化），分類數滑桿只切換分群的分區、不重算。" +
                    "節點顏色為音典顏色，虛線凸包與色塊為聚類類別，連線為每個方言點前 2 條正相似邊。"
            ),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 「分類數」控件：滑桿切換分區，另给一个「自動」按钮回到 optimalK。 */
@Composable
private fun ClusterCountControl(
    selectedK: Int?,
    optimalK: Int,
    maxK: Int,
    onClusterCount: (Int?) -> Unit
) {
    if (maxK < 3) {
        Text(
            text = disp("分類數：方言點不足（需要至少 3 個可比較的方言點）"),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val automatic = selectedK == null
    val sliderValue = (selectedK ?: optimalK).coerceIn(2, maxK).toFloat()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = disp(
                if (automatic) "分類數：自動（K = $optimalK）" else "分類數：K = ${selectedK ?: optimalK}"
            ),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        if (!automatic) {
            TextButton(onClick = { onClusterCount(null) }) { Text(disp("自動")) }
        }
    }
    Slider(
        value = sliderValue,
        onValueChange = { onClusterCount(it.roundToInt()) },
        valueRange = 2f..maxK.toFloat(),
        steps = (maxK - 3).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 「最小義項數」控件：拖动只改本地草稿，松手才提交。 */
@Composable
private fun MinTagsControl(
    minTags: Int,
    maxMinTags: Int,
    onMinTags: (Int) -> Unit
) {
    // 上限由快照算出、恒 ≥ 当前 minTags；这里再兜底一次，保证滑桿范围永远包含当前值。
    val upper = maxMinTags.coerceAtLeast(minTags).coerceAtLeast(1)
    if (upper < 2) {
        Text(
            text = disp("最小義項數：所有方言點義項數相同（${minTags}）"),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    var draft by remember(minTags, upper) { mutableFloatStateOf(minTags.toFloat()) }
    val draftValue = draft.roundToInt().coerceIn(1, upper)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(disp("最小義項數：${draftValue}"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            disp("過濾義項過少的方言點"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Slider(
        value = draftValue.toFloat(),
        onValueChange = { draft = it },
        onValueChangeFinished = {
            val next = draft.roundToInt().coerceIn(1, upper)
            if (next != minTags) onMinTags(next)
        },
        valueRange = 1f..upper.toFloat(),
        steps = (upper - 2).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 一个类别图例块：调色盘色点 + 「第 n 類 · m 點」；选中点的类别会描边。 */
@Composable
private fun ClusterChip(index: Int, size: Int, highlighted: Boolean) {
    val color = Color(paletteColor(index))
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = if (highlighted) 0.28f else 0.14f),
        border = if (highlighted) BorderStroke(1.5.dp, color) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(shape = RoundedCornerShape(50), color = color, modifier = Modifier.size(8.dp)) {}
            Text(
                text = disp("第 ${index + 1} 類 · ${size} 點"),
                fontSize = 12.sp,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/** 错误 / 空态时的居中提示。 */
@Composable
private fun MessagePane(title: String, message: String, isError: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 分数显示：固定两位小数。 */
private fun formatScore(value: Float): String =
    String.format(Locale.ROOT, "%.2f", value)
