#!/usr/bin/env python3
"""
Cognate identification pipeline for SiongDict (v2).

Uses bridge character verification instead of statistical correspondence:
- Case 1: If two entries share a known character → cognate
- Cases 2 & 3: Verify initial/final/tone correspondences by finding
  bridge characters (known chars) that demonstrate regular sound
  correspondences between the two dialects.

A bridge character B for initials means:
  B has initial I1 in dialect D1, AND B has initial I2 in dialect D2.
This proves I1 and I2 are regularly corresponding initials.
Similarly for finals (vowel + coda as a whole) and tones.

□ (unknown character) is excluded from bridge tables.
A minimum bridge count (default 3) filters out accidental exceptions.

Usage:
    python3 cognate_pipeline.py [--db PATH] [--output PATH]
"""

import os
import re
import sqlite3
import sys
from collections import defaultdict

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)

from ipa_parser import parse_ipa, get_tone_category

DEFAULT_DB = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "databases", "siongdict.db")
)
DEFAULT_OUTPUT = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "databases", "cognates.db")
)

LABEL_MAP = {
    "HIDE": "躲藏/捉迷藏",
    "ATTACH": "附着",
    "WRAP": "包覆",
    "COVER": "蓋上",
    "DIVINEANSWER": "聖筊",
    "STACKING": "疊加",
    "CONCEAL": "收藏",
}

# Special characters excluded from bridge tables
SPECIAL_CHARS = {"□", "〇"}

# Minimum bridge character count to confirm a correspondence
MIN_BRIDGE = 3

# Trailing tone modifiers (-/=) stripped from IPA readings
TRAILING_TONE_MARKS_RE = re.compile(r"[-=]+$")

# ─── Step 1: Semantic grouping ───

SEMANTIC_RULES = [
    (r"躲.*捉迷藏|捉迷藏.*躲|躲~~|躲~[:：]", "HIDE"),
    (r"捉迷藏", "HIDE"),
    (r"^躲~?$|^躲[,，]|藏.*躲|躲.*藏|隱藏|隐藏|藏匿", "HIDE"),
]

# 停用自動分組的義類（2026-10-05）。
#
# HIDE 的人工分組（Hide_dzɔŋ2、Hide_ma1、Hide_paŋ5、Hide_piaŋ5、Hide_pɤu6、
# Hide_tsia5、Hide_tsʰia1、Hide_xua5）是人工整理確認過的結果，已固化為
# source='manual'。2026-10-03 換成「按韻母分組」的新演算法後，重跑管線又額外生出
# 23 個 Hide_* 組（Hide_a、Hide_o、Hide_ua …，當時寫作 HIDE_*），與人工結果重複，
# 已於 2026-10-05 整批刪除。
#
# 為什麼要在這裡關、而不是只改資料庫：build_cognates_db() 每次都會 os.remove()
# 整個 cognates.db 重建，只有 source='manual' 會被 Step 0 救回。單獨刪掉資料庫裡
# 的 auto 行，下次重跑就會全部回來——這正是「刪了又恢復」的成因。
#
# 這裡的鍵是 SEMANTIC_RULES 吐出的義類 tag（全大寫），不是組名。
# 置回 set() 即恢復自動分組。
SUPPRESSED_SEMANTIC_TAGS = {"HIDE"}

# ─── 組名與義項的寫法（2026-10-08 統一）───
#
#  組名 = `<義項>_<音類>`，如 Press_gin6、Hide_dzɔŋ2、Segment_CutOpen_ʂak7
#  義項 = 組名去掉末尾那段音類，如 Press、Hide、Segment_CutOpen
#
# 一個義項可以掛多個音類組（Hide 掛 8 組、Press 掛 4 組），義項寫進
# cognates.semantic_tag。英文一律大駝峰；純 A–Z 字母段才轉換，含音標或數字的段
# （DJNbo2 這類）原樣保留。多詞複合義項按 TAG_SPLIT 切詞。
TAG_SPLIT = {
    "CUTOPEN": "CutOpen",                     # 瓣/破開
    "DIVINEANSWER": "DivineAnswer",           # 聖筊
    "DRINKINGHOT": "DrinkingHot",             # 熱水
    "GETWETINTHERAIN": "GetWetInTheRain",     # 淋雨
    "LOOKFOR": "LookFor",                     # 尋找
    "SHORTPERIODOFRAIN": "ShortPeriodOfRain",  # 一陣雨
}


def pascal_tag(seg):
    """純 A–Z 字母段 → 大駝峰；其他段（音標、數字、DJNbo2 之類）原樣返回。"""
    if not seg or not (seg.isascii() and seg.isalpha() and seg.isupper()):
        return seg
    return TAG_SPLIT.get(seg, seg[0] + seg[1:].lower())


def group_prefix(group):
    """組名去掉末尾的音類段 = 義項（組名只有一段時原樣返回）。"""
    parts = group.split("_")
    return "_".join(parts[:-1]) if len(parts) > 1 else group


def extract_semantic_tag(note):
    if not note:
        return None
    for pattern, tag in SEMANTIC_RULES:
        if re.search(pattern, note):
            return tag
    return None


def build_semantic_groups(db_path):
    """Build semantic groups from the database."""
    conn = sqlite3.connect(db_path)
    c = conn.cursor()

    groups = defaultdict(list)
    c.execute("SELECT 字組, 語言, 讀音, 註釋, 排序 FROM langs")
    for chars, lang, ipa, note, sort_key in c.fetchall():
        tag = extract_semantic_tag(note)
        if not tag or tag in SUPPRESSED_SEMANTIC_TAGS:
            continue
        parsed = parse_ipa(ipa)
        if not parsed:
            continue
        initial, final, tone = parsed[0]
        if not final:
            continue
        tone_cat = get_tone_category(tone)
        groups[tag].append({
            "chars": chars,
            "lang": lang,
            "ipa": ipa,
            "note": note or "",
            "sortKey": sort_key or "",
            "initial": initial,
            "final": final,
            "tone_cat": tone_cat,
        })
    conn.close()
    return groups


# ─── Step 2: Bridge lookup tables ───

def build_lookup_tables(db_path):
    """Build character lookup tables for bridge verification.

    For each dialect, creates:
        initial_chars[dialect][initial] = set of known characters
        final_chars[dialect][final] = set of known characters
        tone_chars[dialect][tone_cat] = set of known characters

    □ and 〇 are excluded — bridge characters must be known chars.
    """
    conn = sqlite3.connect(db_path)
    c = conn.cursor()

    initial_chars = defaultdict(lambda: defaultdict(set))
    final_chars = defaultdict(lambda: defaultdict(set))
    tone_chars = defaultdict(lambda: defaultdict(set))

    c.execute("SELECT 字組, 語言, 讀音 FROM langs")
    for chars, lang, ipa in c.fetchall():
        char_list = chars.split() if chars else []
        if not char_list:
            continue
        parsed = parse_ipa(ipa)
        if not parsed:
            continue
        initial, final, tone = parsed[0]
        tone_cat = get_tone_category(tone)
        for ch in char_list:
            if ch in SPECIAL_CHARS:
                continue
            initial_chars[lang][initial].add(ch)
            final_chars[lang][final].add(ch)
            tone_chars[lang][tone_cat].add(ch)

    conn.close()
    return dict(initial_chars), dict(final_chars), dict(tone_chars)


# ─── Step 3: Bridge verification ───

def share_known_char(chars1, chars2):
    """Check if two character strings share any known (non-□) character."""
    set1 = set(chars1.split()) - SPECIAL_CHARS if chars1 else set()
    set2 = set(chars2.split()) - SPECIAL_CHARS if chars2 else set()
    return bool(set1 & set2)


def check_bridge(d1, init1, final1, tone1,
                 d2, init2, final2, tone2,
                 initial_chars, final_chars, tone_chars,
                 min_bridge=MIN_BRIDGE):
    """Check if bridge characters exist for initial, final, and tone.

    For each dimension, finds characters that have the corresponding
    value in both dialects. Requires at least min_bridge characters
    to confirm a regular correspondence (filters out accidental exceptions).

    Returns True only if all three bridges meet the minimum count.
    """
    # Initial bridge
    if init1 or init2:
        s1 = initial_chars.get(d1, {}).get(init1, set())
        s2 = initial_chars.get(d2, {}).get(init2, set())
        if len(s1 & s2) < min_bridge:
            return False
    elif init1 != init2:
        # One has initial, the other doesn't — need bridge
        return False

    # Final bridge (full final: vowel + coda as a whole)
    if final1 or final2:
        s1 = final_chars.get(d1, {}).get(final1, set())
        s2 = final_chars.get(d2, {}).get(final2, set())
        if len(s1 & s2) < min_bridge:
            return False
    elif final1 != final2:
        return False

    # Tone bridge
    if tone1 or tone2:
        s1 = tone_chars.get(d1, {}).get(tone1, set())
        s2 = tone_chars.get(d2, {}).get(tone2, set())
        if len(s1 & s2) < min_bridge:
            return False
    elif tone1 != tone2:
        return False

    return True


# ─── Step 4: Cognate clustering ───

def cluster_cognates(entries, initial_chars, final_chars, tone_chars):
    """Cluster entries using pairwise bridge verification + greedy clique.

    Builds a graph where edges connect cognate pairs (verified by bridge
    check), then finds greedy maximal cliques so that every pair within
    a group passes the bridge check. This prevents transitive over-merging
    (A~B and B~C does not imply A~C).
    """
    n = len(entries)
    if n <= 1:
        return [entries] if n == 1 else []

    # Build adjacency matrix: adj[i][j] = True if i and j are cognates
    adj = [[False] * n for _ in range(n)]
    for i in range(n):
        for j in range(i + 1, n):
            ei, ej = entries[i], entries[j]
            cognate = False
            if ei["lang"] == ej["lang"]:
                cognate = True
            elif share_known_char(ei["chars"], ej["chars"]):
                cognate = True
            else:
                cognate = check_bridge(
                    ei["lang"], ei["initial"], ei["final"], ei["tone_cat"],
                    ej["lang"], ej["initial"], ej["final"], ej["tone_cat"],
                    initial_chars, final_chars, tone_chars
                )
            adj[i][j] = cognate
            adj[j][i] = cognate

    # Find greedy maximal cliques
    remaining = set(range(n))
    clusters = []

    while remaining:
        # Pick seed: node with most connections within remaining set
        seed = max(remaining, key=lambda x: sum(1 for y in remaining if adj[x][y]))
        clique = {seed}
        candidates = {y for y in remaining if adj[seed][y]}

        while candidates:
            # Pick candidate connected to all current clique members
            best = max(candidates, key=lambda x: sum(1 for y in clique if adj[x][y]))
            if all(adj[best][y] for y in clique):
                clique.add(best)
            candidates.discard(best)

        clusters.append([entries[i] for i in sorted(clique)])
        remaining -= clique

    return clusters


# ─── Step 5: Build cognates.db ───

def load_manual_entries(output_path):
    """Load source='manual' entries from cognates.db before rebuild.

    Returns a list of dicts. Returns empty list if file or table is missing.
    """
    if not os.path.exists(output_path):
        return []
    try:
        conn = sqlite3.connect(output_path)
        c = conn.cursor()
        c.execute("""SELECT chars, lang, ipa, cognate_group, note, semantic_tag, semantic_label
                     FROM cognates WHERE source = 'manual'""")
        rows = c.fetchall()
        conn.close()
        return [{"chars": r[0] or "", "lang": r[1] or "",
                 "ipa": TRAILING_TONE_MARKS_RE.sub("", r[2] or ""),
                 "cognate_group": r[3], "note": r[4] or "",
                 "semantic_tag": r[5] or "", "semantic_label": r[6] or ""} for r in rows]
    except Exception:
        return []


def lookup_sort_key(lang, ipa, siong_conn):
    """Look up sort_key from siongdict.db for a given (lang, ipa) pair."""
    try:
        sc = siong_conn.cursor()
        sc.execute("SELECT 排序 FROM langs WHERE 語言 = ? AND 讀音 = ? LIMIT 1", (lang, ipa))
        row = sc.fetchone()
        return row[0] if row else ""
    except Exception:
        return ""


def insert_manual_entries(conn, manual_entries, db_path):
    """Insert manual entries into cognates table with source='manual'.

    For each manual entry, parse IPA, look up sort_key, and insert.
    Also delete any conflicting auto entry (same lang+ipa) so manual
    entries always take precedence.
    """
    if not manual_entries:
        print("  No manual entries to insert.")
        return

    print(f"  Inserting {len(manual_entries)} manual entries...")
    c = conn.cursor()
    siong_conn = sqlite3.connect(db_path)

    for me in manual_entries:
        ipa_clean = TRAILING_TONE_MARKS_RE.sub("", me["ipa"])
        ipa_parsed = parse_ipa(ipa_clean)
        if ipa_parsed:
            init, final, tone = ipa_parsed[0]
            tcat = get_tone_category(tone)
        else:
            init, final, tcat = "", "", ""

        tag = me.get("semantic_tag", "")
        label = me.get("semantic_label", "")
        if not tag:
            tag = group_prefix(me["cognate_group"])
        if not label:
            label = LABEL_MAP.get(tag.upper(), tag)

        sort_key = lookup_sort_key(me["lang"], ipa_clean, siong_conn)

        # Delete conflicting auto entry (same lang+ipa) so manual takes precedence
        c.execute("DELETE FROM cognates WHERE source = 'auto' AND lang = ? AND ipa = ?",
                  (me["lang"], ipa_clean))

        c.execute(
            """INSERT INTO cognates
               (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note,
                sort_key, initial, final, tone_cat, source, modified_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,'manual', datetime('now'))""",
            (me["cognate_group"], tag, label, me["chars"], me["lang"], ipa_clean,
             me["note"], sort_key, init, final, tcat)
        )

    siong_conn.close()
    conn.commit()

    # Show summary
    c.execute("SELECT cognate_group, COUNT(*) FROM cognates GROUP BY cognate_group ORDER BY cognate_group")
    groups = c.fetchall()
    print(f"  After manual entries: {len(groups)} cognate groups")
    for gid, count in groups:
        print(f"    {gid}: {count}")


def rebuild_groups(conn):
    """Rebuild cognate_groups from cognates table."""
    c = conn.cursor()
    c.execute("DELETE FROM cognate_groups")
    c.execute("""
        INSERT INTO cognate_groups (cognate_group, semantic_tag, semantic_label, member_count, dialect_count)
        SELECT cognate_group,
               MAX(semantic_tag) as semantic_tag,
               MAX(semantic_label) as semantic_label,
               COUNT(*) as member_count,
               COUNT(DISTINCT lang) as dialect_count
        FROM cognates
        GROUP BY cognate_group
    """)
    conn.commit()


def build_cognates_db(db_path, output_path):
    # Step 0: Backup manual entries before destroying the file
    print("Step 0: Backing up manual entries...")
    manual_entries = load_manual_entries(output_path)
    print(f"  Found {len(manual_entries)} manual entries")

    if os.path.exists(output_path):
        os.remove(output_path)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    print("Step 1: Building semantic groups...")
    groups = build_semantic_groups(db_path)
    print(f"  Semantic groups: {len(groups)}")
    for tag, entries in sorted(groups.items()):
        dialects = set(e["lang"] for e in entries)
        print(f"    {tag}: {len(entries)} entries, {len(dialects)} dialects")

    print("Step 2: Building bridge lookup tables...")
    initial_chars, final_chars, tone_chars = build_lookup_tables(db_path)
    print(f"  Dialects with lookup data: {len(initial_chars)}")

    print("Step 3: Clustering cognates...")
    all_clusters = []
    group_counter = defaultdict(int)
    for tag, entries in sorted(groups.items()):
        clusters = cluster_cognates(entries, initial_chars, final_chars, tone_chars)
        for cluster in clusters:
            if len(cluster) < 2:
                continue
            rep = cluster[0]
            base_id = f"{pascal_tag(tag)}_{rep['final']}"
            group_counter[base_id] += 1
            if group_counter[base_id] > 1:
                gid = f"{base_id}_{group_counter[base_id]}"
            else:
                gid = base_id
            all_clusters.append((gid, tag, cluster))

    print(f"  Cognate groups (>=2 members): {len(all_clusters)}")
    for gid, tag, cluster in all_clusters[:20]:
        dialects = set(e["lang"] for e in cluster)
        print(f"    {gid}: {len(cluster)} entries, {len(dialects)} dialects")
        for e in cluster[:6]:
            print(f"      {e['lang']:20s} {e['ipa']:15s} init={e['initial']:6s} final={e['final']:8s} tone={e['tone_cat']}  {e['note'][:35]}")
        if len(cluster) > 6:
            print(f"      ... and {len(cluster) - 6} more")

    print("Step 4: Building cognates.db...")
    conn = sqlite3.connect(output_path)
    c = conn.cursor()

    # Single merged table: replaces old cognate_auto + cognate_manual
    c.execute("""
        CREATE TABLE cognates (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            cognate_group TEXT NOT NULL,
            semantic_tag TEXT NOT NULL,
            semantic_label TEXT,
            chars TEXT,
            lang TEXT,
            ipa TEXT,
            note TEXT,
            sort_key TEXT,
            initial TEXT,
            final TEXT,
            tone_cat TEXT,
            source TEXT NOT NULL DEFAULT 'auto',
            modified_at TEXT
        )
    """)

    c.execute("""
        CREATE TABLE cognate_groups (
            cognate_group TEXT PRIMARY KEY,
            semantic_tag TEXT,
            semantic_label TEXT,
            member_count INTEGER,
            dialect_count INTEGER
        )
    """)

    # Insert auto-generated entries
    inserted = 0
    group_meta = {}
    for gid, tag, cluster in all_clusters:
        label = LABEL_MAP.get(tag, tag)
        semantic_tag = pascal_tag(tag)
        for e in cluster:
            c.execute(
                """INSERT INTO cognates
                   (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note,
                    sort_key, initial, final, tone_cat, source)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,'auto')""",
                (gid, semantic_tag, label, e["chars"], e["lang"], e["ipa"], e["note"],
                 e["sortKey"], e["initial"], e["final"], e["tone_cat"])
            )
            inserted += 1
        if gid not in group_meta:
            group_meta[gid] = (semantic_tag, label, len(cluster),
                               len(set(e["lang"] for e in cluster)))

    # Step 5: Insert manual entries (delete conflicting auto entries first)
    print("Step 5: Inserting manual entries...")
    insert_manual_entries(conn, manual_entries, db_path)

    # Rebuild cognate_groups from final state
    rebuild_groups(conn)

    conn.commit()
    c.execute("PRAGMA user_version = 3")
    conn.commit()
    conn.close()

    # Recount final stats
    conn2 = sqlite3.connect(output_path)
    c2 = conn2.cursor()
    c2.execute("SELECT COUNT(*) FROM cognates")
    total = c2.fetchone()[0]
    c2.execute("SELECT COUNT(*) FROM cognates WHERE source = 'auto'")
    auto_count = c2.fetchone()[0]
    c2.execute("SELECT COUNT(*) FROM cognates WHERE source = 'manual'")
    manual_count = c2.fetchone()[0]
    c2.execute("SELECT COUNT(*) FROM cognate_groups")
    groups = c2.fetchone()[0]
    conn2.close()

    print(f"\nCognates database built: {output_path}")
    print(f"  Total entries: {total} (auto: {auto_count}, manual: {manual_count})")
    print(f"  Cognate groups: {groups}")
    print(f"  DB size: {os.path.getsize(output_path) / 1024:.1f} KB")


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Build cognates.db for SiongDict")
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--output", default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    print(f"Source DB: {args.db}")
    print(f"Output: {args.output}")
    build_cognates_db(args.db, args.output)


if __name__ == "__main__":
    main()
