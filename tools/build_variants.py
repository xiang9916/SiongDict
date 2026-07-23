#!/usr/bin/env python3
"""
Build character variant mapping from MCPDict's 正字.tsv.

Produces a JSON file mapping each character to all its variant forms
(simplified, traditional, and orthographic variants).

The mapping is bidirectional: if A maps to B, then B maps to A.
"""

import json
import os
import sys
from collections import defaultdict

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
VARIANT_FILE = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "..", "MCPDict-master", "tools", "tables", "data", "正字.tsv")
)
OUTPUT = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "variants.json")
)


def build_variants():
    # Build adjacency: for each char, which chars are its variants
    adj = defaultdict(set)

    with open(VARIANT_FILE, encoding="U8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            src = parts[0].strip()
            # Remove inline comments and split variants
            dst_raw = parts[1].split("#")[0].strip()
            dsts = dst_raw.split()
            if not src or not dsts:
                continue
            for dst in dsts:
                if dst:
                    adj[src].add(dst)
                    adj[dst].add(src)

    # Build connected components (union-find style)
    visited = set()
    components = []
    for char in adj:
        if char in visited:
            continue
        # BFS to find all connected chars
        queue = [char]
        group = set()
        while queue:
            c = queue.pop()
            if c in visited:
                continue
            visited.add(c)
            group.add(c)
            for neighbor in adj[c]:
                if neighbor not in visited:
                    queue.append(neighbor)
        if len(group) > 1:
            components.append(sorted(group))

    # Build the lookup: each char -> list of all variants (including itself)
    result = {}
    for group in components:
        for char in group:
            result[char] = sorted(group)

    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Built {len(result)} character variant mappings")
    print(f"Output: {OUTPUT}")
    print(f"Size: {os.path.getsize(OUTPUT) / 1024:.1f} KB")

    # Show some examples
    for example in ["东", "東", "躲", "爺", "爷", "藏", "门", "門"]:
        if example in result:
            print(f"  {example} -> {result[example]}")


if __name__ == "__main__":
    build_variants()
