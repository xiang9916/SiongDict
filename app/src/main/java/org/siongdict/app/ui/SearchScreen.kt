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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.SearchMode
import org.siongdict.app.ui.icons.SiongIcons
import org.siongdict.app.ui.theme.SiongDarkRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showInfoDialog by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val suffix = if (uiState.dbOutdated) " - 有更新" else ""
                    Text("湘典 (${uiState.appVersion}$suffix)", fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = { showFilterMenu = true }) {
                        Icon(
                            SiongIcons.Visibility,
                            contentDescription = "方言篩選",
                            tint = Color.White
                        )
                    }
                    DialectFilterMenu(
                        expanded = showFilterMenu,
                        filters = uiState.filters,
                        onDismiss = { showFilterMenu = false },
                        onFiltersChange = viewModel::updateFilters
                    )
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "關於",
                            tint = Color.White
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

    if (showInfoDialog) {
        InfoDialog(viewModel = viewModel, onDismiss = { showInfoDialog = false })
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
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
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(disp(mode.label)) }
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

/** 方言筛选下拉菜单。 */
@Composable
private fun DialectFilterMenu(
    expanded: Boolean,
    filters: DialectFilters,
    onDismiss: () -> Unit,
    onFiltersChange: (DialectFilters) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FilterCheckboxItem("湘贛", filters.xiangGan) {
            onFiltersChange(filters.copy(xiangGan = it))
        }
        FilterCheckboxItem("中上江和藍青", filters.zhongShangJiang) {
            onFiltersChange(filters.copy(zhongShangJiang = it))
        }
        FilterCheckboxItem("鄉話和土話", filters.xiangHuaTuHua) {
            onFiltersChange(filters.copy(xiangHuaTuHua = it))
        }
    }
}

/** One checkbox row inside the dialect-filter dropdown. */
@Composable
private fun FilterCheckboxItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                Text(disp(label))
            }
        },
        onClick = {}
    )
}
