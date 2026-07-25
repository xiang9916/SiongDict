#!/usr/bin/env python3
"""
One-shot script to modify cognates.db directly.
Handles: SHOULDER_kan1 deletions, COVER_ɡɔm4 additions, COVER_do5/do6 creation, CONCEAL_ke3 creation.
Updates all three tables: cognate_auto, cognate_manual, cognate_groups.
"""

import os
import sys
import sqlite3

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from ipa_parser import parse_ipa, get_tone_category

DB_PATH = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "databases", "cognates.db"))
SIONGDICT_DB = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "databases", "siongdict.db"))


def parse_entry(ipa):
    p = parse_ipa(ipa)
    if not p:
        return None, None, None, None
    initial, final, tone = p[0]
    tc = get_tone_category(tone)
    return initial, final, tone, tc


def get_sort_key(lang, siong_conn):
    c = siong_conn.cursor()
    c.execute("SELECT 音典排序 FROM info WHERE 簡稱=?", (lang,))
    row = c.fetchone()
    return row[0] if row else ""


def rebuild_groups(conn):
    """Rebuild cognate_groups from cognate_auto."""
    c = conn.cursor()
    c.execute("DELETE FROM cognate_groups")
    c.execute("""
        INSERT INTO cognate_groups (cognate_group, semantic_tag, semantic_label, member_count, dialect_count)
        SELECT cognate_group,
               COALESCE(semantic_tag, SUBSTR(cognate_group, 1, INSTR(cognate_group, '_') - 1)),
               COALESCE(semantic_label, ''),
               COUNT(*),
               COUNT(DISTINCT lang)
        FROM cognate_auto
        GROUP BY cognate_group
    """)
    conn.commit()


def main():
    conn = sqlite3.connect(DB_PATH)
    siong = sqlite3.connect(SIONGDICT_DB)
    c = conn.cursor()

    # ─── TASK 1: Remove from SHOULDER_kan1 ───
    delete_from_shoulder = [
        ("崇陽縣志", "kiɛ1"),
        ("長沙星沙", "kiẽ1"),
        ("雙峰花門", "kẽ1"),
        ("雙峰三塘鋪", "kai1"),
        ("雙峰甘棠", "kai1"),
    ]
    for lang, ipa in delete_from_shoulder:
        c.execute("DELETE FROM cognate_auto WHERE cognate_group='SHOULDER_kan1' AND lang=? AND ipa=?", (lang, ipa))
        c.execute("DELETE FROM cognate_manual WHERE cognate_group='SHOULDER_kan1' AND lang=? AND ipa=?", (lang, ipa))
        print(f"  Deleted SHOULDER_kan1: {lang} {ipa}")

    # ─── TASK 2: Add to COVER_ɡɔm4 ───
    cover_gom4_additions = [
        ("□", "長沙雙江", "kʰuẽ4", "蓋", "01_J9E-001"),
        ("□", "南溪大坪", "kʰaŋ3", "盖:把锅盖子~到", "03_JCD-055"),
        ("□", "晴隆長流", "kʰõ3", "盖:拿个盖盖~起", "03_JCD-057"),
        ("□", "衡山", "kẽĩ3", "蓋:~噠蓋子,~鍋~", "03_JCH-001"),
        ("□", "泰和南溪", "kɤn3", "盖:~紧", "04_JB-009"),
        ("□", "永新", "kẽ3", "盖", "04_JB-015"),
        ("□", "攸縣皇圖嶺", "kiɛi3", "盖,将容器倒置", "04_JB-020"),
        ("□", "衡東草市", "kẽ3", "蓋:~住", "04_JB-023"),
        ("□", "酃縣", "kẽ3", "动词,盖:~盖子", "04_JB-027"),
    ]
    existing_gom4 = set()
    for row in c.execute("SELECT lang, ipa FROM cognate_auto WHERE cognate_group='COVER_ɡɔm4'"):
        existing_gom4.add((row[0], row[1]))

    for chars, lang, ipa, note, sort_key in cover_gom4_additions:
        if (lang, ipa) in existing_gom4:
            print(f"  SKIP COVER_ɡɔm4 (duplicate): {lang} {ipa}")
            continue
        init, final, tone, tc = parse_entry(ipa)
        if not init and not final:
            continue
        # Add to cognate_auto
        c.execute("""
            INSERT INTO cognate_auto (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note, sort_key, initial, final, tone_cat)
            VALUES ('COVER_ɡɔm4', 'COVER', '蓋上', ?, ?, ?, ?, ?, ?, ?, ?)
        """, (chars, lang, ipa, note, sort_key, init, final, tc))
        # Add to cognate_manual
        c.execute("""
            INSERT INTO cognate_manual (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note, modified_at)
            VALUES ('COVER_ɡɔm4', 'COVER', '蓋上', ?, ?, ?, ?, datetime('now'))
        """, (chars, lang, ipa, note))
        print(f"  Added COVER_ɡɔm4: {lang} {ipa}")

    # ─── TASK 3: Create COVER_do5/do6 ───
    COVER_DO_GROUP = "COVER_do5"
    cover_do_entries = [
        ("□", "臨湘詹橋", "do6", "盖", "00_J7B-005"),
        ("□", "雙峰花門", "dʊ5", "压,盖", "01_J9I-006"),
        ("□", "雙峰花門", "tʰʊ5", "盖", "01_J9I-006"),
        ("□", "雙峰甘棠", "dʊ6", "压,盖", "01_J9I-008"),
        ("□", "雙峰甘棠", "tʰʊ5", "盖", "01_J9I-008"),
        ("□", "邵東斫曹", "tʰʊ6", "压,盖", "03_JCD-015"),
    ]
    for chars, lang, ipa, note, sort_key in cover_do_entries:
        init, final, tone, tc = parse_entry(ipa)
        if not init and not final:
            continue
        # Check duplicate
        c.execute("SELECT id FROM cognate_auto WHERE cognate_group=? AND lang=? AND ipa=?", (COVER_DO_GROUP, lang, ipa))
        if c.fetchone():
            print(f"  SKIP {COVER_DO_GROUP} (duplicate): {lang} {ipa}")
            continue
        c.execute("""
            INSERT INTO cognate_auto (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note, sort_key, initial, final, tone_cat)
            VALUES (?, 'COVER', '蓋上', ?, ?, ?, ?, ?, ?, ?, ?)
        """, (COVER_DO_GROUP, chars, lang, ipa, note, sort_key, init, final, tc))
        c.execute("""
            INSERT INTO cognate_manual (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note, modified_at)
            VALUES (?, 'COVER', '蓋上', ?, ?, ?, ?, datetime('now'))
        """, (COVER_DO_GROUP, chars, lang, ipa, note))
        print(f"  Added {COVER_DO_GROUP}: {lang} {ipa}")

    # ─── TASK 4: Create CONCEAL_ke3 ───
    CONCEAL_GROUP = "CONCEAL_ke3"
    conceal_entries = [
        # User-provided entries, verified against siongdict.db
        ("弆", "益陽泥江口", "kɤ3", "(存疑)蓋,動詞", "01_J9A-002"),
        ("弆", "長沙", "kɘ3", "~起:藏着", "01_J9B-006"),
        ("弆", "新長沙", "kɘ3", "~起:藏着", "01_J9B-008"),
        ("弆", "長沙黎圫", "kə3", "~起:藏着", "01_J9B-009"),
        ("弆", "長沙黃花", "ke3", "把东西藏起来:~哒放柜子里", "01_J9B-011"),
        ("弆", "瀏陽永安", "kə3", "把东西藏起来:~哒放柜子里", "01_J9B-012"),
        ("弆", "瀏陽鎮頭", "cie3", "把東西藏起來:~噠放櫃子裏", "01_J9C-007"),
        ("弆", "瀏陽田坪", "cie3", "把東西藏起來:~噠放櫃子裏", "01_J9C-008"),
        ("弆", "瀏陽官橋", "tɕie3", "把東西藏起來:~噠放櫃子裏", "01_J9C-009"),
        ("弆", "長沙雙江", "ke3", "", "01_J9E-001"),
        ("弆", "長沙金井", "kə3", "", "01_J9E-002"),
        ("弆", "汨羅", "ke6", "~起:藏起来", "01_J9F-001"),
        ("弆", "汨羅古培", "ke3", "~起:藏起来", "01_J9F-003"),
        ("弆", "湘陰", "kə3", "藏:把東西~起來", "01_J9F-004"),
        ("弆", "湘陰界頭鋪", "ke3", "藏", "01_J9F-005"),
        ("弆", "湘陰東塘", "ke3", "藏", "01_J9F-006"),
        ("弆", "平江", "ke3", "言藏物也", "05_J5C-001"),
        ("弆", "汨羅長樂", "tɕi3", "藏(物):把錢~噠", "05_J5C-006"),
        ("弆", "瀏陽", "cie3", "藏:~東西", "05_J8C-001"),
        ("弆", "瀏陽大瑶", "kie3", "藏:~東西", "05_J8C-004"),
        ("弆", "巴陵", "ka3", "~底", "06_F3-001"),
        ("弆", "道縣", "kɤ3", "把东西放好、藏起", "07_E6-001"),
        ("弆", "東源官話", "kɤ3", "把东西放好、藏起", "07_E6-002"),
        ("弆", "上尹官話", "kɤ3", "把东西放好、藏起", "07_E6-003"),
    ]
    for chars, lang, ipa, note, sort_key in conceal_entries:
        init, final, tone, tc = parse_entry(ipa)
        if not init and not final:
            continue
        c.execute("SELECT id FROM cognate_auto WHERE cognate_group=? AND lang=? AND ipa=?", (CONCEAL_GROUP, lang, ipa))
        if c.fetchone():
            print(f"  SKIP {CONCEAL_GROUP} (duplicate): {lang} {ipa}")
            continue
        c.execute("""
            INSERT INTO cognate_auto (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note, sort_key, initial, final, tone_cat)
            VALUES (?, 'CONCEAL', '收藏', ?, ?, ?, ?, ?, ?, ?, ?)
        """, (CONCEAL_GROUP, chars, lang, ipa, note, sort_key, init, final, tc))
        c.execute("""
            INSERT INTO cognate_manual (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note, modified_at)
            VALUES (?, 'CONCEAL', '收藏', ?, ?, ?, ?, datetime('now'))
        """, (CONCEAL_GROUP, chars, lang, ipa, note))
        print(f"  Added {CONCEAL_GROUP}: {lang} {ipa}")

    # ─── Rebuild cognate_groups ───
    rebuild_groups(conn)

    # ─── Summary ───
    print("\n=== Summary ===")
    for group in ["SHOULDER_kan1", "COVER_ɡɔm4", COVER_DO_GROUP, CONCEAL_GROUP]:
        c.execute("SELECT COUNT(*), COUNT(DISTINCT lang) FROM cognate_auto WHERE cognate_group=?", (group,))
        cnt, dc = c.fetchone()
        print(f"  {group}: {cnt} members, {dc} dialects")

    conn.commit()
    conn.close()
    siong.close()


if __name__ == "__main__":
    main()
