package org.siongdict.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.siongdict.app.data.Simplifier

/**
 * 「關於」面板：说明页（CHANGELOG 最新条目 + README）、简体渲染开关、重置資料庫。
 * 说明内容直接读 assets 里的两份文档，与仓库根目录的文件来自同一次构建拷贝。
 */
@Composable
internal fun InfoDialog(
    viewModel: SearchViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showResetConfirm by remember { mutableStateOf(false) }

    val latestChangelog = remember {
        try {
            val text = context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
            extractLatestChangelog(text)
        } catch (e: Exception) {
            ""
        }
    }
    val readmeText = remember {
        try {
            context.assets.open("README.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text(disp("重置資料庫")) },
            text = { Text(disp("將清除快取並從應用內重新載入資料庫，解決更新後的資料不一致問題。")) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    viewModel.resetDatabases()
                    onDismiss()
                }) { Text(disp("重置")) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(disp("取消")) }
            }
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(disp("關於"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text(disp("關閉")) }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AboutActionRow(
                        text = "重置資料庫",
                        onClick = { showResetConfirm = true }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 「渲染為簡體中文 (Beta)」：只影響顯示，不改資料也不改檢索（CONTEXT.md「簡體渲染」）。
                    // 開關狀態存在 siongdict_prefs，重置資料庫不會動它。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { Simplifier.setEnabled(context, !Simplifier.enabled) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            disp("渲染為簡體中文 (Beta)"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = Simplifier.enabled,
                            onCheckedChange = { Simplifier.setEnabled(context, it) }
                        )
                    }

                    HorizontalDivider()

                    if (latestChangelog.isNotBlank()) {
                        Text(disp(latestChangelog), fontSize = 13.sp, lineHeight = 20.sp)
                    }

                    Text(disp(readmeText), fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

/** 「關於」面板里的一行可点操作：左侧图标 ＋ 文字。 */
@Composable
private fun AboutActionRow(
    text: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        leadingIcon()
        Text(disp(text), fontSize = 15.sp, fontWeight = FontWeight.Medium)
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
