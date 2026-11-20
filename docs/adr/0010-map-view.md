# 地图展示：把聚类结果摊到离线省界底图上

**状态**：已采纳（2026-11-20）
**相关**：ADR-0008（聚类分析与三维可视化）、ADR-0007（方言范围）、`AGENTS.md` §1（大型中间资料不进库）、§5.1

## 背景

聚类分析给的是三维散点：能看出「哪些点聚在一起」，看不出「这些点在地理的哪里」。使用者的实际问题是「这一类的方言点集中在湘南还是湘北」，三维图回答不了。需求是在聚类全屏页加一个「在地圖展示」入口，把同一份分区摊到地图上。

约束有三条：**必须离线**（本 App 除更新检查外不联网，见 ADR-0009）、**不新增依赖**（不为一张示意图引地图库或图砖服务）、**必须与聚类结论一致**（地图只换投影，不重新聚类）。

## 决定

### 1. 底图是离线派生的省级轮廓，随仓库入库

- `tools/build_map.py`（纯 Python 3 标准库）从上游 MCPDict 的 `china.json` 中按 bbox **经度 100.2–119.7、纬度 21.5–33.5** 裁出湖南省域附近，用 Douglas–Peucker 简化（容差 **0.005°**），坐标保留 **4 位小数**，输出 `{"provinces":[{"name":…,"polygons":[[[lon,lat],…],…]}]}`。
- 产物 `app/src/main/assets/maps/provinces.json`：**61,756 B、20 个省、44 个环、3,253 个顶点**，已入库。上游 `china.json`（约 928 KB）与任何原始 geojson 一律只留本机（`AGENTS.md` §1、§7.11）——仓库只放这一份派生底图。
- 授权：源自 MCPDict（MIT），来源与重生成步骤写在 `app/src/main/assets/maps/README.md`。
- 只做省级轮廓，不含市县界、河流、地名——需求只是「看出大致方位」，更细的底图会让资产变大且没有对应收益。

### 2. 投影：Web Mercator + 等比置中

- 纵坐标用 Web Mercator：`mercatorY(lat) = ln(tan(π/4 + lat·π/360))`，纬度先夹在 ±85° 避免 tan 爆炸；`inverseMercatorY` 供反解。
- `analysis/MapProjection.kt` 的 `MapTransform` 保存 (minLon, maxLon, minMercatorY, maxMercatorY, scale, offsetX, offsetY, contentWidth, contentHeight)：`project(lon, lat)` → 画布像素（x 向右、y 向下），`unproject(x, y)` → 经纬度（供点选命中与手势换算）。**X 用经度 × π/180（弧度）、Y 用 Mercator 纵坐标（本身即弧度），两轴同单位、共用同一 scale**，因此不会横向或纵向拉伸变形；`contentBounds()` 给出内容在画布上的外接矩形，供泰森多边形裁剪。
- `fitTransform(sites, shapes, width, height, padding = 12dp)` 依底图外接矩形建立变换（底图优先，保证整张地图可见；底图缺失时退回站点范围并至少留 0.05° 边距，避免单点时除零），等比缩放 `scale = min(可用宽/经度跨度, 可用高/Mercator 跨度)`，内容在画布内置中。

### 3. 泰森多边形：半平面裁剪，O(n²)

- `analysis/Voronoi.kt` 对每个站点从画布矩形开始，依序用「到本站点不比到其他站点远」的半平面（`2(rx−sx)·p ≤ |r|² − |s|²`）裁剪，即 Sutherland–Hodgman：剩下的多边形就是它的势力范围。
- 站点数 n ≤ 400，O(n²) 在手机上是毫秒级；只算画布内的有限单元，不需要处理无限单元。
- 重合站点（距离恒等）不互相裁剪；多边形被裁到少于 3 点时返回空列表，调用端跳过填色。
- 多边形用**类别调色盘**淡填（与聚类图例同一套色，见 ADR-0008 §7），站点用音典颜色，两套不混用。
- 只画**参与当前聚类**的点（真实库默认门槛 4 时 209 点，手动门槛 1 时 383 点）：地图表达的是「这次分区的落点」，不是同源词库全量。

### 4. 交互与入口

- 入口：聚類分析全屏页「顯示名稱」下方的「在地圖展示」按钮（`cluster_map_open`）→ `ClusterMapScreen`（`cluster_map_screen`）；返回键由上层的聚類页接管——**先退地图、再退聚類页**（`cluster_map_close` 与系统返回键同一条路径）。
- 手势：单指拖动 = 平移；双指捏合 = 缩放 **0.6–8 倍**；双击复原。手势只改缩放与平移，底图、多边形、站点、标签全画在同一变换下。
- 轻点站点（命中半径 16dp）选中并显示详情（方言点、类别、义项／条目数），再点空白处取消；选中点半径加大。
- 两个开关：**標籤**（`map_labels_switch`，默认开，10sp，上限 80 个——超出就不再画，避免糊成一片）与**多邊形**（`map_voronoi_switch`，默认开）。
- 站点半径 4dp、选中 7dp、地图四周留白 12dp；站点缺经纬度时直接跳过（真实库 491/491 有效，正常情况下不会跳过）。

### 5. 降级：底图缺失只画站点

- `data/MapAssets.kt` 负责读 assets：进程内缓存，解析 `provinces` 数组、坏项逐项跳过；资产缺失或 JSON 坏掉时只打 `Log.w` 并返回空表，不崩。
- 空底图时地图页只画站点与标签，并提示「底圖資產缺失，僅顯示站點位置。」——功能降级但不阻断。

### 6. 确定性

- 投影、视图变换、泰森多边形全是纯函数，无随机数；同一份分区、同一画布尺寸得到同一张图，截图可复现。手势只改视图参数，不重算聚类。

## 投影单位与修正记录（2026-11-20）

- **病因**：`project()` 的 X 轴用「经度（度）」、Y 轴用 `mercatorY` 返回的「弧度」，两者却共用一个 `scale`。度与弧度相差 180/π ≈ **57.3 倍**，纵向因此被压扁 57.3 倍。
- **后果**：真实 491 个方言点在 1074×1000 画布上只得到约 **1050 × 12.75 px** 的横带，投影点几乎全部落在同一行（y 标准差不到 1 px、不同 y 值极少），泰森多边形退化成贯穿画布的竖条；App 里「在地圖展示」实际上不可读。
- **修法**：经度先 `× π/180` 换成弧度，与 Mercator 纵坐标同单位、共用同一 `scale`；`MapTransform` 显式记录 `contentWidth`／`contentHeight` 并提供 `contentBounds()`；`GeoMapView` 的泰森多边形改为裁剪到 `contentBounds()` 并 `clipRect`，不再铺满整个画布。
- **修正后真实尺寸**：1074×1000 画布、padding 12dp 时内容约 **1050 × 730.5 px**（宽高比 1.437）；491 个站点投影后 y 标准差约 **97.6 px**（contentHeight 的 0.134 倍），不同 y 值 490 个——点不再共线。
- **回归锁**：JVM `MapProjectionTest` 的 `projectedAxesUseSameScale`（两轴每弧度像素数一致）、`threeSitesOnDifferentLatitudesDoNotCollapse`（不同纬度纵向跨度 > 100 px）、`fitTransformUsesShapeBoundsAndKeepsAspectRatio`（内容宽高比 = 弧度跨度比、contentHeight 不能是横带）；仪器化 `DataLayerTest.mapProjectionKeepsGeographicProportion`（真实 491 点 + 真实底图：内容高宽比误差 < 1%、contentHeight > 500 px、点不共线、Voronoi 单元格落在 contentBounds 内）。改动 `MapProjection` 的单位约定时这几条会先红。

## 考虑过的方案

- **线上地图瓦片（Google／高德／OSM）**：需要联网、需要 key 或受配额与授权限制，违反「除更新检查外离线」的硬约束。否决。
- **引入地图库（osmdroid、MapLibre 等）**：为一个只画省界与几百个点的示意图引入几 MB 依赖和生命周期负担，收益不成比例。否决。
- **手绘湖南轮廓写死在代码里**：要手写几百个坐标、精度与维护都差，还不如让脚本从上游数据生成。否决。
- **按经纬度做地理聚类（k-means 等）**：那衡量的是地理位置，不是词汇同源关系，会把「地理相邻」误当成「词汇相近」。地图只做展示，分区仍由同源词相似度决定。否决。
- **用站点凸包代替泰森多边形**：凸包只能画整体范围，画不出「每个点的势力范围」，类别交界处还会互相覆盖。否决。

## 后果与限制

- 只有省级轮廓，看不到市县界与地名；要认具体县市得回到聚類分析列表与「關於」里的方言点名称。
- 泰森多边形是**视觉近似**，不是统计意义上的边界，也不代表「方言边界」；它只是把「最近的站点」画出来。
- 底图坐标 4 位小数约 11 m 精度，远超本页需要；但若上游 `china.json` 结构变化，`build_map.py` 需要同步调整并重新核对 B 数与省／环／顶点数。
- 站点分布密集处（湘中南）多边形会很碎，属正常现象；标签上限 80 也会让稠密区只显示一部分。
- 地图页全离线：不要加图砖、第三方地图库或任何联网请求（唯一联网用途见 ADR-0009、`AGENTS.md` §7.10）。

## 实施

- 资料管线：`tools/build_map.py`；资产 `app/src/main/assets/maps/provinces.json`（已入库）与 `app/src/main/assets/maps/README.md`（来源、授权、重生成步骤）。
- 纯函数：`analysis/MapModels.kt`（`LonLat`、`ProvinceShape` 多环多边形、`MapSite`、`parseLonLat`）、`analysis/MapProjection.kt`（`mercatorY`／`inverseMercatorY`、`project`／`unproject`、`fitTransform`）、`analysis/Voronoi.kt`（`voronoiCells`、`PlaneRect`、`PlanePoint`）。
- 数据与界面：`data/MapAssets.kt`（读 assets、缓存、降级）、`ui/ClusterMapScreen.kt`（全屏页、标题、两个开关、图例、选中详情、返回）、`ui/GeoMapView.kt`（Compose Canvas：省界底图 + 泰森多边形 + 站点 + 标签、手势与点选）。
- 测试：地图相关 JVM 17 项——`MapProjectionTest` 8（Mercator 正反解、project/unproject 往返、fitTransform 缩放与置中、空底图退化、两轴同单位、等比宽高比、不同纬度不塌陷）、`VoronoiTest` 6（裁剪、重合站点、单站点、退化为空）、`GeoCoordinateTest` 3（经纬度解析与范围校验）；仪器化 27 项中聚類用例顺带断言每个点经纬度落在底图 bbox 内（100.2–119.7／21.5–33.5），`mapProjectionKeepsGeographicProportion` 用真实 491 点 + 真实底图锁投影单位与内容框。
- 发布：0.5-alpha.5 / versionCode 53（2026-11-20）对外发布，地图展示页随该版上线。
