package org.siongdict.app.data

import android.database.Cursor
import io.requery.android.database.sqlite.SQLiteDatabase

/**
 * 同源词库（cognates.db，可选依赖）的只读端口。
 * 库缺失时实现退化为空结果：search 返回空列表、groupFor 返回 null，
 * UI 的「搜同源」自动停用（与拆分前一致）。
 */
interface CognateBook {

    suspend fun search(query: String): List<CognateGroup>

    /**
     * 按 (方言点, 读音) 反查同源词组；[chars] 非空时优先按字组精确匹配。
     *
     * 同一 (lang, ipa) 可能对应多个字组（同音不同字、分属不同义项组）。只按 (lang, ipa)
     * 取首行会把「冷」显示到「□」所在的组（汨羅長樂 loŋ3 即一例）。字组查不到时回退旧行为：
     * 存量 245 条 cognates.chars 与本库 langs.字組 不一致（多为记「□」），收紧条件会丢显示。
     */
    suspend fun groupFor(lang: String, ipa: String, chars: String? = null): CognateGroup?

    /**
     * 全库同源词成员（lang 非空），供聚类分析按方言点聚合。
     *
     * 一条记录对应 (lang, semanticTag, groupId)：同义项多音类组会重复出现同一 lang+tag。
     * 库缺失时返回空列表；本方法不做缓存（每次读全表 2.7k 行，代价可忽略）。
     */
    suspend fun memberships(): List<CognateMembership>
}

internal class LocalCognateBook(
    private val files: DatabaseFiles,
    private val gate: DbGate,
    private val variants: AssetVariantIndex,
) : CognateBook {

    companion object {
        /**
         * 「搜同源」结果的**固定显示顺序**：按组名（<義項>_<音類>，即卡片上那行英文）
         * 不分大小写的字母序。同字母序时再比原字符串，保证顺序完全确定。
         */
        private val COGNATE_DISPLAY_ORDER =
            compareBy<CognateGroup> { it.groupId.lowercase() }.thenBy { it.groupId }
    }

    override suspend fun search(query: String): List<CognateGroup> = gate.run {
        val db = files.cognates() ?: return@run emptyList()
        val groupIds = mutableSetOf<String>()
        for (v in variants.expand(query)) {
            db.rawQuery(
                "SELECT DISTINCT cognate_group FROM cognates " +
                    "WHERE cognate_group LIKE ? OR ipa LIKE ? OR semantic_label LIKE ?",
                arrayOf("%$v%", "%$v%", "%$v%")
            ).use { cursor ->
                while (cursor.moveToNext()) groupIds.add(cursor.getString(0))
            }
        }
        groupIds.mapNotNull { readGroup(db, it) }.sortedWith(COGNATE_DISPLAY_ORDER)
    }

    override suspend fun groupFor(lang: String, ipa: String, chars: String?): CognateGroup? = gate.run {
        val db = files.cognates() ?: return@run null
        val groupId = findGroupId(db, lang, ipa, chars) ?: return@run null
        readGroup(db, groupId)
    }

    /** 先按字组精确匹配（同一读音可能对应多个字组），查不到再回退到只按 (lang, ipa) 取首行。 */
    private fun findGroupId(
        db: SQLiteDatabase,
        lang: String,
        ipa: String,
        chars: String?,
    ): String? {
        if (!chars.isNullOrBlank()) {
            db.rawQuery(
                "SELECT cognate_group FROM cognates WHERE lang = ? AND ipa = ? AND chars = ? " +
                    "ORDER BY id LIMIT 1",
                arrayOf(lang, ipa, chars)
            ).use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        }
        return db.rawQuery(
            "SELECT cognate_group FROM cognates WHERE lang = ? AND ipa = ? ORDER BY id LIMIT 1",
            arrayOf(lang, ipa)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    override suspend fun memberships(): List<CognateMembership> = gate.run {
        val db = files.cognates() ?: return@run emptyList()
        val result = mutableListOf<CognateMembership>()
        db.rawQuery("SELECT lang, cognate_group, semantic_tag FROM cognates", null).use { cursor ->
            while (cursor.moveToNext()) {
                val lang = cursor.getString(0) ?: continue
                if (lang.isBlank()) continue
                result.add(
                    CognateMembership(
                        lang = lang,
                        groupId = cursor.getString(1) ?: "",
                        semanticTag = cursor.getString(2) ?: "",
                    )
                )
            }
        }
        result
    }

    /** 读出一整组的成员；组内按音典排序。组不存在（空）时返回 null。 */
    private fun readGroup(db: SQLiteDatabase, groupId: String): CognateGroup? {
        var label = ""
        val members = mutableListOf<CognateEntry>()
        db.rawQuery(
            "SELECT lang, ipa, note, sort_key, semantic_label FROM cognates " +
                "WHERE cognate_group = ? ORDER BY sort_key",
            arrayOf(groupId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (label.isEmpty()) label = cursor.getString(4) ?: ""
                members.add(cursor.toCognateEntry())
            }
        }
        return if (members.isEmpty()) null else CognateGroup(groupId, label, members)
    }

    private fun Cursor.toCognateEntry() = CognateEntry(
        lang = getString(0),
        ipa = getString(1),
        note = getString(2) ?: "",
        sortKey = getString(3) ?: ""
    )
}
