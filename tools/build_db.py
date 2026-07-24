#!/usr/bin/env python3
"""
湘典 (SiongDict) database builder.

Reads processed TSV files from MCPDict's output directory,
filters to Xiang-related dialects, and builds a SQLite FTS5 database.

Usage:
    python3 build_db.py [--mcpdict-dir PATH] [--output PATH]
"""

import json
import os
import re
import sqlite3
import sys
from collections import defaultdict

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MCPDICT_DIR = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "..", "MCPDict-new", "tools", "tables")
)
DEFAULT_OUTPUT = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "databases", "siongdict.db")
)

# ─── Filtering ───

# Condition 1: match any of these 音典分區 keywords, or 湘語 in 地圖集二分區
FQ_KEYWORDS = [
    "湘語",
    "湘贛－岳州",
    "湘贛－北湘",
    "湘贛－雪峰",
    "湘贛－羅霄",
    "湘贛－南湘",
    "湘南",
    "道州",
    "鄉話",
]


def matches_fq(d):
    dt2 = d.get("地圖集二分區", "")
    yd = d.get("音典分區", "")
    if "湘語" in dt2:
        return True
    for kw in FQ_KEYWORDS[1:]:
        if kw in yd:
            return True
    return False


def matches_geo(d):
    prov = d.get("省", "").strip("*")
    return prov == "湖南"


# ─── Sorting ───

# Major division ordering: lower = earlier
DIVISION_ORDER = [
    "湘贛－岳州",
    "湘贛－北湘",
    "湘贛－雪峰",
    "湘贛－南湘",
    "湘贛－羅霄",
    "中上江",
    "藍青",
    "鄉話",
    "道州",
    "湘南",
]


def get_division_rank(yd_division):
    """Return a sort rank for the major division. Lower = earlier.
    Dialects not matching any known division go last.
    """
    for i, prefix in enumerate(DIVISION_ORDER):
        if prefix in yd_division:
            return i
    return len(DIVISION_ORDER)


def make_sort_key(meta):
    """Build a composite sort key that orders by (division rank, 音典排序).

    Format: f"{rank:02d}_{yd_sort}" so string sorting gives the desired order.
    """
    yd_div = meta.get("音典分區", "")
    yd_sort = meta.get("音典排序", "")
    rank = get_division_rank(yd_div)
    return f"{rank:02d}_{yd_sort}"


def load_metadata(mcpdict_dir):
    json_path = os.path.join(mcpdict_dir, "output", "_詳情.json")
    if not os.path.exists(json_path):
        sys.exit(f"Metadata not found: {json_path}")
    with open(json_path, encoding="U8") as f:
        return json.load(f)


def filter_dialects(metadata):
    matched = {}
    for key, val in metadata.items():
        if matches_fq(val) or matches_geo(val):
            matched[key] = val
    return matched


def sort_dialects(dialects):
    """Sort dialects by (major division rank, 音典排序).

    Within each major division, dialects are sorted by their 音典排序 string.
    """
    def sort_key(item):
        簡稱, meta = item
        yd_div = meta.get("音典分區", "")
        rank = get_division_rank(yd_div)
        yd_sort = meta.get("音典排序", "")
        return (rank, yd_sort, 簡稱)

    return dict(sorted(dialects.items(), key=sort_key))


def read_tsv(mcpdict_dir, 簡稱):
    tsv_path = os.path.join(mcpdict_dir, "output", f"{簡稱}.tsv")
    if not os.path.exists(tsv_path):
        return []
    entries = []
    with open(tsv_path, encoding="U8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            字 = parts[0]
            音 = parts[1]
            註 = parts[2] if len(parts) > 2 else ""
            if not 字 or not 音:
                continue
            entries.append((字, 音, 註))
    return entries


TOKENS = "□－〈〉［］（）"


def build_database(mcpdict_dir, output_path, dialects):
    if os.path.exists(output_path):
        os.remove(output_path)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    conn = sqlite3.connect(output_path)
    c = conn.cursor()

    c.execute(
        'CREATE VIRTUAL TABLE langs USING fts5 '
        '(字組, 語言, 讀音, 註釋, 排序, '
        'columnsize=0, tokenize="unicode61 remove_diacritics 0 tokenchars \'%s\'")'
        % TOKENS
    )

    info_fields = [
        "語言", "簡稱", "地點", "經緯度", "聲調",
        "地圖集二排序", "地圖集二分區",
        "音典排序", "音典分區", "陳邡排序", "陳邡分區",
        "地圖集二顏色", "音典顏色",
        "省", "市", "縣", "鎮", "村",
        "行政區級別", "方言島", "歷史音",
        "版本", "字表來源", "參考文獻", "補充閲讀",
        "作者", "錄入人", "維護人", "推薦人",
        "音系說明", "說明",
        "字數", "□數", "音節數",
    ]
    c.execute(
        'CREATE VIRTUAL TABLE info USING fts5 (%s, columnsize=0)'
        % ",".join(info_fields)
    )

    items = []
    info_rows = []
    total_chars = 0
    skipped = 0

    for 簡稱, meta in dialects.items():
        entries = read_tsv(mcpdict_dir, 簡稱)
        if not entries:
            skipped += 1
            continue

        groups = defaultdict(list)
        for 字, 音, 註 in entries:
            key = (音, 註)
            groups[key].append(字)

        for (音, 註), chars in groups.items():
            字組 = " ".join(chars)
            items.append((字組, 簡稱, 音, 註, make_sort_key(meta)))

        total_chars += len(entries)

        info_row = []
        for field in info_fields:
            val = meta.get(field, "")
            if val is None:
                val = ""
            if isinstance(val, bool):
                val = "1" if val else ""
            info_row.append(str(val))
        char_set = set(e[0] for e in entries)
        idx = info_fields.index("字數")
        info_row[idx] = str(len(char_set))
        idx = info_fields.index("□數")
        info_row[idx] = str(len(char_set & {"□"}))
        idx = info_fields.index("音節數")
        syllables = set()
        for _, 音, _ in entries:
            base = re.sub(r"\d.*$", "", 音)
            syllables.add(base)
        info_row[idx] = str(len(syllables))
        info_rows.append(tuple(info_row))

    if items:
        c.executemany("INSERT INTO langs VALUES (?,?,?,?,?)", items)
    if info_rows:
        placeholders = ",".join("?" * len(info_fields))
        c.executemany(f"INSERT INTO info VALUES ({placeholders})", info_rows)

    conn.commit()
    c.execute("PRAGMA user_version = 3")
    conn.commit()
    conn.close()

    print(f"Database built: {output_path}")
    print(f"  Dialects: {len(dialects)} matched, {len(dialects) - skipped} loaded, {skipped} skipped")
    print(f"  Characters: {total_chars} entries")
    print(f"  Langs rows: {len(items)}")
    print(f"  Info rows: {len(info_rows)}")
    print(f"  DB size: {os.path.getsize(output_path) / 1024 / 1024:.1f} MB")


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Build SiongDict SQLite database")
    parser.add_argument("--mcpdict-dir", default=DEFAULT_MCPDICT_DIR)
    parser.add_argument("--output", default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    print(f"MCPDict dir: {args.mcpdict_dir}")
    print(f"Output: {args.output}")

    metadata = load_metadata(args.mcpdict_dir)
    print(f"Total dialects in metadata: {len(metadata)}")

    dialects = filter_dialects(metadata)
    print(f"Matched Xiang dialects: {len(dialects)}")

    # Show division breakdown
    div_counts = defaultdict(int)
    for meta in dialects.values():
        yd = meta.get("音典分區", "")
        rank = get_division_rank(yd)
        div_name = DIVISION_ORDER[rank] if rank < len(DIVISION_ORDER) else "其他"
        div_counts[div_name] += 1
    for div in DIVISION_ORDER + ["其他"]:
        if div in div_counts:
            print(f"  {div}: {div_counts[div]}")

    dialects = sort_dialects(dialects)
    build_database(args.mcpdict_dir, args.output, dialects)


if __name__ == "__main__":
    main()
