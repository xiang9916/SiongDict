package org.siongdict.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.siongdict.app.data.AppInfo
import org.siongdict.app.data.EyeCare
import org.siongdict.app.data.Simplifier
import org.siongdict.app.data.UpdateState

/**
 * 「關於」面板：说明页（CHANGELOG 最新条目 + README）、檢查更新、护眼模式、
 * 简体渲染开关、重置資料庫。说明内容直接读 assets 里的两份文档，
 * 与仓库根目录的文件来自同一次构建拷贝。
 */
@Composable
internal fun InfoDialog(
    viewModel: SearchViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showResetConfirm by remember { mutableStateOf(false) }
    val currentVersion = remember { AppInfo.versionName(context) }

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
            containerColor = MaterialTheme.colorScheme.background,
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
            color = MaterialTheme.colorScheme.background,
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
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("info_close")
                    ) { Text(disp("關閉")) }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 檢查更新：启动时已自动查过一次，这里可手动重试；有新版本时点按打开 Release 页。
                    UpdateRow(currentVersion = currentVersion)

                    AboutActionRow(
                        text = "重置資料庫",
                        onClick = { showResetConfirm = true }
                    ) {
                        Icon(
                            Icons.Default.Delete,
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

                    // 护眼模式：默认关闭（纯白底）；开启后界面与弹窗都换成淡黄底。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { EyeCare.setEnabled(context, !EyeCare.enabled) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            disp("啟用護眼模式"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = EyeCare.enabled,
                            onCheckedChange = { EyeCare.setEnabled(context, it) },
                            modifier = Modifier.testTag("eye_care_switch")
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

/**
 * 「檢查更新」一行：状态文案由 [UpdateState] 派生，点按的行为随状态变化。
 * 首次查询在 MainActivity 启动时发起；这里负责手动重试与打开 Release 页。
 */
@Composable
private fun UpdateRow(currentVersion: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result = UpdateState.result
    val checking = UpdateState.checking
    val hasUpdate = UpdateState.hasUpdate(currentVersion)
    val failed = result?.error != null

    val status = when {
        checking -> "檢查中…"
        failed -> "檢查失敗：${result?.error}（點按重試）"
        hasUpdate -> "有新版本 ${result?.latestTag}（點按查看）"
        result?.latestTag != null -> "已是最新版本"
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !checking) {
                val tag = result?.latestTag
                if (hasUpdate && tag != null) {
                    openReleasePage(context, tag)
                } else {
                    scope.launch { UpdateState.refresh(context, currentVersion) }
                }
            }
            .testTag("info_update_row")
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(disp("檢查更新"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (status != null) {
                Text(
                    text = disp(status),
                    fontSize = 12.sp,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 用系统浏览器打开对应 tag 的 Release 页；没有浏览器时静默失败（不影响面板其它操作）。 */
private fun openReleasePage(context: Context, tag: String) {
    try {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/xiang9916/SiongDict/releases/tag/$tag")
            )
        )
    } catch (e: Exception) {
        Log.w("InfoDialog", "無法開啟 Release 頁面", e)
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
