package org.siongdict.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「研究」对话框：研究图标的一级入口，目前只有「聚類分析」一项。
 *
 * 点该行才会进入全螢幕的 [ClusterAnalysisDialog]；本对话框自身不触发任何计算，
 * 关闭或点行都由调用方（SearchScreen）收拢状态。
 */
@Composable
internal fun ResearchDialog(
    onDismiss: () -> Unit,
    onOpenClusterAnalysis: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(disp("研究"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenClusterAnalysis)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(disp("聚類分析"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = disp("依同源詞比較方言點相似度"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "›",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(disp("關閉")) }
        }
    )
}
