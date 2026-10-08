# 湘典 SiongDict

一款面向湖南及周邊地區方言的漢字讀音查詢應用，基於[漢字音典（MCPDict）](https://github.com/osfans/MCPDict)的字表資料整理而成。

## 特別感謝

特別感謝 周生 luggroo 提供的贊助！

## 一、項目簡介

湘典收錄湖南全省及周邊地區（廣西、江西、湖北、四川、貴州等）的漢字方言讀音，方便語言愛好者、研究者及關心湖南方言的朋友快速查詢與比較各方言點的讀音。

目前資料庫收錄 **480 個方言點**、**122,853 組同音字**、**596,822 條讀音記錄**，覆蓋湘語（長益片、婁邵片、衡州片、辰漵片、永全片）、湘南土話、贛語、西南官話等多種方言類型。

## 二、資料來源

本應用所有讀音資料均來自[漢字音典（MCPDict）](https://github.com/osfans/MCPDict)項目，經由 `tools/build_db.py` 腳本篩選湖南及周邊湘語相關方言點後構建為 SQLite FTS5 全文檢索資料庫。

漢字音典由 @osfans 發起，收錄近三千個語言／方言點的漢字讀音，其字表由眾多方言愛好者共同整理。湘典在此基礎上專注於湖南及周邊地區，向原始項目及所有字表貢獻者致謝。

## 三、收錄範圍

收錄標準以漢字音典的「音典分區」與《中國語言地圖集》第二版分區為依據，篩選規則包括：

1. **音典分區**匹配：湘贛（岳州、北湘、雪峰、羅霄、南湘）、湘南、道州、鄉話等分區
2. **地圖集二分區**匹配：所有湘語片（長益、婁邵、衡州、辰漵、永全）
3. **行政區劃**匹配：湖南省全部方言點，以及廣西資源、全州、興安、灌陽，湖北赤壁、崇陽、嘉魚、通城等毗鄰地區

主要省份分佈：湖南 349 個、廣西 75 個、江西 16 個、四川 13 個、湖北 10 個，另含貴州、陝西、廣東等地少數方言點。

## 四、主要功能

● **搜字音**：輸入漢字檢索，查詢各方言點中的讀音。支持多字搜尋，逐字查詢並按輸入順序依次展示結果；自動聯想繁體、簡體、異體字，各變體分別展示為獨立卡片。

● **搜同源**：輸入中英義項或構擬祖型檢索，搜尋跨方言同源詞組。基於橋字驗證法自動識別聲母、韻母、聲調的規律性對應，將不同方言點中語音對應且語義相同的詞歸入同一同源詞組。

● **搜釋義**：輸入注釋內容匹配檢索，通過釋義關鍵詞反查漢字讀音。

● **渲染為簡體中文 (Beta)**：「關於」面板中的開關，預設關閉。開啟後全部可見文本以簡體顯示（介面文案、註釋、方言名、義類標籤、說明頁與匯出文本），`字組` 與檢索輸入保持原樣、檢索行為不變；開關狀態會保存，`重置資料庫` 不受影響。

搜尋結果以卡片形式展示，包含漢字、方言名稱、IPA 讀音及註釋，並按方言分組排列。

## 五、技術實現

● **平台**：Android（最低支持 Android 7.0 / API 24）

● **語言**：Kotlin + Jetpack Compose + Material 3

● **資料庫**：SQLite FTS5 全文檢索，離線使用，無需聯網

● **構建**：Gradle 8.11 + Android Gradle Plugin 8.7.3 + Kotlin 2.0.21

## 六、構建方法

> 完整的開發者說明——含 fork 後重建資料庫、發版流程與已知陷阱——見倉庫根目錄的 `AGENTS.md`。

**前置需求**：JDK 17、Android SDK（`platforms/android-35`、`build-tools/35.0.0`）、Python 3。

```bash
# 設定 SDK 路徑（local.properties 不入庫，需自行建立）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 構建 Debug APK
./gradlew assembleDebug

# 輸出路徑
# app/build/outputs/apk/debug/app-debug.apk
```

⚠️ **資料庫不在倉庫中**：`app/src/main/assets/databases/` 下的 `siongdict.db` 與 `cognates.db` 被 `.gitignore` 的 `*.db` 排除。缺少 `siongdict.db` 時仍可編譯成功，但 APK 一啟動即崩潰，務必先重建：

```bash
# 取得漢字音典（MCPDict）字表，置於與本倉庫平級的目錄
git clone --filter=blob:none --sparse https://github.com/osfans/MCPDict.git MCPDict-master
cd MCPDict-master && git sparse-checkout set tools/tables/output tools/tables/data/正字.tsv app/src/main/assets/opencc && cd ..

# 重建資料
python3 tools/build_db.py          # siongdict.db（字音資料庫）
python3 tools/build_variants.py    # variants.json（異體字對映）
python3 tools/build_simplifier.py  # t2s.json（繁轉簡對映）
python3 tools/cognate_pipeline.py  # cognates.db（同源詞庫）
```

`build_db.py` 可用 `--mcpdict-dir` 指定字表位置；`build_variants.py` 與 `build_simplifier.py` 的上游路徑寫死於原始碼中，需與上述目錄結構一致。對外發佈的安裝檔一律由 `assembleDebug` 產出（沿用 debug 簽章）。

## 七、免責聲明

1. 本應用所有資料均來自漢字音典項目，字表由 OCR 輔助並經人工校對整理而成，雖已盡力核校，仍難以完全避免錯誤。使用時請自行查閱原始字表或資料出處，不宜直接引用本應用作為唯一依據。

2. 本應用僅為便於快速查閱的工具；凡因未經核實而產生的誤解或錯誤，均與本項目及漢字音典項目無關。

3. 如某方言點的漢字讀音存在問題，歡迎向[漢字音典項目組](https://github.com/osfans/MCPDict/issues)反映。

## 八、致謝

● [漢字音典 MCPDict](https://github.com/osfans/MCPDict)：本應用全部資料來源，由 @osfans 發起，眾多方言愛好者共同建設

● [泛粵大典 Jyutdict](https://github.com/JyutdictEB/Jyutdict-Android)：界面設計參考

● 所有參與湖南及周邊方言字表整理的貢獻者

## 九、參考資料

● [原始道江寧方言](https://zhuanlan.zhihu.com/p/686223099)

● [道江寧方言的語音特徵](https://zhuanlan.zhihu.com/p/695848698)

## 十、授權

本應用採用 MIT 授權，資料庫內容的版權歸漢字音典項目及 respective 字表貢獻者所有。

## 附錄：收錄的方言分區

基於漢字音典「音典分區」，本應用收錄以下分區的方言點：

| 分區 | 説明 |
| --- | --- |
| 湘贛－岳州 | 岳陽、臨湘、陸水等 |
| 湘贛－北湘 | 長沙、益陽、寧鄉、湘鄉、安化、梅山等 |
| 湘贛－南湘 | 衡州、衡山、寶慶、永全、兩祁等 |
| 湘贛－雪峰 | 沅州、漵浦、辰州、靖州等 |
| 湘贛－羅霄 | 羅霄片 |
| 湘南 | 新藍嘉、桂陽州、郴州、通道、湘西南等 |
| 道州 | 道州土話 |
| 鄉話 | 鄉話 |
| 中上江 | 巴陵、常澧、荆益等（湖南境內西南官話） |
| 藍青 | 湘境內官話方言島 |
| 其他 | 嶺東、嶺南等周邊方言點 |

地圖集二分區則涵蓋湘語全部五片（長益、婁邵、衡州、辰漵、永全）及周邊西南官話、平話土話等。
