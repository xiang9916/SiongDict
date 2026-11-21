# 更新检查：启动时静默查 GitHub Release

**状态**：已采纳（2026-11-20）
**相关**：ADR-0005、ADR-0008、`AGENTS.md` §6／§7.10、`data/UpdateChecker.kt`、`data/UpdateStatus.kt`、`data/DisplayPreferences.kt`、`ui/InfoDialog.kt`

## 背景

App 是离线工具，但使用者装的是从 GitHub Release 下载的 APK；上游（音典、同源词库、算法）更新后，旧版不会自己知道。既没有应用商店，也没有自建服务端，唯一可用的「最新版本」事实源就是仓库的 GitHub Release。

需求有两层：① 启动时安静地知道「有没有新版」，有新版本时给一个不打断使用的提示；② 「關於」面板里能手动查一次并看到结果。约束是：不能因为查更新而拖慢启动、不能因断网而报错弹窗、不能自动下载或安装（安全与体积都由使用者自己决定），也不为它引入第三方依赖。

## 决定

### 1. 触发时机

- 启动：`MainActivity` 在首帧初始化（`DisplayPreferences.init`／`UpdateStatus.init`）之后，用 `lifecycleScope.launch` 在后台触发 `UpdateStatus.refresh(...)`，不阻塞首帧、不等待结果。
- 手动：「關於」面板顶部的更新行（`ui/InfoDialog.kt`，testTag `info_update_row`）点按触发同一路径；面板打开时显示上次缓存的状态。
- 结果缓存在 `SharedPreferences`（键 `AppPrefs.KEY_UPDATE_LATEST = "update_latest"`），进程重启后仍能立即显示「有新版本」，不必每次打开面板都联网。

### 2. 请求

- 端点：`GET https://api.github.com/repos/xiang9916/SiongDict/releases/latest`（常量 `UpdateChecker.LATEST_RELEASE_URL`）。
- 请求头：`Accept: application/vnd.github+json`；`User-Agent: SiongDict/<versionName>`（GitHub API 要求 UA，版号用 `AppInfo.versionName`）。
- 超时：连接与读取合计 **8 秒**（`TIMEOUT_MS = 8_000`）；只用 `HttpURLConnection`，**不引入 OkHttp／Retrofit 等依赖**。
- 响应只取 `tag_name`：`parseLatestTag()` 用正则 `"tag_name"\s*:\s*"([^"]+)"` 提取，缺失或畸形返回 `null`，**不做完整 JSON 解析**（响应体积与字段都极小，正则足够且可 JVM 单测）。

### 3. 版本比较

`compareVersions(a, b)` 不解析语意化版本库，按本项目既定格式实现：先按 `.` 逐段比较数字段（数值比较，`alpha.10` > `alpha.3`），数字相同再比较 `-` 之后的预发布后缀（逐段比较，数字段数值比较、字母段字典序）；**同一数字段下，无后缀（正式版）大于有任何预发布后缀的版本**。已覆盖：

- `0.4-rc.14 < 0.5-alpha.1`
- `0.5-alpha.2 < 0.5-alpha.3`
- `0.5-alpha.3 == 0.5-alpha.3`
- `0.5-alpha.3 < 0.5-alpha.10`
- `0.5-alpha.3 < 0.5`（预发布后缀 < 同号正式版）

### 4. 结果与展示

- 状态由 `UpdateResult(latestTag, newer, error)` 与 `UpdateStatus` 承载：`checking`（檢查中…）、失败（`檢查失敗：…（點按重試）`）、有新版本（`有新版本 <tag>（點按查看）`）、已是最新（`已是最新版本`）。
- 有新版本时只有两处可见提示：① 顶栏「關於」图标原地旋转（0°→180° 停在「!」形状、再转到 360° 回到「i」，`infiniteRepeatable` ＋ `keyframes`，仅 `hasUpdate` 为真时启动）；② 「關於」面板的更新行显示版本号。**不弹窗、不打断检索**。
- 点按更新行打开 `https://github.com/xiang9916/SiongDict/releases/tag/<tag>`（GitHub Release 页），**不自动下载、不自动安装**，由使用者自行决定。

### 5. 失败与离线保证

- 任何异常（断网、DNS 失败、超时、非 200、解析失败）都收敛成 `UpdateResult` 的 `error` 字段，只影响更新行文案；**不抛到界面、不阻断启动与检索**。
- 检查纯属增量提示：没有网络时 App 与之前完全一致；更新行显示「檢查失敗」，点按可重试。

### 6. 权限与隐私

- `AndroidManifest.xml` 只加 `android.permission.INTERNET`（清单内注释写明用途）。
- 请求只带 `User-Agent` 与非敏感头，不发送任何本机资料、不同步数据库、不上报使用行为。
- 这是 **App 唯一的联网行为**（启动自动 ＋ 手动点按）；除此之外所有功能离线（见 `AGENTS.md` §7.10）。

## 考虑过的方案

- **不检查更新**：使用者只能自己回来 GitHub 看版本，旧版会长期停留在旧资料上（数据库只在 `versionName` 变化时重拷，见 `AGENTS.md` §6）。否决。
- **用 GitHub API 的 `releases` 列表**：响应更大、还要处理预发布过滤；`releases/latest` 直接给最新正式 Release，字段最少。否决列表方案。
- **引入 OkHttp／Retrofit／JSON 库**：为一个 GET 和一个字段引入依赖，增加体积与攻击面，收益为零。否决。
- **启动时同步阻塞检查**：会让冷启动受网络影响（最坏 8 秒），与「离线优先」冲突。否决，改用后台 `lifecycleScope`。
- **自动下载并安装 APK**：需要 `REQUEST_INSTALL_PACKAGES`、自签 APK 的信任问题、断点续传与校验，风险远大于收益。否决，只给 Release 页链接。

## 后果与限制

- 启动会发一次 HTTPS 请求（8 秒超时上限）；无网时不重试、不报错。
- GitHub API 有匿名速率限制（60 次/小时/IP）；启动 ＋ 手动点按的频率远低于此，超限时归为「檢查失敗」，下个版本再试即可。
- 缓存只有一个 `tag_name` 字符串；使用者点按「檢查更新」会刷新它。不发通知、不做后台周期检查（无 WorkManager）。
- 版本比较是自订规则，不保证与语义化版本完全一致；本项目的 tag 命名（`0.4-rc.N`、`0.5-alpha.N`）都在规则覆盖内，新增 tag 风格时要同步扩测试。

## 实施

- 代码：`app/src/main/java/org/siongdict/app/data/UpdateChecker.kt`（端点常量、`check()`、`parseLatestTag()`、`compareVersions()`、`UpdateResult`）、`data/UpdateStatus.kt`（缓存键、`init()`、`hasUpdate()`、`refresh()`）、`data/DisplayPreferences.kt`（简体渲染与护眼模式，与更新行同属「關於」面板白底改造，不联网）。
- 界面：`ui/InfoDialog.kt` 顶部「更新」行（testTag `info_update_row`）＋ 护眼开关（testTag `eye_care_switch`）；`ui/SearchScreen.kt` 的「關於」图标旋转；`MainActivity` 启动触发。
- 权限：`app/src/main/AndroidManifest.xml` 的 `INTERNET`。
- 测试：JVM `data/VersionComparatorTest`（版本比较 2 项）与 `data/UpdateCheckerTest`（`parseLatestTag` 解析 2 项），只测纯函数、**绝不联网**；仪器化 `UiSmokeTest` 只断言更新行存在与可点，不对「已是最新／有新版本」这类依赖网络的结果断言（模拟器可能无网或限流）。
- 文档：`AGENTS.md` §0／§2／§6／§7.10、`README.md`（两份）的离线说明改述。
- 发布：0.5-alpha.4 / versionCode 52（2026-11-20）上线，为 App 首次引入联网检查更新。
