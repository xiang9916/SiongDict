# AGENTS.md — 湘典 SiongDict 开发须知

> 给在此仓库工作的 AI／开发者。**2026-10-18 汇总**，代码对应 `0.4-rc.12`。
>
> ⚠️ **本文件在仓库内、会随仓库一起公开** —— 所以这里只写可公开的事实：不含建置机绝对路径（`/Users/<使用者名>/…`）、
> 不含任何签名凭证或其口令、不含未公开的个人资料。动代码、资料或发版之前先读一遍。

## 0. 现状速览

- 原生 Android App（Kotlin + Jetpack Compose + Material 3），离线 SQLite FTS5 全文检索，无需联网。
- 语言约定：**开发文档一律用简体中文**（本文件、`CONTEXT.md`、`docs/adr/*`、交接文档与技能文档）；**面向使用者的前端内容用繁体中文**（App 界面文案、`README.md`、`CHANGELOG.md`、义类标签）——本项目以汉字研究为主，繁体是显示层标准；开发侧用简体提升效率。
- 当前版本 **0.4-rc.12**：`versionCode 46`、`versionName "0.4-rc.12"`，只存在于 `app/build.gradle`。
- 线上仓库 `xiang9916/SiongDict`；`master` 是唯一分支，**没有** `develop`、也**没有** `.github/workflows`（无 CI）。
- 资料规模：**480 个方言点**、**596,822 条读音**、122,853 组同音字；同源词 **154 组 / 2,222 条**（全数人工标注，无自动条目）。
- git 仓库根 = 本文件所在目录；父目录不是仓库，所有 git 命令都在本目录内执行。
- `.git` 约 **9 MB**：2026-10-04 重写过一次历史，把非代码大档全部剔除（见 §7.8）。**该日之前的 clone 已与远端分岔，请重新 clone 后再推送。**
- 全部读音资料来自 [汉字音典 MCPDict](https://github.com/osfans/MCPDict)。本项目只做三件事：筛选湘语相关方言点、重建检索索引、人工标注同源词。

## 1. 🔴 红线：这些东西不进仓库

| 类别 | 说明 |
| --- | --- |
| 建置机绝对路径 | 被跟踪文件里的路径一律用占位写法（如 `/path/to/android-sdk`），真实建置机路径只出现在本机的未跟踪文件与命令里，**日志与例外堆叠同样按此处理**。 |
| 签名凭证 | `*.keystore`、`*.jks`、`keystore.properties`、`signingkey.jks` 全在 `.gitignore`。本仓库**不含任何签名档**；签名文件只留在本机，由 `.gitignore` 覆盖。 |
| 数据库二进制 | `*.db` 与 `*.db.bak-*` 在 `.gitignore`（理由见 §3）。V0.4.3 曾误入 5 个 `cognates.db.bak-*`，还被打进 APK；已于 V0.4-rc.4 清除。 |
| 大型中间资料 | `tools/tables/`（MCPDict 上游字表，单档最大 47 MB）、`app/src/main/assets/maps/`、字型 `*.ttf/*.otf`、`*.geojson`、`*.tsv`、`*.apk`、旧 `assets/databases/` 都曾进过历史，**保持它们只在本机**（否则 `.git` 又会暴涨，得再重写一次历史，见 §7.8）。 |
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
  data/DictDatabase.kt       assets → 私有目录复制（延迟 + 原子）、只读连接、FTS5 查询、变体表载入、版本比对
  data/Models.kt             资料模型
  data/Simplifier.kt         繁→简的显示层转换（读 assets/t2s.json）
  data/ToneFormatter.kt      声调格式化
  ui/SearchScreen.kt         检索主界面（搜索栏、模式切换、结果列表与右侧跳转条、方言筛选）
  ui/ResultCard.kt           结果卡片（字组／义项标题、方言点读音、同源词展开、复制按钮）
  ui/InfoDialog.kt           「關於」面板（说明页、简体渲染开关、重置資料庫）
  ui/UiText.kt               显示层文本工具（简体渲染、字组标题）
  ui/icons/SiongIcons.kt     内联的两个 Material 图标（替代 material-icons-extended）
  ui/theme/Theme.kt          品牌色与 Material 3 主题
  ui/SearchViewModel.kt      检索、分组、方言筛选
  ui/CognateExport.kt        同源词 CSV 导出
app/src/androidTest/java/org/siongdict/app/
  DataLayerTest.kt           数据层回归（只读连接、异体展开、同源词、声调、forceReset、检索/重置串行化）
  UiSmokeTest.kt             界面回归（首帧、重复字、三模式、方言筛选、关于面板）
app/src/main/assets/
  databases/siongdict.db    ★ 不入库，必须自行重建（§3、§4）
  databases/cognates.db     ★ 不入库，必须自行重建；缺失时 App 不会崩，「搜同源」自动停用
  variants.json             异体字对映，**已入库**（由 tools/build_variants.py 产生；繁体、简体、异体三种写法互查）
  t2s.json                  繁→简对映，**已入库**（由 tools/build_simplifier.py 产生；含本地「着」保护词表，见该脚本与 docs/adr/0003）
  README.md / CHANGELOG.md  **已入库**；App 内「说明」页读的就是这两份，必须与仓库根目录的同名文件逐字节一致
tools/                       资料管线（纯 Python 3 标准库，无第三方依赖、无 requirements.txt）
  build_db.py                MCPDict 字表 → siongdict.db
  build_variants.py          正字.tsv + OpenCC 字表 → variants.json（繁简异体混搜）
  build_simplifier.py        OpenCC TSPhrases/TSCharacters → t2s.json（简体渲染）
  cognate_pipeline.py        桥字验证 → cognates.db
  ipa_parser.py              IPA 声母／韵母／声调切分，被 cognate_pipeline.py 导入
publish/                     本机出包暂存，已 gitignore
```

## 3. 数据库不入库 —— fork 之后必须自己重建

这是本项目最容易踩的坑：**clone 下来直接 `./gradlew assembleDebug` 会 BUILD SUCCESSFUL，但装上去一开就崩。**

原因有两层：

1. `app/src/main/assets/databases/` 整个目录不在仓库里 —— `.gitignore:10` 的 `*.db` 把两个数据库挡掉了；
2. 建置期不校验 assets 是否存在。崩溃发生在执行期：`DictDatabase.copyAssetDatabase()` 找不到 `databases/siongdict.db` 会直接抛例外（见 `data/DictDatabase.kt`）。

两个文件的性质不同：

| 文件 | 大小 | 缺失后果 |
| --- | --- | --- |
| `siongdict.db` | 约 54 MB | **必需**。App 启动即崩。含 `langs`（读音，FTS5）与 `info`（方言点元数据）两张虚拟表。数据库字段名沿用繁体写法（`字組`、`語言`、`讀音`、`註釋`、`排序`），写 SQL 时照抄。 |
| `cognates.db` | 约 252 KB | **可选**。只记一条 warn，App 正常跑，「搜同源」停用（`DictDatabase.copyCognateDatabase()`）。含 `cognates` 与 `cognate_groups`。 |

不把 `.db` 纳入版本控制是刻意的：`siongdict.db` 每次音典更新都会整体变动，一旦入库，**每次资料更新都会在 git 历史里永久留下一个 54 MB 的 blob**，仓库很快就会被撑爆。请走 §4 的重建路线，或直接取用 GitHub Release 的 APK。

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
#    README.md 的「480 个方言点、122,853 组同音字、596,822 条读音」
#    CHANGELOG.md 新增顶部条目
#    然后把这两份复制到 app/src/main/assets/（见 §7.2）

# 4) bump versionName / versionCode（见 §6），出包，发 Release
```

**人工同源词不会被重建吃掉。** `cognate_pipeline.py` 的 Step 0 先把 `cognates` 表里 `source='manual'` 的记录整批备份出来，Step 5 再插回并删掉同 `lang+ipa` 的自动条目（`load_manual_entries` / `insert_manual_entries`）。
但**上游方言点改名／消失时要人工检查引用**：条目按「字组＋读音＋方言点」唯一匹配，方言点一改名就会对不上而失效。

`cognate_pipeline.py` 顶部的 `LABEL_MAP` 是自动义类的中文标签表（tag → 标签），新增自动义类时要补一条。

旁边的 `SUPPRESSED_SEMANTIC_TAGS` 是**停用清单**：清单里的 tag 不再自动生成义类组，目前只含 `HIDE`。2026-10-05 撤下了 23 个 `Hide_*` 自动组（当时写作 `HIDE_*`，标签同为「躲藏/捉迷藏」，62 条），但 pipeline 每次重建都会把它们重新算出来——**只删数据库里的行是删不掉的，必须在这里挡**。反过来，要恢复某个自动义类，把它从这个集合里拿掉即可。这里的键是 `SEMANTIC_RULES` 吐出的 tag（全大写），不是组名。

**组名与义项的写法（2026-10-08 统一，英文一律大驼峰）：**

- `cognate_group` = `<义项>_<音类>`：`Press_gin6`、`Hide_dzɔŋ2`、`Segment_CutOpen_ʂak7`；
- `semantic_label` = **繁体中文**标签（前端显示用，如 `鋸`、`彎腰`）；`semantic_tag` = **义项**，即组名去掉末尾那段音类：`Press`、`Hide`、`Segment_CutOpen`。**一个义项下挂多个音类组是正确结构**——`Hide` 挂 8 组、`Press` 4 组、`Attach`/`Curse`/`Fuck` 各 4 组；
- 驼峰只作用于**纯 A–Z 字母**段；含音标或数字的段原样保留（`DJNbo2_bo2` 这类音类组不动），多词复合义项按语义切词（`GETWETINTHERAIN_dʐai6` → `GetWetInTheRain_dʐai6`）；
- 组名后缀是**音类标签**，**不保证等于组内某个成员的今读**：`Press_gin6`/`gin6`/`ɣan6`/`dzen6` 用的是中古音类名，`Curse_tʂʰak8`（成员今读是 tsʰu1/tsʰo1/tsʰa6…）、`Segment_CutOpen_ʂak7`（成员是 sa1/sa5/sa7…）、`SetUp_tun5`（成员是 tən5/ten5/tuen5/tin5…）用的是该音类的代表读音。2026-10-08 审计时 141 组里有 100 组如此，是正常结构，按既有组名使用即可；
- `cognate_pipeline.py` 的 `pascal_tag()` / `group_prefix()` 就是这条规则的实现，自动分组与人工条目都走它。

**「搜同源」结果的排序（2026-10-08 定案）：** 一律按 `cognate_group`（卡片副标那行英文，`<义项>_<音类>`）**不分大小写的字母序**，与命中相关度、方言点数多寡都无关。实现在 `DictDatabase.searchCognates()` 尾端的 `COGNATE_DISPLAY_ORDER`，改那个比较器就等于改全 App 的显示顺序；组**内**成员仍按 `sort_key`（音典排序）排列，两套顺序各自独立。

**入组硬规则（2026-10-08 定案，见 `docs/adr/0005-entries-must-have-gloss.md`）：** 新增同源词条必须**同时**满足 ——
① 该读音在字音库里 `註釋` 非空（**全库 67% 的行没有注释**，入组候选一律取自注释非空的行）；
② 注释的语义与该组**义项**相符，且经人工确认。
**「音类对得上」只是必要条件，入组理由须是注释语义相符**：同点同韵母里挤着几百个字，`SetUp_tun5` 就出现过
同点同韵母的 `敦 ten1` 注释是「敦促」、与该组的「敦／竖起」不是一个词。本规则**不追溯存量**
（现有 2,222 条成员中 521 条无注释，是当年按文献核定的；2026-10-13 另有一次经维护者逐条核可的例外，见 ADR-0005 附录），只约束新增。

维护者另有一套本机技能（同韵母比较判归音类、桥字验证、人工编辑同源词、CSV 导出）——**不随仓库分发**，仓库内的 `tools/*.py` 才是唯一事实来源。

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

`versionName` 自 0.4-rc.4 起采 `0.4-rc.N` 形式（取代原先的 `0.4.N` 递增）；0.4.4 从未出包，所以接在 0.4.3 后面的是 `0.4-rc.4` / code 38，而不是 39。

**版本号是唯一把新资料推给既有使用者的机制。** `isDatabaseOutdated()` 拿 `versionName` 字符串与 SharedPreferences 里记下的值做**不等比较**（`DictDatabase.isDatabaseOutdated()`，不解析语意化版本），不相等就重拷 assets 里的数据库。所以**只要动了资料，就一定要 bump `versionName`**，否则老使用者永远留在旧资料上。

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

16 项仪器化测试（数据层 10 + 界面 6），需要模拟器或真机：

```bash
# 无窗口模拟器（本机 AVD：siongtest，API 35 / aosp_atd arm64）
emulator -avd siongtest -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device
./gradlew :app:connectedDebugAndroidTest   # 全绿应显示 16/16
```

- 测试依赖全部走 `androidTestImplementation`，**不进对外发布的 APK**；`assembleDebug` 不跑测试。
- 断言以**不变量**为主（有序、数量相等、往返一致、重置前后一致），只有少数用例引用具体数据（長沙 的声调与分区、`搜同源("彎腰")` 有结果）。上游资料更新后若测试失败，先核对断言是否仍成立，不要直接改测试。
- 本次（0.4-rc.11 之后）新增的两项针对并发：`rapidResearchesLastWins`（慢查询不得覆盖新结果）、`resetDuringSearchDoesNotCrash`（检索中重置不崩、重置后可继续查）。

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
5. 方言岛（如分区归「闽」的点）默认不显示，要在 App 里勾选对应筛选才会出现——`SearchViewModel.isDialectVisible()` 按「音典分区」前缀分派，`闽`／`岭东`／`岭南` 归在「湘南土话」那一组开关下。
6. Gradle 开了 `org.gradle.configuration-cache=true`；AGP 8.7.3 与 Kotlin 2.0.21 是硬绑定，`org.jetbrains.kotlin.android` 与 `org.jetbrains.kotlin.plugin.compose` 两个 plugin 版本必须保持一致。
7. 数据库备份档 `*.db.bak-YYYYMMDD-HHMMSS` 由管线自动产生，留在 `app/src/main/assets/databases/` 会被一起打进 APK——App 根本不读它们。已在 `.gitignore` 加 `*.db.bak-*`，仍请在出包前确认该目录只剩两个 `.db`。
8. **2026-10-04 重写过一次 git 历史**（`git-filter-repo`），剔除非代码 blob：`--strip-blobs-bigger-than 1M`，加 `*.tsv`／`*.geojson`／`*.ttf`／`*.otf`／`*.apk`／`*.db`／`*.csv`／`*.xlsx`／`*.zip`／`*.DS_Store` 等 glob，加历史遗留目录 `tools/tables`、`方言.geojson`、`bin`、`font`、`cgi`、`libs`、`gen`、`.settings`、`.kotlin`、`assets/databases`、`app/src/main/assets/maps`、`app/src/main/res/font`。结果：`.git` 由 **882 MB 降至 9 MB**，提交数 1672 → 740（被剪掉的是只动数据、重写后变空的提交，代码提交全在），19 个 tag 全部重写并强推。**重写后 `HEAD^{tree}` 与重写前逐位元组相同**（`4b7b0c941040efab83d031611b88a890720222f5`），代码内容零改动；全新 clone 可以正常 `assembleDebug` 并打出带数据库的 APK。三条后果要记住：
   - 旧 clone 的历史已与远端分岔，**从新 clone 推送**；
   - 别再往仓库提交大档，否则又要重写一次历史（`git-filter-repo` 需另装，`pip3 install git-filter-repo`）；
   - GitHub Release 挂在 tag 名称上，重写 tag 不影响 Release 本体（0.4-rc.4 的 APK 仍在，未受影响）。

   顺带：这次重写也把历史上的 `.kotlin/errors/errors-*.log`（含建置机家目录路径）一并抹掉了。

9. **lint 目前 0 errors / 24 warnings，全部属于「已知可接受」**：7 条 GradleDependency（3 条应用依赖 + 4 条测试依赖/BOM 的版本提示，版本有意固定）、10 条启动图标形状、5 条 round 图标与方图标内容重复、2 条自适应图标缺 monochrome。发版不阻塞；要清掉它们需单独评估图标品牌与依赖升级，不要顺手改。

---

*本文件由维护者与 AI 协作维护。改动项目结构、管线或发版流程时，请一并更新这里。*
