package org.siongdict.app.ui

import org.siongdict.app.data.CharGroup
import org.siongdict.app.data.CognateGroup

/** Append one export line: "lang<TAB>ipa[<TAB>note]<newline>". */
private fun StringBuilder.appendMemberLine(lang: String, ipa: String, note: String) {
    append(lang)
    append('\t')
    append(ipa)
    if (note.isNotBlank()) {
        append('\t')
        append(note)
    }
    append('\n')
}

fun buildCognateExportText(group: CognateGroup): String {
    val sb = StringBuilder()
    if (group.semanticLabel.isNotBlank()) {
        sb.append("義類：${group.semanticLabel} ")
    }
    sb.append(group.groupId)
    sb.append("\n")
    group.members.forEach { m ->
        sb.appendMemberLine(m.lang, m.ipa, m.note)
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
            sb.appendMemberLine(dialect.lang, p.ipa, p.note)
        }
    }
    return sb.toString().trimEnd()
}
