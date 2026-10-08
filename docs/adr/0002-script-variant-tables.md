# 繁簡異體混搜：字形表隨包分發，不用運行時算法

搜字音／搜釋義／搜同源都要求「輸入任一種寫法都能命中同一批條目」。App 的展開邏輯（`DictDatabase.expandQueryVariants()`）一直都在，缺的是表：`variants.json` 只由 MCPDict 的 `正字.tsv` 生成，而該表把「湿」只連到「溼」，**沒有標準繁體「濕」**（`著`、`裡`、`裏` 同樣缺失）。結果是搜釋義輸「濕」只得 47 條（實際 231 條），搜同源輸「湿」一組都查不到。

決定：`tools/build_variants.py` 改成多源並查集——`正字.tsv`（異體）＋ OpenCC 的 `STCharacters` / `TSCharacters`（簡↔繁）＋ `HKVariants` / `TWVariants`（港台異體），這些表都在 MCPDict clone 內，不引入新依賴。產物是入庫資源 `app/src/main/assets/variants.json`：13,574 字 / 6,556 組，單組上限 12 字（防「一簡多繁」串成巨團），約 296 KB。

## Considered Options

- **運行時用啟發式或引入轉換庫**：App 要離線，多一個依賴換來的只是同一張表。
- **只修資料、不做表**：治不了根，下一次上游更新或新字照樣漏。

## Consequences

fork 的 sparse-checkout 必須多拉一塊 `app/src/main/assets/opencc`，否則 `build_variants.py` 直接報錯退出（已寫進 AGENTS.md §4）。一簡多繁（`发`→`發/髮`）會把相關字串成一組，這對檢索是要的；`expandQueryVariants()` 原有的 64 組合上限未動，多字查詢若每字都有多變體仍會退回原查詢。
