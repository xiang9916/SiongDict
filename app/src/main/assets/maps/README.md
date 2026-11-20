# 離線底圖（provinces.json）

「研究 → 聚類分析 → 在地圖展示」用的省級輪廓底圖，**完全離線**，不含任何線上圖磚。

- **來源**：[osfans/MCPDict](https://github.com/osfans/MCPDict) 的 `app/src/main/assets/maps/china.json`（MIT 授權）。
- **範圍**：只保留湖南省域附近的省級單元，經度 100.2–119.7、緯度 21.5–33.5。
- **處理**：每個外環先裁到上述矩形（Sutherland–Hodgman），再做 Douglas–Peucker 簡化（容差 0.005°），座標保留 4 位小數。
- **格式**：`{"source":…,"simplify":…,"bbox":[minLon,minLat,maxLon,maxLat],"provinces":[{"name":"湖南","polygons":[[[lon,lat],…],…]}]}`；一個省可能有多塊多邊形。
- **重生成**：`python3 tools/build_map.py`（需要 MCPDict 的 `china.json`；若 sparse-checkout 沒抓 `maps/`，可先下載到 `publish/china.json`，再 `python3 tools/build_map.py publish/china.json`）。
- **更新時機**：上游 `china.json` 變動、或地圖範圍要調整時；重新生成後請跑一次 `GeoCoordinateTest` 與儀器化的地圖頁測試。

本檔只含簡化後的省級輪廓（約 60 KB），不是上游原始 GeoJSON，請勿把上游大檔放進倉庫。
