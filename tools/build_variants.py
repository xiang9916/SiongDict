#!/usr/bin/env python3
"""
Build character variant mapping for the App (assets/variants.json).

Sources (all from the sibling MCPDict clone):
  1. tools/tables/data/正字.tsv        —— 异体字表（含简繁与字形异体，`#` 分隔不同类别）
  2. app/src/main/assets/opencc/STCharacters.txt  —— 简体 → 繁体
  3. app/src/main/assets/opencc/TSCharacters.txt  —— 繁体 → 简体
  4. app/src/main/assets/opencc/HKVariants.txt    —— 港式异体（如 溼/濕）
  5. app/src/main/assets/opencc/TWVariants.txt    —— 台式异体

把一张表里出现的字形连成一张无向图，再取连通分量：同一分量里的任一写法，
搜尋时都会展开成整个分量，从而实现「繁简异体混搜」。
所以映射天然是双向且可传递的。

注意：一简对多繁（如 发 → 發/髮）会让 發 与 髮 落进同一个分量，
这对「搜尋」是想要的行为（输 發 也能找到 髮 的条目），但会放大组合数，
故 expandQueryVariants() 对组合数设了上限。
"""

import json
import os
from collections import defaultdict

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MCPDICT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", "MCPDict-master"))
OPENCC = os.path.normpath(
    os.path.join(MCPDICT, "app", "src", "main", "assets", "opencc")
)
OUTPUT = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "variants.json")
)

SOURCES = [
    (os.path.join(MCPDICT, "tools", "tables", "data", "正字.tsv"), True),
    (os.path.join(OPENCC, "STCharacters.txt"), False),
    (os.path.join(OPENCC, "TSCharacters.txt"), False),
    (os.path.join(OPENCC, "HKVariants.txt"), False),
    (os.path.join(OPENCC, "TWVariants.txt"), False),
]

# 单个分组里字数超过这个值就丢掉：那是「一简多繁」串成的巨团，
# 展开后组合数爆炸，对搜尋反而有害（宁可查不到，不可查出一屏不相干的东西）。
MAX_GROUP = 12


def read_pairs(path, hash_is_separator):
    """逐行读表，产出 (src, dst) 字对。

    正字.tsv 用 `#` 分隔不同类别的异体（不是注释），其余表用空格分隔多个目标字。
    """
    pairs = []
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            src = parts[0].strip()
            dst_field = parts[1].replace("#", " ") if hash_is_separator else parts[1]
            if not src or len(src) != 1:
                continue
            for dst in dst_field.split():
                if len(dst) == 1 and dst != src:
                    pairs.append((src, dst))
    return pairs


def build_components(pairs):
    """无向图连通分量。"""
    adj = defaultdict(set)
    for a, b in pairs:
        adj[a].add(b)
        adj[b].add(a)

    seen = set()
    groups = []
    for char in adj:
        if char in seen:
            continue
        stack, group = [char], set()
        while stack:
            c = stack.pop()
            if c in seen:
                continue
            seen.add(c)
            group.add(c)
            stack.extend(adj[c] - seen)
        if len(group) > 1:
            groups.append(group)
    return groups


def build_variants():
    all_pairs = []
    for path, hash_is_separator in SOURCES:
        if not os.path.exists(path):
            raise SystemExit(
                "缺少字表：%s\n（MCPDict 需与 SiongDict 平级，且已 sparse-checkout "
                "tools/tables/data 与 app/src/main/assets/opencc）" % path)
        pairs = read_pairs(path, hash_is_separator)
        print("  %-28s %6d 对" % (os.path.basename(path), len(pairs)))
        all_pairs.extend(pairs)

    groups = build_components(all_pairs)
    dropped = [g for g in groups if len(g) > MAX_GROUP]
    groups = [g for g in groups if len(g) <= MAX_GROUP]

    result = {}
    for group in groups:
        members = sorted(group)
        for char in members:
            result[char] = members

    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    print("\n字符 %d 个，分组 %d 个（最大 %d 字，平均 %.2f）"
          % (len(result), len(groups),
             max((len(g) for g in groups), default=0),
             sum(len(g) for g in groups) / max(len(groups), 1)))
    print("因超过 %d 字而丢弃的巨团：%d 个" % (MAX_GROUP, len(dropped)))
    for g in dropped[:5]:
        print("   %d 字：%s" % (len(g), "".join(sorted(g))))
    print("输出：%s（%.1f KB）" % (OUTPUT, os.path.getsize(OUTPUT) / 1024))

    print("\n抽样：")
    for ch in ["湿", "溼", "濕", "着", "著", "里", "裡", "裏", "说", "說", "发", "發", "髮", "東", "东"]:
        print("  %s -> %s" % (ch, "".join(result.get(ch, [])) or "（无）"))


if __name__ == "__main__":
    build_variants()
