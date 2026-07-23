package org.siongdict.app.ui

import org.siongdict.app.data.CognateGroup

fun buildCognateExportText(group: CognateGroup): String {
    val sb = StringBuilder()
    if (group.semanticLabel.isNotBlank()) {
        sb.append("義類：${group.semanticLabel} ")
    }
    sb.append(group.groupId)
    sb.append("\n")
    group.members.forEach { m ->
        sb.append(m.lang)
        sb.append("\t")
        sb.append(m.ipa)
        if (m.note.isNotBlank()) {
            sb.append("\t")
            sb.append(m.note)
        }
        sb.append("\n")
    }
    return sb.toString().trimEnd()
}
