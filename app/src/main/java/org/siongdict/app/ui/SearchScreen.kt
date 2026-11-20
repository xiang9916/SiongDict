package org.siongdict.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import org.siongdict.app.data.UpdateState
import kotlinx.coroutines.launch
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.SearchMode
import org.siongdict.app.ui.icons.SiongIcons
import org.siongdict.app.ui.theme.SiongDarkRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val clusterState by viewModel.clusterState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showInfoDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showResearchDialog by remember { mutableStateOf(false) }

    // 聚类分析是全屏页，不套 Dialog：进入后整页替换检索界面，返回键与「關閉」都走 onDismiss。
    if (clusterState.visible) {
        ClusterAnalysisScreen(
            state = clusterState,
            onDismiss = viewModel::closeClusterAnalysis,
            onClusterCount = viewModel::setClusterCount,
            onMinTags = viewModel::setMinTags,
            onSelectPoint = viewModel::selectClusterPoint
        )
    } else {
        // 有新版时「關於」图标原地旋转：0→180° 停在「!」的形状，再转到 360° 回到「i」，循环往复。
        // 只在发现新版本时才创建无限动画，避免无更新时常驻重组。
        val hasUpdate = UpdateState.hasUpdate(uiState.appVersion)
        val infoRotation = if (hasUpdate) {
            val spin = rememberInfiniteTransition(label = "update-icon")
            val spinAngle by spin.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 2000
                        0f at 0
                        180f at 500
                        180f at 1000
                        360f at 1500
                        360f at 2000
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "update-icon-angle"
            )
            spinAngle
        } else {
            0f
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val suffix = if (uiState.dbOutdated) " - 有更新" else ""
                        Text("湘典 (${uiState.appVersion}$suffix)", fontWeight = FontWeight.Bold)
                    },
                    actions = {
                        IconButton(onClick = { showResearchDialog = true }) {
                            Icon(
                                SiongIcons.Research,
                                contentDescription = "研究",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(
                                SiongIcons.Visibility,
                                contentDescription = "方言篩選",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { showInfoDialog = true }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "關於",
                                tint = Color.White,
                                modifier = Modifier.rotate(infoRotation)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SiongDarkRed,
                        titleContentColor = Color.White
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                SearchBar(
                    query = uiState.query,
                    mode = uiState.mode,
                    onQueryChange = viewModel::updateQuery,
                    onSearch = viewModel::search
                )
                ModeSelector(selected = uiState.mode, onSelect = viewModel::updateMode)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ResultsPane(
                    uiState = uiState,
                    listState = listState,
                    onJumpTo = { index -> scope.launch { listState.animateScrollToItem(index) } }
                )
            }
        }

        if (showFilterDialog) {
            DialectFilterDialog(
                tree = uiState.dialectTree,
                filters = uiState.filters,
                onDismiss = { showFilterDialog = false },
                onFiltersChange = viewModel::updateFilters
            )
        }

        if (showInfoDialog) {
            InfoDialog(viewModel = viewModel, onDismiss = { showInfoDialog = false })
        }

        if (showResearchDialog) {
            ResearchDialog(
                onDismiss = { showResearchDialog = false },
                onOpenClusterAnalysis = {
                    showResearchDialog = false
                    viewModel.openClusterAnalysis()
                }
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    mode: SearchMode,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = {
            Text(
                disp(
                    when (mode) {
                        SearchMode.CHAR -> "輸入漢字檢索"
                        SearchMode.COGNATE -> "輸入中英義項或構擬祖型檢索"
                        SearchMode.MEANING -> "輸入注釋內容匹配檢索"
                    }
                )
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "清除")
                }
            }
        },
        singleLine = true,
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedBorderColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun ModeSelector(selected: SearchMode, onSelect: (SearchMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SearchMode.entries.forEach { mode ->
            val isSelected = selected == mode
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(mode) },
                label = { Text(disp(mode.label)) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = SiongDarkRed,
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = SiongDarkRed,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.dp
                )
            )
        }
    }
}

@Composable
private fun ResultsPane(
    uiState: SearchUiState,
    listState: LazyListState,
    onJumpTo: (Int) -> Unit
) {
    when {
        uiState.loading -> CenteredBox { CircularProgressIndicator() }
        uiState.searched && uiState.results.isEmpty() -> CenteredBox {
            val error = uiState.error
            if (error != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = disp("查詢出錯"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = disp(error),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            } else {
                Text(disp("無結果"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
                    uiState.results,
                    key = { index, group -> resultKey(index, group) }
                ) { _, group ->
                    ResultCard(group)
                }
            }
            if (uiState.results.size > 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                ResultNavRail(
                    results = uiState.results,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    onJumpTo = onJumpTo
                )
            }
        }
    }
}

/** 结果列表右侧的竖向快速跳转条（只显示前两个字）。 */
@Composable
private fun ResultNavRail(
    results: List<CharGroup>,
    contentPadding: PaddingValues,
    onJumpTo: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .width(36.dp)
            .fillMaxHeight(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(
            results,
            key = { index, group -> resultKey(index, group) }
        ) { index, group ->
            Text(
                text = displayTitle(group.chars).take(2),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .clickable { onJumpTo(index) }
            )
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * LazyColumn 的稳定 key：同一张卡在列表中唯一。
 * 逐字检索时同一个字可能出现两次（如「哈哈」），只用 chars＋subtitle 会撞 key 而崩溃，
 * 因此把列表下标也带上。
 */
private fun resultKey(index: Int, group: CharGroup) =
    "${index}:${group.chars}_${group.subtitle}"

/**
 * 方言筛选对话框：摊平整棵分区树，全部展开、不可折叠。
 *
 * 每行列出一个节点（缩进体现层级），整行点击切换该节点下的全部叶子；
 * 父节点显示子树三态。变更即时套用到检索（[DialectFilters]），不需要关对话框。
 */
@Composable
private fun DialectFilterDialog(
    tree: List<DialectNode>,
    filters: DialectFilters,
    onDismiss: () -> Unit,
    onFiltersChange: (DialectFilters) -> Unit
) {
    val rows = remember(tree) { flattenDialectTree(tree) }
    val allLeaves = remember(tree) { allLeafPaths(tree) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .testTag("filter_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                // 三個操作按钮像「關於」一样放在标题行右侧：全選 / 全不選 / 關閉。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disp("方言篩選"),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { onFiltersChange(DialectFilters(null)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("filter_select_all")
                    ) { Text(disp("全選"), fontSize = 13.sp) }
                    TextButton(
                        onClick = { onFiltersChange(DialectFilters(emptySet())) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("filter_select_none")
                    ) { Text(disp("全不選"), fontSize = 13.sp) }
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("filter_close")
                    ) { Text(disp("關閉"), fontSize = 13.sp) }
                }
                HorizontalDivider()
                if (rows.isEmpty()) {
                    Text(
                        text = disp("暫無分區資料"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(rows, key = { _, row -> row.first.path }) { _, row ->
                            val (node, depth) = row
                            DialectFilterRow(
                                node = node,
                                depth = depth,
                                state = triStateOf(nodeLeafPaths(node), filters.selected),
                                onToggle = {
                                    onFiltersChange(
                                        DialectFilters(
                                            toggleLeaves(filters.selected, nodeLeafPaths(node), allLeaves)
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一组叶子的三态：未筛选（selected == null）视为全选。 */
private fun triStateOf(leaves: Set<String>, selected: Set<String>?): ToggleableState = when {
    selected == null -> ToggleableState.On
    leaves.all { it in selected } -> ToggleableState.On
    leaves.none { it in selected } -> ToggleableState.Off
    else -> ToggleableState.Indeterminate
}

/** 一行方言节点：整行点击切换该节点下的全部叶子；父节点按子树显示三态。 */
@Composable
private fun DialectFilterRow(
    node: DialectNode,
    depth: Int,
    state: ToggleableState,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 勾选状态由整行的 onClick 负责，避免勾选框与行各触发一次
        TriStateCheckbox(state = state, onClick = null)
        Text(
            text = disp(node.name),
            fontWeight = if (depth == 0) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = disp("${node.pointCount} 點"),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
