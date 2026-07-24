package org.siongdict.app.ui

import org.siongdict.app.data.CognateGroup

import org.siongdict.app.data.CharGroup

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

/**
 * Build export text from a CharGroup (used by 搜同源 cards).
 * The subtitle field holds the cognate group ID (e.g. "COVER_ɡɔm4").
 */
fun buildCharGroupExportText(group: CharGroup): String {
    val sb = StringBuilder()
    if (group.subtitle.isNotBlank()) {
        sb.append(group.subtitle)
        sb.append("\n")
    }
    group.entries.forEach { dialect ->
        dialect.prons.forEach { p ->
            sb.append(dialect.lang)
            sb.append("\t")
            sb.append(p.ipa)
            if (p.note.isNotBlank()) {
                sb.append("\t")
                sb.append(p.note)
            }
            sb.append("\n")
        }
    }
    return sb.toString().trimEnd()
}
