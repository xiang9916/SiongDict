# 数据层按生命周期拆成只读端口，串行化归数据层

2026-10 的架构审查（问题一）发现：旧数据层只有一个访问类（2026-10-24 重构时删除，见 git 历史），它同时承担五类生命周期——
assets → 私有目录的复制与自愈、`versionName` 版本门、两个 SQLite 连接（`siongdict.db` 必需、
`cognates.db` 可选）、FTS 查询、`variants.json` 与 `info` 缓存。公开方法 12 个，
「查询本身不持锁、调用方必须串行」的契约只写在类注释里，由 `SearchViewModel` 的 `Mutex` 兑现。
后果是 `SearchViewModel` 必须直接持有 `Context` 与具体类，检索/分组/筛选只能在模拟器上测；
0.4-rc.12 修的启动供给崩溃与检索竞态也都落在这一个文件里。

## 决定

按**生命周期与失败模式**把数据层拆成三个只读端口，加上一组共享的内部设施：

| 端口 | 职责 | 失败模式 |
| --- | --- | --- |
| `Lexicon` | 字音库（`siongdict.db`）的 FTS 检索、`info` 元数据、繁简异体展开 | 库缺失/损坏 → 自愈；仍失败则抛异常 |
| `CognateBook` | 同源词库（`cognates.db`）的组查询与显示排序 | 库缺失 → 空结果，UI 停用「搜同源」 |
| `DatabaseMaintenance` | 资产供给、版本比对、重置 | 复制失败抛异常（与拆分前一致） |

共享的内部设施（不是端口）：`DatabaseFiles`（文件/连接/版本）、`DbGate`（串行化）、
`VariantIndex` / `AssetVariantIndex`（字形表）、`ToneSystem`（声调值对象）、`AppInfo`（versionName）。

规则：

1. **端口方法一律 `suspend`**（`isOutdated` 只读 SharedPreferences，保持主线程安全），
   实现内部经同一个 `DbGate`（`Mutex` + `Dispatchers.IO`）串行；调用方不再持锁或切线程。
2. **端口不暴露 Android/JSON 类型**：声调 JSON 在 `LocalLexicon` 里解析成 `ToneSystem`，
   `ToneFormatter` 只接受值对象；变体展开是纯逻辑 `VariantIndex`。
3. `reset()` 后 `DatabaseFiles.generation` 自增，`LocalLexicon` 的 `info` 缓存据此失效。
4. `SearchViewModel` 改为构造注入的普通 `ViewModel`，生产装配在 `SearchViewModelFactory`，
   JVM 测试直接注入 fake 端口。

## 考虑过的方案

- **单类只抽接口**：seam 有了，但供给与查询仍在一个实现里，locality 不变；否决。
- **按「字音/同源」拆，但供给留在查询类**：reset 与查询生命周期继续纠缠；否决。
- **保持 ViewModel 持锁**：契约继续泄漏给每个调用方；否决。
- **把声调格式化搬进数据层**：会污染原始 IPA，且违反 ADR-0003「转换只在显示层」；否决。

## 后果

- 检索装配、分组、方言筛选第一次可以在 JVM 上测（`app/src/test`，24 项）；
  设备测试继续覆盖真实 SQL、资产复制与重置（`DataLayerTest` 11 项 + `UiSmokeTest` 6 项）。
- 连接生命周期不变：仍在进程内复用，`ViewModel` 清除时不关闭（本决定不处理该问题）。
- 无 schema／数据／版本号变化；`versionName` 的刷新机制仍是唯一的数据更新开关。
- `cognate_groups` 表仍未被 App 使用（组标签取成员首行）；这是数据契约问题，另案。
- 新增端口或实现时，需同步更新本 ADR 与 `AGENTS.md` §2。

## 实施

2026-10-24，对应 0.4-rc.12 之后的未发版重构；文件见 `app/src/main/java/org/siongdict/app/data/`。
