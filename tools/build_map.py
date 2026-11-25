#!/usr/bin/env python3
"""從 MCPDict 的 china.json 產生離線省級底圖（assets/maps/provinces.json）。

只保留湖南省域附近的省級輪廓，供「研究 → 聚類分析 → 在地圖展示」畫底圖用：
- 來源：../MCPDict-master/app/src/main/assets/maps/china.json（MIT 授權）
- 裁切：經度 108.0–117.2、緯度 22.8–31.9（Sutherland–Hodgman 裁到矩形；2026-11-25 由 100.2–119.7／21.5–33.5 收窄到維護者黃框範圍）
- 簡化：Douglas–Peucker，容差 0.005°
- 座標：保留 4 位小數
- 輸出：{"source":…, "simplify":…, "provinces":[{"name":…,"polygons":[[[lon,lat],…],…]}]}

只用 Python 3 標準庫；可重複執行，輸出確定（無隨機）。
"""
import json
import math
import os
import sys

MCPDICT_CHINA = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "MCPDict-master", "app", "src", "main", "assets", "maps", "china.json",
)

# 沒有 sparse-checkout 出 maps/ 時，可先從上游抓一份到 publish/ 再傳路徑：
#   curl -L -o publish/china.json https://raw.githubusercontent.com/osfans/MCPDict/master/app/src/main/assets/maps/china.json
#   python3 tools/build_map.py publish/china.json
FALLBACK_CHINA = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "publish", "china.json",
)
OUTPUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "assets", "maps", "provinces.json",
)

MIN_LON, MAX_LON = 108.0, 117.2
MIN_LAT, MAX_LAT = 22.8, 31.9
TOLERANCE = 0.005
DECIMALS = 4


def clip_ring(ring, min_lon, max_lon, min_lat, max_lat):
    """把一個環裁到矩形內（Sutherland–Hodgman，四個半平面）。"""
    def clip(points, inside, intersect):
        if not points:
            return []
        output = []
        count = len(points)
        for index in range(count):
            current = points[index]
            nxt = points[(index + 1) % count]
            current_in = inside(current)
            next_in = inside(nxt)
            if current_in:
                output.append(current)
            if current_in != next_in:
                output.append(intersect(current, nxt))
        return output

    def clip_vertical(points, boundary, keep_greater):
        def inside(point):
            return point[0] >= boundary if keep_greater else point[0] <= boundary

        def intersect(a, b):
            t = (boundary - a[0]) / (b[0] - a[0])
            return (boundary, a[1] + (b[1] - a[1]) * t)

        return clip(points, inside, intersect)

    def clip_horizontal(points, boundary, keep_greater):
        def inside(point):
            return point[1] >= boundary if keep_greater else point[1] <= boundary

        def intersect(a, b):
            t = (boundary - a[1]) / (b[1] - a[1])
            return (a[0] + (b[0] - a[0]) * t, boundary)

        return clip(points, inside, intersect)

    points = list(ring)
    points = clip_vertical(points, min_lon, True)
    points = clip_vertical(points, max_lon, False)
    points = clip_horizontal(points, min_lat, True)
    points = clip_horizontal(points, max_lat, False)
    return points


def perpendicular_distance(point, start, end):
    if start == end:
        return math.hypot(point[0] - start[0], point[1] - start[1])
    x0, y0 = point
    x1, y1 = start
    x2, y2 = end
    denominator = math.hypot(x2 - x1, y2 - y1)
    return abs((x2 - x1) * (y0 - y1) - (x1 - x0) * (y2 - y1)) / denominator


def simplify(points, tolerance):
    """Douglas–Peucker（迭代版，避免遞迴深度問題）。"""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        start, end = stack.pop()
        if end <= start + 1:
            continue
        max_distance = -1.0
        index = start
        for i in range(start + 1, end):
            distance = perpendicular_distance(points[i], points[start], points[end])
            if distance > max_distance:
                max_distance = distance
                index = i
        if max_distance > tolerance:
            keep[index] = True
            stack.append((start, index))
            stack.append((index, end))
    return [point for point, kept in zip(points, keep) if kept]


def round_point(point):
    return [round(point[0], DECIMALS), round(point[1], DECIMALS)]


def main():
    source = MCPDICT_CHINA
    if len(sys.argv) > 1:
        source = sys.argv[1]
    elif not os.path.exists(source) and os.path.exists(FALLBACK_CHINA):
        source = FALLBACK_CHINA
    with open(source, encoding="utf-8") as handle:
        data = json.load(handle)

    provinces = []
    for feature in data.get("features", []):
        properties = feature.get("properties") or {}
        name = properties.get("name") or ""
        geometry = feature.get("geometry") or {}
        if geometry.get("type") == "Polygon":
            polygons = [geometry.get("coordinates") or []]
        elif geometry.get("type") == "MultiPolygon":
            polygons = geometry.get("coordinates") or []
        else:
            continue

        kept = []
        for polygon in polygons:
            if not polygon:
                continue
            ring = polygon[0]  # 只取外環：底圖不需要洞
            clipped = clip_ring(ring, MIN_LON, MAX_LON, MIN_LAT, MAX_LAT)
            if len(clipped) < 3:
                continue
            simplified = simplify(clipped, TOLERANCE)
            if len(simplified) < 3:
                continue
            kept.append([round_point(point) for point in simplified])
        if kept:
            provinces.append({"name": name, "polygons": kept})

    provinces.sort(key=lambda item: item["name"])
    payload = {
        "source": "MCPDict app/src/main/assets/maps/china.json (MIT)",
        "simplify": TOLERANCE,
        "bbox": [MIN_LON, MIN_LAT, MAX_LON, MAX_LAT],
        "provinces": provinces,
    }
    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
        handle.write("\n")
    size = os.path.getsize(OUTPUT)
    points = sum(len(ring) for item in provinces for ring in item["polygons"])
    print("provinces=%d polygons=%d points=%d bytes=%d" % (
        len(provinces),
        sum(len(item["polygons"]) for item in provinces),
        points,
        size,
    ))
    print("names=" + ",".join(item["name"] for item in provinces))
    print("source=" + source)


if __name__ == "__main__":
    main()
