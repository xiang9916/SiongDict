package org.siongdict.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.siongdict.app.data.SearchMode
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.DialectEntry
import org.siongdict.app.data.CognateGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

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
            Column {
               TopAppBar(
                    title = {
                        val suffix = if (uiState.dbOutdated) " - 有更新" else ""
                        Text("湘典 (${uiState.appVersion}$suffix)", fontWeight = FontWeight.Bold)
                    },
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
                            FilterCheckboxItem("湘贛", uiState.filterXiangGan) { checked ->
                                viewModel.updateFilters(
                                    checked, uiState.filterZhongShangJiang, uiState.filterXiangHuaTuHua
                                )
                            }
                            FilterCheckboxItem("中上江和藍青", uiState.filterZhongShangJiang) { checked ->
                                viewModel.updateFilters(
                                    uiState.filterXiangGan, checked, uiState.filterXiangHuaTuHua
                                )
                            }
                            FilterCheckboxItem("鄉話和土話", uiState.filterXiangHuaTuHua) { checked ->
                                viewModel.updateFilters(
                                    uiState.filterXiangGan, uiState.filterZhongShangJiang, checked
                                )
                            }
                        }
                        IconButton(onClick = { showInfoDialog = true }) {
                           Icon(
                                Icons.Default.Info,
                                contentDescription = "關於",
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
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
                            key = { _, group -> groupKey(group) }
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
                                key = { _, group -> groupKey(group) }
                            ) { index, group ->
                                val navText = displayTitle(group.chars).take(2)
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

    if (showInfoDialog) {
        InfoDialog(viewModel = viewModel, onDismiss = { showInfoDialog = false })
    }
}

/** Stable LazyColumn key for a result card. */
private fun groupKey(group: CharGroup) = "${group.chars}_${group.subtitle}"

/** Card title with the inter-character spaces of 字組 removed. */
private fun displayTitle(chars: String) = chars.replace(" ", "")

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
                Text(label)
            }
        },
        onClick = {}
    )
}

/** Small copy button: copies the text built by [buildText] and shows a toast. */
@Composable
private fun CopyButton(buildText: () -> String) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    DisableSelection {
        IconButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(buildText()))
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

/**
 * Zero-height newline: participates in text selection so copied text keeps
 * line breaks between rows, without adding visual space.
 */
@Composable
private fun LineGap() {
    Text(text = "\n", modifier = Modifier.height(0.dp).clipToBounds())
}

@Composable
private fun InfoDialog(
    viewModel: SearchViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showResetConfirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }

    val latestChangelog = remember {
        try {
            val text = context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
            extractLatestChangelog(text)
        } catch (e: Exception) { "" }
    }
    val readmeText = remember {
        try {
            context.assets.open("README.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) { "" }
    }

   if (resetting) {
       AlertDialog(
           onDismissRequest = {},
           confirmButton = {},
           containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
           title = { Text("重置中") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("正在重新載入資料庫…")
                }
            }
        )
        LaunchedEffect(Unit) {
            viewModel.resetDatabases()
            resetting = false
            onDismiss()
        }
        return
    }

   if (showResetConfirm) {
       AlertDialog(
           onDismissRequest = { showResetConfirm = false },
           containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
           title = { Text("重置資料庫") },
            text = { Text("將清除快取並從應用內重新載入資料庫，解決更新後的資料不一致問題。") },
            confirmButton = {
                TextButton(onClick = { resetting = true }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
        return
    }

   Dialog(onDismissRequest = onDismiss) {
       Surface(
           shape = RoundedCornerShape(16.dp),
           color = MaterialTheme.colorScheme.surfaceContainerLow,
           tonalElevation = 0.dp,
           modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("關於", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("關閉") }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showResetConfirm = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "重置資料庫",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider()

                    if (latestChangelog.isNotBlank()) {
                        Text(latestChangelog, fontSize = 13.sp, lineHeight = 20.sp)
                    }

                    HorizontalDivider()

                    Text(readmeText, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

/** Return the first "### version" section of the changelog (header line included). */
private fun extractLatestChangelog(changelog: String): String {
    val lines = changelog.lines()
    val first = lines.indexOfFirst { it.startsWith("### ") }
    if (first == -1) return changelog
    val rest = lines.drop(first)
    val next = rest.indexOfFirst { it.startsWith("### ") }
    val section = if (next == -1) rest else rest.subList(0, next)
    return section.joinToString("\n").trim()
}

@Composable
private fun ResultCard(group: CharGroup) {
    val displayChars = displayTitle(group.chars)
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
        SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
               .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
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
                    DisableSelection {
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
                    CopyButton { buildCharGroupExportText(group) }
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
}

@Composable
private fun DialectBlock(dialect: DialectEntry) {
    var expanded by rememberSaveable { mutableStateOf(false) }
   Column(
       modifier = Modifier
           .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
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
                DisableSelection {
               Text(
                   text = "同源 ${dialect.cognate.members.size} 詞",
                   fontSize = 11.sp,
                   color = MaterialTheme.colorScheme.tertiary,
                   modifier = Modifier.clickable { expanded = !expanded }
               )
                }
           }
        }
        // 读音行：IPA 左、註釋右
        dialect.prons.forEach { p ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )) {
                        append(p.ipa)
                    }
                    if (p.note.isNotBlank()) {
                        withStyle(SpanStyle(
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )) {
                            append(" ${p.note}")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp)
            )
            LineGap()
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
            verticalArrangement = Arrangement.spacedBy(1.dp)
       ) {
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
                 CopyButton { buildCognateExportText(group) }
            }
            LineGap()
            group.members.forEach { m ->
                val isCurrent = m.lang == currentLang
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                   Text(
                       text = m.lang,
                       fontSize = 12.sp,
                        lineHeight = 14.sp,
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
                LineGap()
                }
            }
        }
    }
