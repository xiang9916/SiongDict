package org.siongdict.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.CognateGroup
import org.siongdict.app.data.DialectEntry
import org.siongdict.app.data.Simplifier
import org.siongdict.app.ui.icons.SiongIcons

/** 搜尋結果卡片：标题（字組或義項標籤）＋ 方言點數 ＋ 逐方言點读音。 */
@Composable
internal fun ResultCard(group: CharGroup) {
    val displayChars = cardTitle(group)
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
                // 标题行：字組／義項標籤 ＋（多于一个方言点时的）折叠开关
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
                                    text = disp("${group.entries.size} 點"),
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
                        CopyButton { Simplifier.display(buildCharGroupExportText(group)) }
                    }
                }

                if (!collapsed) {
                    group.entries.forEach { dialect -> DialectBlock(dialect) }
                }
            }
        }
    }
}

@Composable
private fun DialectBlock(dialect: DialectEntry) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val cognate = dialect.cognate
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // 方言名行（＋该点同源词展开开关）
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
                text = disp(dialect.lang),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            if (cognate != null && cognate.members.size > 1) {
                Spacer(modifier = Modifier.weight(1f))
                DisableSelection {
                    Text(
                        text = disp("同源 ${cognate.members.size} 詞"),
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
                    withStyle(
                        SpanStyle(
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        append(p.ipa)
                    }
                    if (p.note.isNotBlank()) {
                        withStyle(
                            SpanStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            append(" ${disp(p.note)}")
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
        if (expanded && cognate != null) {
            CognateExpand(cognate, dialect.lang)
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
                    "${disp("義類：${group.semanticLabel}")} ${group.groupId}"
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
                CopyButton { Simplifier.display(buildCognateExportText(group)) }
            }
            LineGap()
            group.members.forEach { m ->
                val isCurrent = m.lang == currentLang
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = disp(m.lang),
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

/** Zero-height newline: participates in text selection so copied text keeps line breaks between rows. */
@Composable
private fun LineGap() {
    Text(text = "\n", modifier = Modifier.height(0.dp).clipToBounds())
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
                Toast.makeText(context, disp("已複製同源詞"), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                SiongIcons.ContentCopy,
                contentDescription = "複製同源詞",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}
