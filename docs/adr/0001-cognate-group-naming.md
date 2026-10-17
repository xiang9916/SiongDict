# 同源词组名与义项的写法：大驼峰，义项可挂多组

2026-10-08 之前，组名混用全大写（`PRESS_gin6`、`Wet_hai7` 并存），`semantic_tag` 有的存整组名、有的存裸义类（`ATTACH`），还出现过指向不存在组名的脏值（`Curse_tʂʰak8` 的 tag 写成了 `CURSE_ʈʰa1`）。

统一为：`cognate_group = <义项>_<音类标签>`，英文段一律大驼峰（`Press_gin6`、`Segment_CutOpen_ʂak7`）；`semantic_tag` 只存义项（`Press`），**一个义项下挂多个音类组是正常结构**（`Hide` 8 组、`Press` 4 组）。只有纯 A–Z 字母段参与转换，含音标或数字的段原样保留（`DJNbo2_bo2` 不动），多词复合义项按语义切词（`GETWETINTHERAIN_dʐai6` → `GetWetInTheRain_dʐai6`）。

## Considered Options

- **保留全大写**：与既有 App 卡片显示一致，但义项与音类标签层次仍然看不出来。
- **一组一义项**：让 `semantic_tag` 就等于组名，省掉一个概念；但「同一义项、不同音类」是这批资料的常态，硬拆会丢掉这层结构。

## Consequences

组名是外部引用契约——改名必须同步 CSV 导出、App 卡片与技能脚本，且**已发布版本的 CHANGELOG 条目保留当时的写法**，改名只作用于之后的版本。实现落在 `tools/cognate_pipeline.py` 的 `pascal_tag()` / `group_prefix()`，自动分组与人工录入都走它。
