#!/usr/bin/env python3
"""
Build the Traditional→Simplified conversion table for the App's 「渲染為簡體中文」 switch.

Sources (both from the sibling MCPDict clone, which vendors OpenCC):
  app/src/main/assets/opencc/TSPhrases.txt    —— 例外词组表（詞級優先）
  app/src/main/assets/opencc/TSCharacters.txt —— 字符表（字級兜底）

TSPhrases is NOT a general dictionary — it is OpenCC's exception list, holding
the cases where character-level conversion would be wrong (「乾坤」 must stay
「乾坤」, not「干坤」).  The bulk of the work is done by TSCharacters.

Output: app/src/main/assets/t2s.json
  {"phrases": {"乾坤": "乾坤", ...}, "chars": {"乾": "干", ...}}

Runtime matching: longest phrase first, then a single character, else keep.
See docs/adr/0002-script-variant-tables.md for why these tables ship with the app
instead of running a conversion algorithm at runtime.
"""

import json
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MCPDICT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", "MCPDict-master"))
OPENCC = os.path.normpath(os.path.join(MCPDICT, "app", "src", "main", "assets", "opencc"))
OUTPUT = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "t2s.json")
)

SOURCES = {
    "phrases": os.path.join(OPENCC, "TSPhrases.txt"),
    "chars": os.path.join(OPENCC, "TSCharacters.txt"),
}

# ── 本地叠加的「著」启发式（2026-10-08 由維護者定）──
#
# OpenCC 的字表不含「著」：它該轉「着」（盯著、沿著、附著）還是保持「著」
# （著作、著名、顯著）要看讀音（zhe/zhuó vs zhù），字級表不敢替你決定。
# 本庫語料裡 84 條含「著」的註釋幾乎全是助詞用法，少數 zhù 類是「著名/著作/顯著」。
# 所以：字表加一條「著 → 着」的默認規則，再用下面這張保護詞表（讀 zhù 的詞）
# 把它們按住——詞表條目會逐字轉成簡體後寫進 phrases，運行時詞級最長匹配優先，
# 所以「顯著」會整體命中並保持「著」，而「盯著」查不到詞條，落到字表變「着」。
ZHU_KEEP = [
    "顯著", "著名", "著作", "著者", "著錄", "著述", "著書", "著稱", "著作權",
    "編著", "論著", "原著", "譯著", "專著", "巨著", "名著", "遺著", "新著",
    "著明", "昭著", "卓著",
]


def read_table(path):
    """逐行读 OpenCC 表。值是空格分隔的候选，OpenCC 取第一个。"""
    table = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            key = parts[0].strip()
            values = parts[1].split()
            if not key or not values:
                continue
            table[key] = values[0]
    return table


def build():
    data = {}
    for name, path in SOURCES.items():
        if not os.path.exists(path):
            sys.exit(
                "缺少字表：%s\n（MCPDict 需與 SiongDict 平級，且已 sparse-checkout "
                "app/src/main/assets/opencc）" % path)
        table = read_table(path)
        # 字表里「自己映射到自己」是纯冗余，去掉；词表里同形条目**必须保留**——
        # 那正是例外表的作用（「乾坤」不許变成「干坤」）。
        if name == "chars":
            table = {k: v for k, v in table.items() if k != v}
        data[name] = dict(sorted(table.items()))
        print("  %-28s %6d 条" % (os.path.basename(path), len(table)))

    # 本地叠一层「著」规则：先算出保护词表的简体写法（此时字表还没有「著」），
    # 再把「著 → 着」的默认规则加进字表。
    chars = data["chars"]
    added = 0
    for word in ZHU_KEEP:
        simplified = "".join(chars.get(c, c) for c in word)
        for key in {word, simplified}:
            if data["phrases"].get(key) != simplified:
                data["phrases"][key] = simplified
                added += 1
    chars["著"] = "着"
    data["phrases"] = dict(sorted(data["phrases"].items()))
    print("  %-28s %6d 条（本地「著」保护词表，%d 条新词条）"
          % ("ZHU_KEEP", len(ZHU_KEEP), added))

    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))

    longest = max(len(k) for k in data["phrases"])
    print("\n最長詞條 %d 字；輸出：%s（%.1f KB）"
          % (longest, OUTPUT, os.path.getsize(OUTPUT) / 1024))

    print("\n抽样：")
    for s in ["乾坤", "乾乾淨淨", "一目瞭然", "頭髮", "隻身", "著手", "裡面", "濕氣"]:
        out, i = [], 0
        while i < len(s):
            for n in range(min(longest, len(s) - i), 0, -1):
                piece = s[i:i + n]
                if piece in data["phrases"]:
                    out.append(data["phrases"][piece])
                    i += n
                    break
            else:
                out.append(data["chars"].get(s[i], s[i]))
                i += 1
        print("  %s → %s" % (s, "".join(out)))


if __name__ == "__main__":
    build()
