package org.siongdict.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.siongdict.app.data.SearchMode
import org.siongdict.app.data.SearchResult
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.DialectEntry
import org.siongdict.app.data.CognateGroup
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
   var showResetDialog by remember { mutableStateOf(false) }
   var showFilterMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("湘典", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showFilterMenu = true }) {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = "方言篩選",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Checkbox(
                                            checked = uiState.filterXiangGan,
                                            onCheckedChange = {
                                                viewModel.updateFilters(
                                                    it, uiState.filterZhongShangJiang, uiState.filterXiangHuaTuHua
                                                )
                                            }
                                        )
                                        Text("湘贛")
                                    }
                                },
                                onClick = {}
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Checkbox(
                                            checked = uiState.filterZhongShangJiang,
                                            onCheckedChange = {
                                                viewModel.updateFilters(
                                                    uiState.filterXiangGan, it, uiState.filterXiangHuaTuHua
                                                )
                                            }
                                        )
                                        Text("中上江和藍青")
                                    }
                                },
                                onClick = {}
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Checkbox(
                                            checked = uiState.filterXiangHuaTuHua,
                                            onCheckedChange = {
                                                viewModel.updateFilters(
                                                    uiState.filterXiangGan, uiState.filterZhongShangJiang, it
                                                )
                                            }
                                        )
                                        Text("鄉話和土話")
                                    }
                                },
                                onClick = {}
                            )
                        }
                        IconButton(onClick = { showResetDialog = true }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "重置資料庫",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF8B0000),
                        titleContentColor = Color.White
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
               placeholder = {
                   Text(when (uiState.mode) {
                       SearchMode.CHAR -> "輸入漢字檢索"
                       SearchMode.COGNATE -> "輸入中英義項或構擬祖型檢索"
                       SearchMode.MEANING -> "輸入注釋內容匹配檢索"
                   })
               },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search)
            )

            // Mode selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = uiState.mode == mode,
                        onClick = { viewModel.updateMode(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Results
            if (uiState.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.searched && uiState.results.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.error != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "查詢出錯",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = uiState.error!!,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Text("無結果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            uiState.results,
                            key = { _, group -> "${group.chars}_${group.subtitle}" }
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
                        LazyColumn(
                            modifier = Modifier
                                .width(36.dp)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            itemsIndexed(
                                uiState.results,
                                key = { _, group -> "${group.chars}_${group.subtitle}" }
                            ) { index, group ->
                                val navText = group.chars.replace(" ", "").let {
                                    if (it.length <= 2) it else it.take(2)
                                }
                                Text(
                                    text = navText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(vertical = 2.dp)
                                        .clickable {
                                            scope.launch { listState.animateScrollToItem(index) }
                                        }
                                )
                            }
                        }
                    }
                }
            }
       }
    }

    if (showResetDialog) {
        ResetConfirmDialog(viewModel = viewModel, onDismiss = { showResetDialog = false })
    }
}

@Composable
private fun ResetConfirmDialog(
    viewModel: SearchViewModel,
    onDismiss: () -> Unit
) {
    var resetting by remember { mutableStateOf(false) }

    if (resetting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("重置中") },
            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text("正在重新載入資料庫…") } }
        )
        LaunchedEffect(Unit) {
            viewModel.resetDatabases()
            resetting = false
            onDismiss()
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重置資料庫") },
        text = { Text("將清除快取並從應用內重新載入資料庫，解決更新後的資料不一致問題。") },
        confirmButton = {
            TextButton(onClick = { resetting = true }) { Text("重置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ResultCard(group: CharGroup) {
    val displayChars = group.chars.replace(" ", "")
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val titleSize = when {
        displayChars.length <= 2 -> 28.sp
        displayChars.length <= 4 -> 22.sp
        else -> 16.sp
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
           // 字組标题 + 方言数
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = displayChars,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (group.entries.size > 1) {
                    Row(
                        modifier = Modifier.clickable { collapsed = !collapsed },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "${group.entries.size} 點",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (collapsed) "＞" else "∨",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (group.subtitle.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = group.subtitle,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    val context = LocalContext.current
                    val clipboardManager = LocalClipboardManager.current
                    IconButton(
                        onClick = {
                            val exportText = buildCharGroupExportText(group)
                            clipboardManager.setText(AnnotatedString(exportText))
                            Toast.makeText(context, "已複製同源詞", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "複製同源詞",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            // 各方言点读音
            if (!collapsed) {
                group.entries.forEach { dialect ->
                    DialectBlock(dialect)
                }
            }
        }
    }
}

@Composable
private fun DialectBlock(dialect: DialectEntry) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        // 方言名行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = dialect.lang,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            if (dialect.cognate != null && dialect.cognate.members.size > 1) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "同源 ${dialect.cognate.members.size} 詞",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
        }
        // 读音行：IPA 左、註釋右
        dialect.prons.forEach { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = p.ipa,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = p.note,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // 同源词展开
        if (expanded && dialect.cognate != null) {
            CognateExpand(dialect.cognate, dialect.lang)
        }
    }
}

@Composable
private fun CognateExpand(group: CognateGroup, currentLang: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 2.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val context = LocalContext.current
            val clipboardManager = LocalClipboardManager.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val headerText = if (group.semanticLabel.isNotBlank()) {
                    "義類：${group.semanticLabel} ${group.groupId}"
                } else {
                    group.groupId
                }
                Text(
                    text = headerText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val exportText = buildCognateExportText(group)
                        clipboardManager.setText(AnnotatedString(exportText))
                        Toast.makeText(context, "已複製同源詞", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "複製同源詞",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            group.members.forEach { m ->
                val isCurrent = m.lang == currentLang
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = m.lang,
                        fontSize = 12.sp,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = m.ipa,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isCurrent) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
