# AGENTS.md — 湘典 SiongDict 開發須知

> 給在此倉庫工作的 AI／開發者。**2026-10-08 匯總**，程式碼對應 `0.4-rc.6`。
>
> ⚠️ **本文件在倉庫內、會隨倉庫一起公開** —— 所以這裡只寫可公開的事實：不含建置機絕對路徑（`/Users/<使用者名>/…`）、
> 不含任何簽章憑證或其口令、不含未公開的個人資料。動程式碼、資料或發版之前先讀一遍。

## 0. 現狀速覽

- 原生 Android App（Kotlin + Jetpack Compose + Material 3），離線 SQLite FTS5 全文檢索，無需聯網。
- 當前版本 **0.4-rc.6**：`versionCode 40`、`versionName "0.4-rc.6"`，只存在於 `app/build.gradle`。
- 線上倉庫 `xiang9916/SiongDict`；`master` 是唯一分支，**沒有** `develop`、也**沒有** `.github/workflows`（無 CI）。
- 資料規模：**480 個方言點**、**596,822 條讀音**、122,853 組同音字；同源詞 **142 組 / 1,930 條**（全數人工標註，無自動條目）。
- git 倉庫根 = 本文件所在目錄；父目錄不是倉庫，勿在父目錄跑 git 命令。
- `.git` 約 **9 MB**：2026-10-04 重寫過一次歷史，把非程式碼大檔全部剔除（見 §7.8）。**該日之前的 clone 已與遠端分岔，請重新 clone，勿從舊副本推送。**
- 全部讀音資料來自 [漢字音典 MCPDict](https://github.com/osfans/MCPDict)。本專案只做三件事：篩選湘語相關方言點、重建檢索索引、人工標註同源詞。

## 1. 🔴 紅線：這些東西不進倉庫

| 類別 | 說明 |
| --- | --- |
| 建置機絕對路徑 | `/Users/<使用者名>/…`、`/home/<使用者名>/…` 不得出現在任何被跟蹤檔案中，**日誌與例外堆疊也算**。 |
| 簽章憑證 | `*.keystore`、`*.jks`、`keystore.properties`、`signingkey.jks` 全在 `.gitignore`。本倉庫**不含任何簽章檔**，也不要 `git add -f` 進去。 |
| 資料庫二進位 | `*.db` 與 `*.db.bak-*` 在 `.gitignore`（理由見 §3）。V0.4.3 曾誤入 5 個 `cognates.db.bak-*`，還被打進 APK；已於 V0.4-rc.4 清除。 |
| 大型中間資料 | `tools/tables/`（MCPDict 上游字表，單檔最大 47 MB）、`app/src/main/assets/maps/`、字型 `*.ttf/*.otf`、`*.geojson`、`*.tsv`、`*.apk`、舊 `assets/databases/` 都曾進過歷史，**不要再提交**（否則 `.git` 又會暴漲，得再重寫一次歷史，見 §7.8）。 |
| Release notes | 它掛在 GitHub 上、不在倉庫裡，但同樣按上表自查。 |

發版前自查（除 `.gitignore` 自身列舉關鍵字屬預期命中外，其餘應零命中）：

```bash
cd SiongDict
git grep -nI -E '/(Users|home)/[A-Za-z0-9_.-]+' -- . ':!.gitignore'
git grep -nI -E 'password|passwd|secret|api[_-]?key|BEGIN [A-Z ]*PRIVATE KEY|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}' -- . ':!.gitignore'
git ls-files | grep -E '\.(keystore|jks)$|keystore\.properties'   # 應無輸出
```

`.kotlin/errors/*.log` 是 Kotlin daemon 崩潰日誌，會把機器的家目錄路徑寫進去（歷史上的 `errors-*.log` 就是這樣混進倉庫的），
`.gitignore` 已加 `.kotlin/`；本機重跑建置後若又生成，不要提交。

## 2. 目錄結構

```
app/src/main/java/org/siongdict/app/
  MainActivity.kt            入口
  data/DictDatabase.kt       assets → 私有目錄複製、FTS5 查詢、變體表載入、版本比對
  data/Models.kt             資料模型
  data/ToneFormatter.kt      聲調格式化
  ui/SearchScreen.kt         全部 UI（搜字音／搜同源／搜釋義；說明頁直接讀 assets 的 README.md、CHANGELOG.md）
  ui/SearchViewModel.kt      檢索、分組、方言篩選
  ui/CognateExport.kt        同源詞 CSV 匯出
app/src/main/assets/
  databases/siongdict.db    ★ 不入庫，必須自行重建（§3、§4）
  databases/cognates.db     ★ 不入庫，必須自行重建；缺失時 App 不會崩，「搜同源」自動停用
  variants.json             異體字對映，**已入庫**（由 tools/build_variants.py 產生；繁體、簡體、異體三種寫法互查）
  t2s.json                  繁→簡對映，**已入庫**（由 tools/build_simplifier.py 產生；含本地「著」保護詞表，見該腳本與 docs/adr/0003）
  README.md / CHANGELOG.md  **已入庫**；App 內「說明」頁讀的就是這兩份，必須與倉庫根目錄的同名檔案逐字節一致
tools/                       資料管線（純 Python 3 標準庫，無第三方依賴、無 requirements.txt）
  build_db.py                MCPDict 字表 → siongdict.db
  build_variants.py          正字.tsv + OpenCC 字表 → variants.json（繁簡異體混搜）
  build_simplifier.py        OpenCC TSPhrases/TSCharacters → t2s.json（簡體渲染）
  cognate_pipeline.py        橋字驗證 → cognates.db
  ipa_parser.py              IPA 聲母／韻母／聲調切分，被 cognate_pipeline.py 匯入
publish/                     本機出包暫存，已 gitignore
```

## 3. 資料庫不入庫 —— fork 之後必須自己重建

這是本專案最容易踩的坑：**clone 下來直接 `./gradlew assembleDebug` 會 BUILD SUCCESSFUL，但裝上去一開就崩。**

原因有兩層：

1. `app/src/main/assets/databases/` 整個目錄不在倉庫裡 —— `.gitignore:10` 的 `*.db` 把兩個資料庫擋掉了；
2. 建置期不校驗 assets 是否存在。崩潰發生在執行期：`DictDatabase.copyAssetDatabase()` 找不到 `databases/siongdict.db` 會直接拋例外（`DictDatabase.kt:52-60`）。

兩個檔案的性質不同：

| 檔案 | 大小 | 缺失後果 |
| --- | --- | --- |
| `siongdict.db` | 約 54 MB | **必需**。App 啟動即崩。含 `langs`（讀音，FTS5）與 `info`（方言點後設資料）兩張虛擬表。 |
| `cognates.db` | 約 252 KB | **可選**。只記一條 warn，App 正常跑，「搜同源」停用（`DictDatabase.kt:67-74`）。含 `cognates` 與 `cognate_groups`。 |

不把 `.db` 納入版本控制是刻意的：`siongdict.db` 每次音典更新都會整體變動，一旦入庫，**每次資料更新都會在 git 歷史裡永久留下一個 54 MB 的 blob**，倉庫很快就會被撐爆。請走 §4 的重建路線，或直接取用 GitHub Release 的 APK。

## 4. 從 fork 到能跑：最短路徑

### 前置

- **JDK 17**（`app/build.gradle` 的 `sourceCompatibility` / `targetCompatibility` / `jvmTarget` 都是 17）
- **Android SDK**：`platforms/android-35` + `build-tools/35.0.0`（`compileSdk 35` / `targetSdk 35` / `minSdk 24`）
- **Python 3**（管線只用標準庫）
- Gradle 由 wrapper 自動下載（8.11.1），不必預裝

### 步驟

```bash
# 1) 指定 SDK 路徑（local.properties 已 gitignore，必須自建）
cd SiongDict
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 取得 MCPDict 上游字表。只 sparse 出需要的三塊——完整 clone 是 GB 級
cd ..
git clone --filter=blob:none --sparse https://github.com/osfans/MCPDict.git MCPDict-master
cd MCPDict-master
git sparse-checkout set tools/tables/output tools/tables/data/正字.tsv app/src/main/assets/opencc
cd ../SiongDict
# ⚠️ MCPDict-master 必須與 SiongDict 平級：tools/*.py 的預設路徑是 ../../MCPDict-master/...

# 3) 重建資料（腳本的預設輸出路徑就是 assets 下的正確位置）
python3 tools/build_db.py          # 讀 ../MCPDict-master/tools/tables/output/_詳情.json 與 <簡稱>.tsv
python3 tools/build_variants.py    # 讀 正字.tsv + opencc/{ST,TS}Characters、{HK,TW}Variants
python3 tools/build_simplifier.py  # 讀 opencc/TSPhrases.txt 與 opencc/TSCharacters.txt
python3 tools/cognate_pipeline.py  # 讀 siongdict.db，輸出 cognates.db

# 4) 出包
./gradlew assembleDebug
# 產物：app/build/outputs/apk/debug/app-debug.apk
```

### 幾個必須知道的限制

- **只有 `build_db.py` 支援 `--mcpdict-dir`**。`build_variants.py` 的上游路徑**寫死在原始碼常數裡**，MCPDict 放在別處時要嘛改常數，要嘛做個同名軟連結：`ln -s /path/to/MCPDict-master ../MCPDict-master`。
- `build_db.py` 依賴 `output/_詳情.json`；只 sparse 出各方言點 TSV 是跑不起來的。
- 資料庫都是 SQLite FTS5，App 以**唯讀**開啟，不寫入使用者裝置上的資料。

## 5. 資料更新流程（上游 → 本專案）

```bash
# 1) 更新 MCPDict（sparse clone 體積小，直接 fetch 即可）
cd MCPDict-master && git fetch && git checkout <新的 commit> && cd ../SiongDict

# 2) 重跑 §4 的四支腳本

# 3) 同步文件數字
#    README.md 的「480 個方言點、122,853 組同音字、596,822 條讀音」
#    CHANGELOG.md 新增頂部條目
#    然後把這兩份複製到 app/src/main/assets/（見 §7.2）

# 4) bump versionName / versionCode（見 §6），出包，發 Release
```

**人工同源詞不會被重建吃掉。** `cognate_pipeline.py` 的 Step 0 先把 `cognates` 表裡 `source='manual'` 的記錄整批備份出來，Step 5 再插回並刪掉同 `lang+ipa` 的自動條目（`load_manual_entries` / `insert_manual_entries`）。
但**上游方言點改名／消失時要人工檢查引用**：條目按「字組＋讀音＋方言點」唯一匹配，方言點一改名就會對不上而失效。

`cognate_pipeline.py` 頂部的 `LABEL_MAP` 是自動義類的中文標籤表（tag → 標籤），新增自動義類時要補一條。

旁邊的 `SUPPRESSED_SEMANTIC_TAGS` 是**停用清單**：清單裡的 tag 不再自動生成義類組，目前只含 `HIDE`。2026-10-05 撤下了 23 個 `Hide_*` 自動組（當時寫作 `HIDE_*`，標籤同為「躲藏/捉迷藏」，62 條），但 pipeline 每次重建都會把它們重新算出來——**只刪資料庫裡的行是刪不掉的，必須在這裡擋**。反過來，要恢復某個自動義類，把它從這個集合裡拿掉即可。這裡的鍵是 `SEMANTIC_RULES` 吐出的 tag（全大寫），不是組名。

**組名與義項的寫法（2026-10-08 統一，英文一律大駝峰）：**

- `cognate_group` = `<義項>_<音類>`：`Press_gin6`、`Hide_dzɔŋ2`、`Segment_CutOpen_ʂak7`；
- `semantic_tag` = **義項**，即組名去掉末尾那段音類：`Press`、`Hide`、`Segment_CutOpen`。**一個義項下掛多個音類組是正確結構**——`Hide` 掛 8 組、`Press` 4 組、`Attach`/`Curse`/`Fuck` 各 4 組；
- 駝峰只作用於**純 A–Z 字母**段；含音標或數字的段原樣保留（`DJNbo2_bo2` 這類音類組不動），多詞複合義項按語義切詞（`GETWETINTHERAIN_dʐai6` → `GetWetInTheRain_dʐai6`）；
- 組名後綴是**音類標籤**，**不保證等於組內某個成員的今讀**：`Press_gin6`/`gin6`/`ɣan6`/`dzen6` 用的是中古音類名，`Curse_tʂʰak8`（成員今讀是 tsʰu1/tsʰo1/tsʰa6…）、`Segment_CutOpen_ʂak7`（成員是 sa1/sa5/sa7…）、`SetUp_tun5`（成員是 tən5/ten5/tuen5/tin5…）用的是該音類的代表讀音。2026-10-08 審計時 141 組裡有 100 組如此，是正常結構，不要當成錯名去改；
- `cognate_pipeline.py` 的 `pascal_tag()` / `group_prefix()` 就是這條規則的實作，自動分組與人工條目都走它。

維護者另有一套本機技能（同韻母比較判歸音類、橋字驗證、人工編輯同源詞、CSV 匯出）——**不隨倉庫分發**，倉庫內的 `tools/*.py` 才是唯一事實來源。

## 6. 版本號與發版

**`versionCode` 每個對外 tag 嚴格 +1**，不要跳號：

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

`versionName` 自 0.4-rc.4 起採 `0.4-rc.N` 形式（取代原先的 `0.4.N` 遞增）；0.4.4 從未出包，所以接在 0.4.3 後面的是 `0.4-rc.4` / code 38，而不是 39。

**版本號是唯一把新資料推給既有使用者的機制。** `isDatabaseOutdated()` 拿 `versionName` 字串與 SharedPreferences 裡記下的值做**不等比較**（`DictDatabase.kt:115-118`，不解析語意化版本），不相等就重拷 assets 裡的資料庫。所以**只要動了資料，就一定要 bump `versionName`**，否則老使用者永遠留在舊資料上。

### 出包

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk "publish/<version>.apk"
```

**暫存一律放 `publish/`，不要放 `/tmp`** —— macOS 開機時會清空 `/tmp`，放那裡的 APK 與資料庫備份重開機就沒了（`publish/` 已 gitignore，不會進倉庫）。

**上傳用的檔名必須是純 ASCII。** `gh release` 不會報錯，但會把非 ASCII 檔名吃掉一大截：2026-10-05 用 `publish/湘典-debug-0.4-rc.5.apk` 上傳，asset 名稱變成 `-debug-0.4-rc.5.apk`，得刪掉重傳。一律先複製成 `publish/<version>.apk` 再上傳。

**對外一律發 debug 簽章的 APK。** `app/build.gradle` 的 `release` buildType 沒有 `signingConfig`，`assembleRelease` 產出的是**未簽章** APK，裝不上。

### 發 Release

```bash
# 0) 清掉上一版留下的資料庫備份（回滾點用到下一次發版為止）
ls -la publish/*.db.bak-*   # 先看一眼再刪
rm -f publish/*.db.bak-*

git tag <version>                    # lightweight tag，不要 annotated
git push origin master <version>
gh release create <version> "publish/<version>.apk" \
  --repo xiang9916/SiongDict \
  --title "<version>" \
  --notes-file <notes> \
  --target master --verify-tag
```

- Release `name` 與 tag 同名；asset 名為 `<version>.apk`（不帶 `湘典-debug-` 前綴）；`prerelease=false`。
- notes 取 CHANGELOG 頂部那一條，去掉 Markdown 粗體等標記，改寫成純文本逐條列出。
- release 列表按時間排序，最新的一條自動成為 **Latest**。

### 簽章陷阱

debug keystore 是**每台機器各自生成**的（`~/.android/debug.keystore`）。換一台機器出包，簽章指紋就變了，已安裝舊版的裝置升級時會 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，**必須先卸載**。跨機器發版時，release notes 裡要明寫這條提醒。

## 7. 已知陷阱

1. **`.gitignore` 的 `*.db` 會讓 `git add app/src/main/assets/databases/` 靜默跳過資料庫**，不報錯也不提示。真要加得 `git add -f`（本專案政策是不要加，見 §1、§3）。
2. **`README.md` / `CHANGELOG.md` 各有兩份，必須逐字節一致。** App 的「說明」頁讀的是 `app/src/main/assets/` 裡那一份（`SearchScreen.kt:320,326`），只改倉庫根目錄那份，會出現「倉庫寫的」和「App 顯示的」不一致。改完自查：
   ```bash
   md5 README.md app/src/main/assets/README.md
   md5 CHANGELOG.md app/src/main/assets/CHANGELOG.md
   ```
3. 資料更新後別忘了 `README.md` 裡的三個數字（方言點數、同音字組數、讀音條數）。
4. `tools/tables/` 不存在於倉庫中，任何指向 `tools/tables/...` 的文檔路徑都只是**上游 MCPDict 的相對位置**，不是本倉庫的檔案。
5. 方言島（如分區歸「閩」的點）預設不顯示，要在 App 裡勾選對應篩選才會出現——`SearchViewModel.isDialectVisible()` 按「音典分區」前綴分派，`閩`／`嶺東`／`嶺南` 歸在「湘南土話」那一組開關下。
6. Gradle 開了 `org.gradle.configuration-cache=true`；AGP 8.7.3 與 Kotlin 2.0.21 是硬綁定，`org.jetbrains.kotlin.android` 與 `org.jetbrains.kotlin.plugin.compose` 兩個 plugin 版本必須保持一致。
7. 資料庫備份檔 `*.db.bak-YYYYMMDD-HHMMSS` 由管線自動產生，留在 `app/src/main/assets/databases/` 會被一起打進 APK——App 根本不讀它們。已在 `.gitignore` 加 `*.db.bak-*`，仍請在出包前確認該目錄只剩兩個 `.db`。
8. **2026-10-04 重寫過一次 git 歷史**（`git-filter-repo`），剔除非程式碼 blob：`--strip-blobs-bigger-than 1M`，加 `*.tsv`／`*.geojson`／`*.ttf`／`*.otf`／`*.apk`／`*.db`／`*.csv`／`*.xlsx`／`*.zip`／`*.DS_Store` 等 glob，加歷史遺留目錄 `tools/tables`、`方言.geojson`、`bin`、`font`、`cgi`、`libs`、`gen`、`.settings`、`.kotlin`、`assets/databases`、`app/src/main/assets/maps`、`app/src/main/res/font`。結果：`.git` 由 **882 MB 降至 9 MB**，提交數 1672 → 740（被剪掉的是只動數據、重寫後變空的提交，程式碼提交全在），19 個 tag 全部重寫並強推。**重寫後 `HEAD^{tree}` 與重寫前逐位元組相同**（`4b7b0c941040efab83d031611b88a890720222f5`），程式碼內容零改動；全新 clone 可以正常 `assembleDebug` 並打出帶資料庫的 APK。三條後果要記住：
   - 舊 clone 的歷史已與遠端分岔，**不要再從舊副本 push**，重新 clone；
   - 別再往倉庫提交大檔，否則又要重寫一次歷史（`git-filter-repo` 需另裝，`pip3 install git-filter-repo`）；
   - GitHub Release 掛在 tag 名稱上，重寫 tag 不影響 Release 本體（0.4-rc.4 的 APK 仍在，未受影響）。

   順帶：這次重寫也把歷史上的 `.kotlin/errors/errors-*.log`（含建置機家目錄路徑）一併抹掉了。

---

*本文件由維護者與 AI 協作維護。改動專案結構、管線或發版流程時，請一併更新這裡。*
