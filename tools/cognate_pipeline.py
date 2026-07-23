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

SEMANTIC_LABELS = {
    "HIDE": "躲藏/捉迷藏",
}

# Special characters excluded from bridge tables
SPECIAL_CHARS = {"□", "〇"}

# Minimum bridge character count to confirm a correspondence
MIN_BRIDGE = 3

# ─── Step 1: Semantic grouping ───

SEMANTIC_RULES = [
    (r"躲.*捉迷藏|捉迷藏.*躲|躲~~|躲~[:：]", "HIDE"),
    (r"捉迷藏", "HIDE"),
    (r"^躲~?$|^躲[,，]|藏.*躲|躲.*藏|隱藏|隐藏|藏匿", "HIDE"),
]


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
        if not tag:
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
            if initial:
                initial_chars[lang][initial].add(ch)
            if final:
                final_chars[lang][final].add(ch)
            if tone_cat:
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
    if init1 and init2:
        s1 = initial_chars.get(d1, {}).get(init1, set())
        s2 = initial_chars.get(d2, {}).get(init2, set())
        if len(s1 & s2) < min_bridge:
            return False
    elif init1 != init2:
        # One has initial, the other doesn't — need bridge
        return False

    # Final bridge (full final: vowel + coda as a whole)
    if final1 and final2:
        s1 = final_chars.get(d1, {}).get(final1, set())
        s2 = final_chars.get(d2, {}).get(final2, set())
        if len(s1 & s2) < min_bridge:
            return False
    elif final1 != final2:
        return False

    # Tone bridge
    if tone1 and tone2:
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

def build_cognates_db(db_path, output_path):
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
            base_id = f"{tag}_{rep['final']}"
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

    c.execute("""
        CREATE TABLE cognate_auto (
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
            tone_cat TEXT
        )
    """)

    c.execute("""
        CREATE TABLE IF NOT EXISTS cognate_manual (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            chars TEXT,
            lang TEXT,
            ipa TEXT,
            cognate_group TEXT NOT NULL,
            note TEXT,
            modified_at TEXT DEFAULT (datetime('now'))
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

    inserted = 0
    group_meta = {}
    for gid, tag, cluster in all_clusters:
        label = SEMANTIC_LABELS.get(tag, tag)
        for e in cluster:
            c.execute(
                "INSERT INTO cognate_auto (cognate_group, semantic_tag, semantic_label, chars, lang, ipa, note, sort_key, initial, final, tone_cat) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (gid, tag, label, e["chars"], e["lang"], e["ipa"], e["note"],
                 e["sortKey"], e["initial"], e["final"], e["tone_cat"])
            )
            inserted += 1
        if gid not in group_meta:
            group_meta[gid] = (tag, label, len(cluster), len(set(e["lang"] for e in cluster)))

    for gid, (tag, label, mc, dc) in group_meta.items():
        c.execute("INSERT INTO cognate_groups VALUES (?,?,?,?,?)", (gid, tag, label, mc, dc))

    c.execute("PRAGMA user_version = 2")
    conn.commit()
    conn.close()

    print(f"\nCognates database built: {output_path}")
    print(f"  Auto entries: {inserted}")
    print(f"  Cognate groups: {len(group_meta)}")
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
