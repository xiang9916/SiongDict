# 繁简异体混搜：字形表随包分发，不用运行时算法

搜字音／搜释义／搜同源都要求「输入任一种写法都能命中同一批条目」。App 的展开逻辑（`DictDatabase.expandQueryVariants()`）一直都在，缺的是表：`variants.json` 只由 MCPDict 的 `正字.tsv` 生成，而该表把「湿」只连到「溼」，**没有标准繁体「濕」**（`著`、`裡`、`裏` 同样缺失）。结果是搜释义输「濕」只得 47 条（实际 231 条），搜同源输「湿」一组都查不到。

决定：`tools/build_variants.py` 改成多源并查集——`正字.tsv`（异体）＋ OpenCC 的 `STCharacters` / `TSCharacters`（简↔繁）＋ `HKVariants` / `TWVariants`（港台异体），这些表都在 MCPDict clone 内，不引入新依赖。产物是入库资源 `app/src/main/assets/variants.json`：13,574 字 / 6,556 组，单组上限 12 字（防「一简多繁」串成巨团），约 296 KB。

## Considered Options

- **运行时用启发式或引入转换库**：App 要离线，多一个依赖换来的只是同一张表。
- **只修资料、不做表**：治不了根，下一次上游更新或新字照样漏。

## Consequences

fork 的 sparse-checkout 必须多拉一块 `app/src/main/assets/opencc`，否则 `build_variants.py` 直接报错退出（已写进 AGENTS.md §4）。一简多繁（`发`→`發/髮`）会把相关字符串成一组，这对检索是要的；`expandQueryVariants()` 原有的 64 组合上限未动，多字查询若每字都有多变体仍会退回原查询。
