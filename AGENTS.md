# AGENTS.md — 湘典 SiongDict 开发须知

> 给在此仓库工作的 AI／开发者。**2026-11-20 汇总**，代码对应 `0.5-alpha.5`。
>
> ⚠️ **本文件在仓库内、会随仓库一起公开** —— 所以这里只写可公开的事实：不含建置机绝对路径（`/Users/<使用者名>/…`）、
> 不含任何签名凭证或其口令、不含未公开的个人资料。动代码、资料或发版之前先读一遍。

## 0. 现状速览

- 原生 Android App（Kotlin + Jetpack Compose + Material 3），离线 SQLite FTS5 全文检索；唯一联网用途是启动时与手动「檢查更新」查 GitHub Release（见 ADR-0009、§7.10），其余功能无需联网。
- 语言约定：**开发文档一律用简体中文**（本文件、`CONTEXT.md`、`docs/adr/*`、交接文档与技能文档）；**面向使用者的前端内容用繁体中文**（App 界面文案、`README.md`、`CHANGELOG.md`、义类标签）——本项目以汉字研究为主，繁体是显示层标准；开发侧用简体提升效率。
- 当前版本 **0.5-alpha.5**：`versionCode 53`、`versionName "0.5-alpha.5"`，只存在于 `app/build.gradle`。
- 线上仓库 `xiang9916/SiongDict`；`master` 是唯一分支，**没有** `develop`、也**没有** `.github/workflows`（无 CI）。
- 资料规模：**491 个方言点**、**609,323 条读音**、124,577 组同音字；同源词 **206 组 / 2,804 条**（全数人工标注，无自动条目）。
- git 仓库根 = 本文件所在目录；父目录不是仓库，所有 git 命令都在本目录内执行。
- `.git` 约 **9 MB**：2026-10-04 重写过一次历史，把非代码大档全部剔除（见 §7.8）。**该日之前的 clone 已与远端分岔，请重新 clone 后再推送。**
- 全部读音资料来自 [汉字音典 MCPDict](https://github.com/osfans/MCPDict)。本项目只做三件事：筛选湘语相关方言点、重建检索索引、人工标注同源词。

## 1. 🔴 红线：这些东西不进仓库

| 类别 | 说明 |
| --- | --- |
| 建置机绝对路径 | 被跟踪文件里的路径一律用占位写法（如 `/path/to/android-sdk`），真实建置机路径只出现在本机的未跟踪文件与命令里，**日志与例外堆叠同样按此处理**。 |
| 签名凭证 | `*.keystore`、`*.jks`、`keystore.properties`、`signingkey.jks` 全在 `.gitignore`。本仓库**不含任何签名档**；签名文件只留在本机，由 `.gitignore` 覆盖。 |
| 数据库二进制 | `*.db` 与 `*.db.bak-*` 在 `.gitignore`（理由见 §3）。V0.4.3 曾误入 5 个 `cognates.db.bak-*`，还被打进 APK；已于 V0.4-rc.4 清除。 |
| 大型中间资料 | `tools/tables/`（MCPDict 上游字表，单档最大 47 MB）、地图上游原件 `MCPDict-master/app/src/main/assets/maps/china.json`（约 928 KB）与任何原始 `*.geojson`、字型 `*.ttf/*.otf`、`*.tsv`、`*.apk`、旧 `assets/databases/` 都曾进过历史，**保持它们只在本机**（否则 `.git` 又会暴涨，得再重写一次历史，见 §7.8）。仓库里的 `app/src/main/assets/maps/` 只放需求用到的派生底图 `provinces.json`（61,756 B；20 省／44 环／3,253 顶点，由 `tools/build_map.py` 从上游 `china.json` 裁切简化，见 ADR-0010）；上游 `china.json` 与原始 geojson 一律不进库。 |
| Release notes | 它挂在 GitHub 上、不在仓库里，但同样按上表自查。 |

发版前自查（除 `.gitignore` 自身列举关键字属预期命中外，其余应零命中）：

```bash
cd SiongDict
git grep -nI -E '/(Users|home)/[A-Za-z0-9_.-]+' -- . ':!.gitignore'
git grep -nI -E 'password|passwd|secret|api[_-]?key|BEGIN [A-Z ]*PRIVATE KEY|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}' -- . ':!.gitignore'
git ls-files | grep -E '\.(keystore|jks)$|keystore\.properties'   # 应无输出
```

`.kotlin/errors/*.log` 是 Kotlin daemon 崩溃日志，会把机器的家目录路径写进去（历史上的 `errors-*.log` 就是这样混进仓库的），
`.gitignore` 已加 `.kotlin/`；本机重跑建置后若又生成，按 `.gitignore` 忽略即可。

## 2. 目录结构

```
app/src/main/java/org/siongdict/app/
  MainActivity.kt            入口（主题 + 首帧前初始化简体渲染）
  data/AppPrefs.kt           偏好档名与键名（版本记录、简体渲染开关共用）
  data/AppInfo.kt            读取 versionName（界面标题与数据库版本记录共用）
  data/DatabaseFiles.kt      assets → 私有目录复制（延迟 + 原子 + 自愈）、只读连接、版本比对、重置
  data/DbGate.kt             数据层串行化（Mutex + IO 线程），查询与重置的唯一排序点
  data/Lexicon.kt            字音库端口与本地实现（FTS 检索、info 元数据、变体展开）
  data/CognateBook.kt        同源词库端口与本地实现（可选库、组查询与显示排序）
  data/DatabaseMaintenance.kt 供给/重置端口与本地实现
  data/VariantIndex.kt       繁简/异体展开（assets/variants.json，进程内缓存）
  data/ToneSystem.kt         声调系统值对象
  data/ToneFormatter.kt      声调格式化（只接受 ToneSystem）
  data/LocalData.kt          本地数据层装配根（端口 + 共享设施）
  data/Models.kt             资料模型
  data/Simplifier.kt         繁→简的显示层转换（读 assets/t2s.json）
  data/UpdateChecker.kt      更新检查（GET GitHub releases/latest，8s 超时，纯函数可 JVM 测）
  data/UpdateState.kt        更新检查结果缓存（SharedPreferences + 进程内状态）
  data/EyeCare.kt            护眼模式开关（浅米色主题背景 0xFFF2EEE6）
  data/MapAssets.kt          離線底圖資產載入（assets/maps/provinces.json，壞檔回空表並 Log.w）
  analysis/ClusterModels.kt  聚类分析结果模型（点含经纬度、候选分区、稀疏阈值轮廓系数 0.10）
  analysis/Similarity.kt     义项相似度 → 距离矩阵（共同义项 +1／−1／不计）
  analysis/HierarchicalClustering.kt UPGMA 平均链接 + 轮廓系数（k=2..min(10,n−1)，轮廓系数降为对照）
  analysis/CommunityDetection.kt 确定性 Louvain 社群偵測（边权 = 相似度，≤3 层，模组度增益 <1e-9 停）
  analysis/PartitionSearch.kt 候选分區搜索（UPGMA 各 k + Louvain γ 网格 0.25–4 与每 k 二分 γ；每簇 ≥ max(2, ⌈5%n⌉) 才算可接受；自動挑輪廓係數最高，平手取小 k）
  analysis/ClusterLayout.kt  确定性三维力导向布局 + 分群锚点布局（Fibonacci 球面初值／锚点，无随机数，200 轮）
  analysis/ClusterEngine.kt  聚类入口（筛选树切片、minTags 排除、候选分區 + 自動兩參數、輪廓係數稀疏判定、經緯度帶入）
  analysis/MapModels.kt      地圖模型（LonLat、ProvinceShape 多環多邊形、MapSite、parseLonLat）
  analysis/MapProjection.kt  Web Mercator 投影與視圖變換（mercatorY／inverseMercatorY、project／unproject、fitTransform）
  analysis/Voronoi.kt        泰森多邊形（半平面裁剪，O(n²)，純函數）
  ui/SearchScreen.kt         检索主界面（搜索栏、模式切换、结果列表与右侧跳转条、方言筛选）
  ui/DialectTree.kt         方言筛选树（音典分区 → 三级节点、纯函数）
  ui/ResultCard.kt           结果卡片（字组／义项标题、方言点读音、同源词展开、复制按钮）
  ui/InfoDialog.kt           「關於」面板（说明页、檢查更新行、護眼模式开关、简体渲染开关、重置資料庫）
  ui/UiText.kt               显示层文本工具（简体渲染、字组标题）
  ui/icons/SiongIcons.kt     内联图标（两个 Material 图标 + 自绘「研究」放大镜原子图；替代 material-icons-extended）
  ui/theme/Theme.kt          品牌色与 Material 3 主题
  ui/SearchViewModel.kt      检索、分组、方言筛选、聚类分析（构造注入数据端口，普通 ViewModel）
  ui/SearchViewModelFactory.kt 生产装配（LocalData → 三个端口注入 ViewModel）
  ui/CognateExport.kt        同源词 CSV 导出
  ui/ResearchDialog.kt       「研究」一级对话框（白底 Dialog + 右上「關閉」；目前只有聚类分析一项）
  ui/ClusterAnalysisScreen.kt 聚类分析全屏页（非对话框；一行摘要「模組度 · 輪廓係數」、参数、图例、选中点详情、在地圖展示入口）
  ui/ClusterMap.kt           聚类三维散点画布（透视投影、分群聚拢动画、单指旋转、双指缩放平移、双击复位、标签去重叠、点选）
  ui/ClusterProjection.kt    三维透视投影（相机距离／焦距／偏航俯仰常量、深度半径缩放，纯函数）
  ui/ClusterMapScreen.kt     地圖展示全屏页（标题、標籤／多邊形开关、图例、點選詳情、返回聚類分析）
  ui/GeoMapView.kt           地圖畫布（省界底圖 + 泰森多邊形 + 站點，手勢縮放平移，純 Compose Canvas）
  ui/ClusterColors.kt        类别调色盘与音典颜色解析（#RRGGBB／双色渐变）
app/src/androidTest/java/org/siongdict/app/
  DataLayerTest.kt           数据层回归（只读连接、自愈重拷、异体展开、同源词、声调、重置、检索/重置串行化、方言画像、同源词成员）
  UiSmokeTest.kt             界面回归（首帧、重复字、三模式、方言筛选、关于面板、研究入口）
app/src/test/java/org/siongdict/app/
  data/ToneFormatterTest.kt  声调格式化（纯函数，JVM）
  data/VariantIndexTest.kt   变体展开（纯逻辑，JVM）
  analysis/SimilarityTest.kt 义项相似度与距离（纯函数，JVM）
  analysis/HierarchicalClusteringTest.kt UPGMA／轮廓系数／平手规则（纯函数，JVM）
  analysis/ClusterLayoutTest.kt 布局确定性与边界（纯函数，JVM）
  analysis/ClusterEngineTest.kt 筛选切片、minTags、稀疏判定、默认门槛、经纬度传递（纯函数，JVM）
  analysis/PartitionSearchTest.kt 候选池、可接受性、自动挑选与平手规则（纯函数，JVM）
  analysis/MapProjectionTest.kt Mercator 投影与视图变换往返（纯函数，JVM）
  analysis/VoronoiTest.kt    泰森多边形裁剪与边界（纯函数，JVM）
  analysis/GeoCoordinateTest.kt 经纬度解析与范围校验（纯函数，JVM）
  data/VersionComparatorTest.kt 版本号比较（预发布／数字段，纯函数，JVM）
  data/UpdateCheckerTest.kt  更新 tag 解析（纯函数，不联网，JVM）
  ui/ClusterColorsTest.kt    调色盘与颜色解析（纯函数，JVM）
  ui/DialectTreeTest.kt      方言筛选树（分区路径与叶子键，纯函数，JVM）
  ui/SearchViewModelTest.kt  检索装配/分组/筛选/代际守卫/聚类快照（fake 端口，JVM）
  fakes/Fakes.kt             测试用 fake 端口
app/src/main/assets/
  databases/siongdict.db    ★ 不入库，必须自行重建（§3、§4）
  databases/cognates.db     ★ 不入库，必须自行重建；缺失时 App 不会崩，「搜同源」自动停用
  variants.json             异体字对映，**已入库**（由 tools/build_variants.py 产生；繁体、简体、异体三种写法互查）
  t2s.json                  繁→简对映，**已入库**（由 tools/build_simplifier.py 产生；含本地「着」保护词表，见该脚本与 docs/adr/0003）
  maps/provinces.json       離線省界底圖，**已入库**（61,756 B；20 省／44 環／3,253 頂點；由 tools/build_map.py 從 MCPDict china.json 裁切簡化，MIT，見 ADR-0010）
  maps/README.md            底圖來源、授權與重生成步驟
  README.md / CHANGELOG.md  **已入库**；App 内「说明」页读的就是这两份，必须与仓库根目录的同名文件逐字节一致
tools/                       资料管线（纯 Python 3 标准库，无第三方依赖、无 requirements.txt）
  build_db.py                MCPDict 字表 → siongdict.db
  build_variants.py          正字.tsv + OpenCC 字表 → variants.json（繁简异体混搜）
  build_simplifier.py        OpenCC TSPhrases/TSCharacters → t2s.json（简体渲染）
  cognate_pipeline.py        桥字验证 → cognates.db
  ipa_parser.py              IPA 声母／韵母／声调切分，被 cognate_pipeline.py 导入
  build_map.py               MCPDict china.json → assets/maps/provinces.json（bbox 100.2–119.7／21.5–33.5、容差 0.005°、4 位小数）
publish/                     本机出包暂存，已 gitignore
docs/                        开发文档：ADR（docs/adr）与长期参考资料（docs/reference，含 tone-relations.md 十六调关系表）
```

## 3. 数据库不入库 —— fork 之后必须自己重建

这是本项目最容易踩的坑：**clone 下来直接 `./gradlew assembleDebug` 会 BUILD SUCCESSFUL，但装上去一开就崩。**

原因有两层：

1. `app/src/main/assets/databases/` 整个目录不在仓库里 —— `.gitignore:10` 的 `*.db` 把两个数据库挡掉了；
2. 建置期不校验 assets 是否存在。崩溃发生在执行期：`DatabaseFiles.copyAssetDatabase()` 找不到 `databases/siongdict.db` 会直接抛例外（见 `data/DatabaseFiles.kt`）。

两个文件的性质不同：

| 文件 | 大小 | 缺失后果 |
| --- | --- | --- |
| `siongdict.db` | 约 55 MB | **必需**。App 启动即崩。含 `langs`（读音，FTS5）与 `info`（方言点元数据）两张虚拟表。数据库字段名沿用繁体写法（`字組`、`語言`、`讀音`、`註釋`、`排序`），写 SQL 时照抄。 |
| `cognates.db` | 约 300 KB | **可选**。只记一条 warn，App 正常跑，「搜同源」停用（`DatabaseFiles.copyCognateDatabase()`）。含 `cognates` 与 `cognate_groups`。 |

不把 `.db` 纳入版本控制是刻意的：`siongdict.db` 每次音典更新都会整体变动，一旦入库，**每次资料更新都会在 git 历史里永久留下一个 55 MB 的 blob**，仓库很快就会被撑爆。请走 §4 的重建路线，或直接取用 GitHub Release 的 APK。

## 4. 从 fork 到能跑：最短路径

### 前置

- **JDK 17**（`app/build.gradle` 的 `sourceCompatibility` / `targetCompatibility` / `jvmTarget` 都是 17）
- **Android SDK**：`platforms/android-35` + `build-tools/35.0.0`（`compileSdk 35` / `targetSdk 35` / `minSdk 24`）
- **Python 3**（管线只用标准库）
- Gradle 由 wrapper 自动下载（8.11.1），不必预装

### 步骤

```bash
# 1) 指定 SDK 路径（local.properties 已 gitignore，必须自建）
cd SiongDict
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 取得 MCPDict 上游字表。只 sparse 出需要的三块——完整 clone 是 GB 级
cd ..
git clone --filter=blob:none --sparse https://github.com/osfans/MCPDict.git MCPDict-master
cd MCPDict-master
git sparse-checkout set tools/tables/output tools/tables/data/正字.tsv app/src/main/assets/opencc
cd ../SiongDict
# ⚠️ MCPDict-master 必须与 SiongDict 平级：tools/*.py 的默认路径是 ../../MCPDict-master/...

# 3) 重建资料（脚本的默认输出路径就是 assets 下的正确位置）
python3 tools/build_db.py          # 读 ../MCPDict-master/tools/tables/output/_詳情.json 与 <简称>.tsv
python3 tools/build_variants.py    # 读 正字.tsv + opencc/{ST,TS}Characters、{HK,TW}Variants
python3 tools/build_simplifier.py  # 读 opencc/TSPhrases.txt 与 opencc/TSCharacters.txt
python3 tools/cognate_pipeline.py  # 读 siongdict.db，输出 cognates.db

# 4) 出包
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 几个必须知道的限制

- **只有 `build_db.py` 支持 `--mcpdict-dir`**。`build_variants.py` 的上游路径**写死在原始码常数里**，MCPDict 放在别处时要嘛改常数，要嘛做个同名软链接：`ln -s /path/to/MCPDict-master ../MCPDict-master`。
- `build_db.py` 依赖 `output/_詳情.json`；只 sparse 出各方言点 TSV 是跑不起来的。
- 数据库都是 SQLite FTS5，App 以**只读**开启，不写入使用者装置上的资料。

## 5. 资料更新流程（上游 → 本项目）

```bash
# 1) 更新 MCPDict（sparse clone 体积小，直接 fetch 即可）
cd MCPDict-master && git fetch && git checkout <新的 commit> && cd ../SiongDict

# 2) 重跑 §4 的四支脚本

# 3) 同步文件数字
#    README.md 的「491 个方言点、124,577 组同音字、609,323 条读音」
#    CHANGELOG.md 新增顶部条目
#    然后把这两份复制到 app/src/main/assets/（见 §7.2）

# 4) bump versionName / versionCode（见 §6），出包，发 Release
```

**人工同源词不会被重建吃掉。** `cognate_pipeline.py` 的 Step 0 先把 `cognates` 表里 `source='manual'` 的记录整批备份出来，Step 5 再插回并删掉同 `lang+ipa` 的自动条目（`load_manual_entries` / `insert_manual_entries`）。
但**上游方言点改名／消失时要人工检查引用**：条目按「字组＋读音＋方言点」唯一匹配，方言点一改名就会对不上而失效。

`cognate_pipeline.py` 顶部的 `LABEL_MAP` 是自动义类的中文标签表（tag → 标签），新增自动义类时要补一条。

旁边的 `SUPPRESSED_SEMANTIC_TAGS` 是**停用清单**：清单里的 tag 不再自动生成义类组，目前只含 `HIDE`。2026-10-05 撤下了 23 个 `Hide_*` 自动组（当时写作 `HIDE_*`，标签同为「躲藏/捉迷藏」，62 条），但 pipeline 每次重建都会把它们重新算出来——**只删数据库里的行是删不掉的，必须在这里挡**。反过来，要恢复某个自动义类，把它从这个集合里拿掉即可。这里的键是 `SEMANTIC_RULES` 吐出的 tag（全大写），不是组名。

**组名与义项的写法（2026-10-08 统一，英文一律大驼峰）：**

- `cognate_group` = `<义项>_<音类>`：`Press_gin4`、`Hide_dzɔŋ2`、`Segment_CutOpen_ʂak7`；
- `semantic_label` = **繁体中文**标签（前端显示用，如 `鋸`、`彎腰`）；`semantic_tag` = **义项**，即组名去掉末尾那段音类：`Press`、`Hide`、`Segment_CutOpen`。**一个义项下挂多个音类组是正确结构**——`Hide` 挂 7 组（原 8 组，2026-11-20 删除误收的 `Hide_ma1`，见 ADR-0005 附录二十）、`Press` 4 组、`Attach`/`Curse`/`Fuck` 各 4 组；
- 驼峰只作用于**纯 A–Z 字母**段；含音标或数字的段原样保留（`DJNbo2_bo2` 这类音类组不动），多词复合义项按语义切词（`GETWETINTHERAIN_dʒai6` → `GetWetInTheRain_dʒai6`）；
- 组名后缀是**音类标签**，**不保证等于组内某个成员的今读**：`Press_gin4`/`gin4`/`ɣan6`/`dzen4` 用的是中古音类名，`Curse_tʂʰak8`（成员今读是 tsʰu1/tsʰo1/tsʰa6…）、`Segment_CutOpen_ʂak7`（成员是 sa1/sa5/sa7…）、`SetUp_tun5`（成员是 tən5/ten5/tuen5/tin5…）用的是该音类的代表读音。2026-10-08 审计时 141 组里有 100 组如此，是正常结构，按既有组名使用即可；
- `cognate_pipeline.py` 的 `pascal_tag()` / `group_prefix()` 就是这条规则的实现，自动分组与人工条目都走它。

**「搜同源」结果的排序（2026-10-08 定案）：** 一律按 `cognate_group`（卡片副标那行英文，`<义项>_<音类>`）**不分大小写的字母序**，与命中相关度、方言点数多寡都无关。实现在 `LocalCognateBook.search()` 尾端的 `COGNATE_DISPLAY_ORDER`，改那个比较器就等于改全 App 的显示顺序；组**内**成员仍按 `sort_key`（音典排序）排列，两套顺序各自独立。

**入组硬规则（2026-10-08 定案，见 `docs/adr/0005-entries-must-have-gloss.md`）：** 新增同源词条必须**同时**满足 ——
① 该读音在字音库里 `註釋` 非空（**全库 67% 的行没有注释**，入组候选一律取自注释非空的行）；
② 注释的语义与该组**义项**相符，且经人工确认。
**「音类对得上」只是必要条件，入组理由须是注释语义相符**：同点同韵母里挤着几百个字，`SetUp_tun5` 就出现过
同点同韵母的 `敦 ten1` 注释是「敦促」、与该组的「敦／竖起」不是一个词。本规则**不追溯存量**
（现有 2,804 条成员中 587 条无注释，是当年按文献核定的；2026-10-13 另有一次经维护者逐条核可的例外，见 ADR-0005 附录），只约束新增。

维护者另有一套本机技能（同韵母比较判归音类、桥字验证、人工编辑同源词、CSV 导出）——**不随仓库分发**，仓库内的 `tools/*.py` 才是唯一事实来源。

### 5.1 聚类分析数据流（「研究」入口）

「研究」不是检索模式，而是独立的对照工具：顶栏「研究」图标 → `ResearchDialog` → `ClusterAnalysisScreen`（全屏页，不套 Dialog）。全部在手机端计算，离线、不新增依赖。

- **输入**：`Lexicon.dialectProfiles()`（`info` 表的 簡稱/音典分區/音典顏色/音典排序/經緯度，491 点，經緯度 491/491 有效）+ `CognateBook.memberships()`（`cognates` 的 lang/cognate_group/semantic_tag，2,804 条）。首次打开在 IO 上读一次存快照；之后调参只重算、不再读库；重置数据库作废快照并关闭全屏页。
- **范围**：先按**当地方言筛选树**切片（`selected == null` 全收，否则 `leafKey(division) ∈ selected`，与检索共用同一套前三段分区叶子键）；再排除没有同源词的点与义项数低于「最小义项数」的点。全屏页摘要只留一行「模組度 · 輪廓係數」（旧的三行摘要已删除，明细看日志与图例）。2026-11-20 实测：491 点中默认排除 108 个无同源词的点，剩 383 点。
- **算法**（`analysis/` 十个纯 Kotlin 文件）：义项 = `semantic_tag`；共同义项下共享至少一个 `cognate_group` 记 +1、都收录但无共享记 −1、缺义项不计；s = 总分／(|T| + λ)，λ = 2（收缩平均，s ∈ (−1,1)，共同义项越多同一比例越可信），距离 d = (1−s)/2（无共同义项时 d = 0.5）；**「自動」分群不再赌单一算法，而是先造候选池再挑**：候选 = UPGMA 各 k（2..min(10, n−1)）的分区 + 确定性 Louvain 在 γ 网格 0.25–4（10 档，边权 = 相似度、≤3 层、模组度增益 <1e-9 停）上的分区，并对每个 k 再二分 γ 6 步细化；**每个 k 只保留轮廓系数最高的候选**（平手优先 Louvain、再取小 γ），候选池按 k 升序。「自動」值 = 候选池里**每簇都不小于 `max(2, ⌈5%n⌉)`** 的候选中轮廓系数最高者（平手小 k → Louvain → 小 γ，`PartitionSearch`）；稀疏判定改为**轮廓系数 < 0.10**（`SPARSE_SILHOUETTE`），**模组度降为对照指标（`modularity`）**——按 Q 最大一定退化成 k=2 的粗切，不能用来选分类数。最小义项数的默认值也由数据定：`ClusterEngine.defaultMinTags()` 在 1..上限 内取「自动分群」轮廓系数最大的门槛，且该门槛须保留至少 50% 有同源词的点（`MIN_DEFAULT_COVERAGE`），否则轮廓系数会一路挑到只剩 10 点的门槛；该搜索只跑粗候选池（不做每 k 的 γ 二分，实测两者结果一致，二分会让开启延迟从约 6.5 秒增至约 20.5 秒）。**2026-11-20 仪器化实测（唯一可信值）**：门槛 1 时 383 点、自动 k=6、类别规模 61/108/75/62/31/46、轮廓 0.1587、Q ≈ 0.2723（仅对照）；默认门槛 4 时 209/383 点、自动 k=2、规模 178/31、轮廓 0.1972、Q ≈ 0.0997（仅对照），义项不足排除 174 点。布局改为三维力导向（Fibonacci 球面初值、归一化到单位立方体），画布做透视投影，支持单指旋转/双指缩放平移/双击复位；标签默认显示（≤40 点全显，否则按深度去重叠、上限 80）。**分群锚点布局 + 700ms 过渡动画**：先按当前分区的类号把同类点收缩到 Fibonacci 球面锚点附近（类号升序定锚点，类内按质心收缩、`spread = 0.35`，`anchorRadius = 0.35`），再由画布以 700 毫秒补间把点从旧坐标聚拢到新坐标——切换分类数时只重算分区与布局、不重跑聚类，聚拢过程可见。同一份资料坐标恒定。
- **交互**：分类数滑桿只换分区、不重跑（`selectedK`，可回「自动」）；最小义项数滑桿松手后按快照重算，上限固定为快照中最大义项数（夹在 1..10），调高门槛不会缩水。**最小义项数的默认值由数据决定**（规则见上「算法」段）；2026-11-20 实测默认门槛 4（209/383 点，义项不足排除 174 点）。点节点看该点义项／条目数并高亮所属类别；画布支持单指旋转、双指 0.6–4 倍缩放与平移、双击复位视角。
- **地图展示**：全屏页「顯示名稱」下方的「在地圖展示」按钮（`cluster_map_open`）→ `ClusterMapScreen`（`cluster_map_screen`），返回键先退地图、再退聚类页。底图是离线派生的 `assets/maps/provinces.json`（20 省／44 环／3,253 顶点，61,756 B，MCPDict `china.json` 裁切简化，MIT，见 ADR-0010），Web Mercator 投影后按站点经纬度画泰森多边形（`Voronoi.kt`，半平面裁剪 O(n²)）与站点点位，只画当前参与聚类的点（默认门槛 4 时 209 点；手动把门槛调到 1 时 383 点）。可切「標籤」与「多邊形」开关、点选站点看详情，手势缩放平移；全程离线，无图砖、无地图库。底图缺失时只画站点并提示「底圖資產缺失，僅顯示站點位置。」。投影时经度与 Mercator 纵坐标统一换算成弧度、共用同一个 `scale`（曾因「度／弧度」混用把整张图压成约 1050×12.75 px 的横带、泰森多边形退化成竖条，2026-11-20 修正，见 ADR-0010「投影单位与修正记录」）；泰森多边形只铺在地图内容框 `contentBounds()` 内并 `clipRect`，不铺满整个画布。
- **配色**：节点用音典顏色（`#RRGGBB`／`#AARRGGBB`／逗号双色渐变），类别用固定 10 色调色盘（凸包填色与图例），两套不混用。
- **复杂度**：O(n² + 成员条数)，n ≤ 383（有同源词的点），手机端毫秒到百毫秒级（候选池把聚类跑多次，仍是同阶）。
- 决策与取舍见 `docs/adr/0008-cluster-analysis.md`。

## 6. 版本号与发版

**`versionCode` 每个对外 tag 严格 +1**，按顺序递增：

| versionName | versionCode |
| --- | --- |
| 0.3.21 | 33 |
| 0.4.0 | 34 |
| 0.4.1 | 35 |
| 0.4.2 | 36 |
| 0.4.3 | 37 |
| 0.4-rc.4 | 38 |
| 0.4-rc.5 | 39 |
| 0.4-rc.6 | 40 |
| 0.4-rc.7 | 41 |
| 0.4-rc.8 | 42 |
| 0.4-rc.9 | 43 |
| 0.4-rc.10 | 44 |
| 0.4-rc.11 | 45 |
| 0.4-rc.12 | 46 |
| 0.4-rc.13 | 47 |
| 0.4-rc.14 | 48 |
| 0.5-alpha.1 | 49 |
| 0.5-alpha.2 | 50 |
| 0.5-alpha.3 | 51 |
| 0.5-alpha.4 | 52 |
| 0.5-alpha.5 | 53 |

`versionName` 自 0.4-rc.4 起采 `0.4-rc.N` 形式（取代原先的 `0.4.N` 递增）；0.4.4 从未出包，所以接在 0.4.3 后面的是 `0.4-rc.4` / code 38，而不是 39。0.4-rc.14 之后进入 `0.5-alpha.N` 系列，首版为 `0.5-alpha.1` / code 49；`0.5-alpha.2` / code 50 为「研究」入口与聚类分析；`0.5-alpha.3` / code 51 为聚类社群偵测、三维可视化与同源词第三轮；`0.5-alpha.4` / code 52 为同源词第四轮、相似度收缩、检查更新、护眼模式与聚类全屏页；`0.5-alpha.5` / code 53 为聚类候选池＋自动分类数／最小义项数、摘要精简与地图展示页。下一个对外版本用 `0.5-alpha.6` / code 54。

**版本号是唯一把新资料推给既有使用者的机制。** `isOutdated()` 拿 `versionName` 字符串与 SharedPreferences 里记下的值做**不等比较**（`DatabaseMaintenance.isOutdated()`，实现是 `DatabaseFiles.isOutdated()`，不解析语意化版本），不相等就重拷 assets 里的数据库。所以**只要动了资料，就一定要 bump `versionName`**，否则老使用者永远留在旧资料上。

### 出包

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk "publish/<version>.apk"
```

**暂存一律放 `publish/`** —— macOS 开机时会清空 `/tmp`，放在 `publish/` 的 APK 与数据库备份才能留到下一次发版（`publish/` 已 gitignore，不进仓库）。

**上传用的档名必须是纯 ASCII。** `gh release` 不会报错，但会把非 ASCII 档名吃掉一大截：2026-10-05 用 `publish/湘典-debug-0.4-rc.5.apk` 上传，asset 名称变成 `-debug-0.4-rc.5.apk`，得删掉重传。一律先复制成 `publish/<version>.apk` 再上传。

**对外一律发 debug 签名的 APK。** `app/build.gradle` 的 `release` buildType 没有 `signingConfig`，`assembleRelease` 产出的是**未签名** APK，装不上。

### 回归测试

27 项仪器化测试（数据层 18 + 界面 9），需要模拟器或真机：

```bash
# 无窗口模拟器（本机 AVD：siongtest，API 35 / aosp_atd arm64）
emulator -avd siongtest -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device
./gradlew :app:connectedDebugAndroidTest   # 全绿应显示 27/27
```

- 测试依赖全部走 `androidTestImplementation` / `testImplementation`，**不进对外发布的 APK**；`assembleDebug` 不跑测试。
- 另有 89 项 JVM 单元测试（声调格式化、变体展开、检索装配/竞态、方言筛选树、义项相似度、层次聚类、社群偵測、候选分區搜索 7、三维布局、分群锚点布局、布局补间、三维投影、聚类引擎、Mercator 投影 8（含两轴同单位、不同纬度不塌陷、等比缩放的投影回归锁）、泰森多邊形 6、經緯度 3、调色盘与方言树、版本比较与更新解析）：`./gradlew :app:testDebugUnitTest`，不需要模拟器，几秒完成。
- 断言以**不变量**为主（有序、数量相等、往返一致、重置前后一致），只有少数用例引用具体数据（長沙 的声调与分区、`搜同源("彎腰")` 有结果）。上游资料更新后若测试失败，先核对断言是否仍成立，不要直接改测试。
- 本次（0.4-rc.11 之后）新增的两项针对并发：`rapidResearchesLastWins`（慢查询不得覆盖新结果）、`resetDuringSearchDoesNotCrash`（检索中重置不崩、重置后可继续查）。
- 本次（0.5-alpha.2）新增 3 项仪器化测试（`dialectProfilesLoadFromInfo` 方言画像、`cognateMembershipsMatchDatabase` 同源词成员、`researchOpensClusterAnalysis` 研究入口）与 14 项 JVM 测试（相似度 3、层次聚类 3、布局 1、聚类引擎 2、调色盘 2、ViewModel 聚类 3）。
- `mapProjectionKeepsGeographicProportion` 用真实 491 点 + 真实底图跑 `fitTransform(1074×1000, padding 12)`，锁地图投影单位：内容高宽比 = 两轴弧度跨度比（误差 < 1%）、`contentHeight > 500 px`（修正前约 12.75 px）、投影点不共线（y 标准差 > contentHeight 的 10%、不同 y 值 > 100），Voronoi 单元格全部落在 `contentBounds()` 内且数量等于站点数。
- `cognateInventoryMatchesRelease` 刻意写死本版的同源词库库存（组数、条数、新增组、调类范围），资料更新时**必须同步更新断言**，不要当普通不变量测试。
- `clusterAnalysisUsesCommunityDetection` 用真实库跑两遍聚类（门槛 1 与默认门槛）：断言参与点数、自动类数 ≥3、`optimalK`／`optimalSilhouette` 与自动分区一致、**轮廓系数 > 0.10 且未标稀疏**、模组度落在 −0.5..1、**每个簇都不小于 `minClusterSize(n)`**、候选池按 k 升序且轮廓系数在 −1..1、每个点都带底图范围内的经纬度（100.2–119.7／21.5–33.5）、默认门槛为 4 且默认门槛下是 k=2（这是「可接受候选中轮廓最高」的正常结果，不是退化）、`groupedLayout3D` 类内平均距离小于类间质心距离。真实 k／轮廓系数／Q／类别规模／完整候选表／默认门槛结果全部用 `Log.i("ClusterTest", …)` 打印，跑完用 `adb logcat -d -s ClusterTest` 核对（2026-11-20 实测：门槛 1 → k=6、轮廓 0.1587；默认门槛 4 → k=2、轮廓 0.1972）。
- 启动时会自动检查更新（`data/UpdateChecker.kt`，见 ADR-0009）：GET GitHub `releases/latest`，8 秒超时；失败静默、不阻断启动、不自动下载安装；结果缓存到 SharedPreferences，有新版本时「關於」图标旋转并在面板提示，点按打开 Release 页。手动「檢查更新」走同一路径。
- `AndroidManifest` 的 `INTERNET` 权限**只**用于上述更新检查，其余功能仍全程离线。

### 发 Release

```bash
# 0) 清掉上一版留下的数据库备份（回滚点用到下一次发版为止）
ls -la publish/*.db.bak-*   # 先看一眼再删
rm -f publish/*.db.bak-*

git tag <version>                    # lightweight tag（非 annotated）
git push origin master <version>
gh release create <version> "publish/<version>.apk" \
  --repo xiang9916/SiongDict \
  --title "<version>" \
  --notes-file <notes> \
  --target master --verify-tag
```

- Release `name` 与 tag 同名；asset 名为 `<version>.apk`（不带 `湘典-debug-` 前缀）；`prerelease=false`。
- notes 取 CHANGELOG 顶部那一条，去掉 Markdown 粗体等标记，改写成纯文本逐条列出。
- release 列表按时间排序，最新的一条自动成为 **Latest**。

### 签名陷阱

debug keystore 是**每台机器各自生成**的（`~/.android/debug.keystore`）。换一台机器出包，签名指纹就变了，已安装旧版的装置升级时会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，升级前先卸载旧版。跨机器发版时，release notes 里要明写这条提醒。

## 7. 已知陷阱

1. **`.gitignore` 的 `*.db` 会让 `git add app/src/main/assets/databases/` 静默跳过数据库**，不报错也不提示。真要加得 `git add -f`（本项目政策是只留在本机，见 §1、§3）。
2. **`README.md` / `CHANGELOG.md` 各有两份，必须逐字节一致。** App 的「说明」页读的是 `app/src/main/assets/` 里那一份（`ui/InfoDialog.kt` 的 `InfoDialog()`），只改仓库根目录那份，会出现「仓库写的」和「App 显示的」不一致。改完自查：
   ```bash
   md5 README.md app/src/main/assets/README.md
   md5 CHANGELOG.md app/src/main/assets/CHANGELOG.md
   ```
3. 资料更新后别忘了 `README.md` 里的三个数字（方言点数、同音字组数、读音条数）。
4. `tools/tables/` 不存在于仓库中，任何指向 `tools/tables/...` 的文档路径都只是**上游 MCPDict 的相对位置**，不是本仓库的文件。
5. **方言筛选走分区树，不再按「方言岛」分单组开关。** 右上「方言筛选」对话框显示音典一级／二级／三级分区树（全部展开、多选、父节点三态）；默认不筛选（全部显示）。方言岛（闽／岭东／岭南）在各自分区叶子下勾选即显示；`SearchViewModel.isDialectVisible()` 以 `leafKey()`（前三段分区路径）是否在 `selected` 集合判断。改筛选会即时重跑当前检索。
6. Gradle 开了 `org.gradle.configuration-cache=true`；AGP 8.7.3 与 Kotlin 2.0.21 是硬绑定，`org.jetbrains.kotlin.android` 与 `org.jetbrains.kotlin.plugin.compose` 两个 plugin 版本必须保持一致。
7. 数据库备份档 `*.db.bak-YYYYMMDD-HHMMSS` 由管线自动产生，留在 `app/src/main/assets/databases/` 会被一起打进 APK——App 根本不读它们。已在 `.gitignore` 加 `*.db.bak-*`，仍请在出包前确认该目录只剩两个 `.db`。
8. **2026-10-04 重写过一次 git 历史**（`git-filter-repo`），剔除非代码 blob：`--strip-blobs-bigger-than 1M`，加 `*.tsv`／`*.geojson`／`*.ttf`／`*.otf`／`*.apk`／`*.db`／`*.csv`／`*.xlsx`／`*.zip`／`*.DS_Store` 等 glob，加历史遗留目录 `tools/tables`、`方言.geojson`、`bin`、`font`、`cgi`、`libs`、`gen`、`.settings`、`.kotlin`、`assets/databases`、`app/src/main/assets/maps`、`app/src/main/res/font`。结果：`.git` 由 **882 MB 降至 9 MB**，提交数 1672 → 740（被剪掉的是只动数据、重写后变空的提交，代码提交全在），19 个 tag 全部重写并强推。**重写后 `HEAD^{tree}` 与重写前逐位元组相同**（`4b7b0c941040efab83d031611b88a890720222f5`），代码内容零改动；全新 clone 可以正常 `assembleDebug` 并打出带数据库的 APK。三条后果要记住：
   - 旧 clone 的历史已与远端分岔，**从新 clone 推送**；
   - 别再往仓库提交大档，否则又要重写一次历史（`git-filter-repo` 需另装，`pip3 install git-filter-repo`）；
   - GitHub Release 挂在 tag 名称上，重写 tag 不影响 Release 本体（0.4-rc.4 的 APK 仍在，未受影响）。

   顺带：这次重写也把历史上的 `.kotlin/errors/errors-*.log`（含建置机家目录路径）一并抹掉了。

9. **lint 目前 0 errors / 24 warnings，全部属于「已知可接受」**：7 条 GradleDependency（3 条应用依赖 + 4 条测试依赖/BOM 的版本提示，版本有意固定）、10 条启动图标形状、5 条 round 图标与方图标内容重复、2 条自适应图标缺 monochrome。发版不阻塞；要清掉它们需单独评估图标品牌与依赖升级，不要顺手改。
10. **本 App 唯一的联网用途是「檢查更新」。** 启动时与「關於」面板手动点按时会 GET `https://api.github.com/repos/xiang9916/SiongDict/releases/latest`（8 秒超时、失败静默、不自动下载安装，见 ADR-0009）；`INTERNET` 权限只为此存在。除此之外全部离线，不要为其他功能加联网。
11. **`app/src/main/assets/maps/provinces.json` 是派生产物，不要提交上游原件。** 它由 `tools/build_map.py` 从 MCPDict 的 `china.json` 裁切简化而来（bbox 100.2–119.7／21.5–33.5、容差 0.005°）；上游 `china.json`（约 928 KB）与任何原始 geojson 一律只留本机（§1）。要改底图就改脚本重跑，不要手改 JSON。地图页全程离线：别加线上图砖、第三方地图库或任何联网请求（唯一联网用途见下一条）。

---

*本文件由维护者与 AI 协作维护。改动项目结构、管线或发版流程时，请一并更新这里。*
