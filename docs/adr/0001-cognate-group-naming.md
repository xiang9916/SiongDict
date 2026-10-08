# 同源詞組名與義項的寫法：大駝峰，義項可掛多組

2026-10-08 之前，組名混用全大寫（`PRESS_gin6`、`Wet_hai7` 並存），`semantic_tag` 有的存整組名、有的存裸義類（`ATTACH`），還出現過指向不存在組名的髒值（`Curse_tʂʰak8` 的 tag 寫成了 `CURSE_ʈʰa1`）。

統一為：`cognate_group = <義項>_<音類標籤>`，英文段一律大駝峰（`Press_gin6`、`Segment_CutOpen_ʂak7`）；`semantic_tag` 只存義項（`Press`），**一個義項下掛多個音類組是正常結構**（`Hide` 8 組、`Press` 4 組）。只有純 A–Z 字母段參與轉換，含音標或數字的段原樣保留（`DJNbo2_bo2` 不動），多詞複合義項按語義切詞（`GETWETINTHERAIN_dʐai6` → `GetWetInTheRain_dʐai6`）。

## Considered Options

- **保留全大寫**：與既有 App 卡片顯示一致，但義項與音類標籤層次仍然看不出來。
- **一組一義項**：讓 `semantic_tag` 就等於組名，省掉一個概念；但「同一義項、不同音類」是這批資料的常態，硬拆會丟掉這層結構。

## Consequences

組名是外部引用契約——改名必須同步 CSV 匯出、App 卡片與技能腳本，且**已發佈版本的 CHANGELOG 條目保留當時的寫法**，不要回頭改。實作落在 `tools/cognate_pipeline.py` 的 `pascal_tag()` / `group_prefix()`，自動分組與人工錄入都走它。
