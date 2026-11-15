# 重构完成度审查报告

- **审查日期**：2026-11-15
- **审查对象**：`refactor/modernize` @ `6d18950`（领先 `main` 114 个提交）
- **功能基线**：`main` / tag `v0.9.18`（重构前最后一个版本，单模块 Kotlin + Fragment + RecyclerView）
- **审查方法**：git 历史逐文件比对 + 全量静态检索 + release 包产物核验；**未使用真实微博账号做端到端验证**
- **结论适用范围**：本文所有"等价/保留"判断均为静态证据，凡依赖服务端行为的结论均标注为"待真机验证"

---

## 一、结论

**原软件功能已完整迁移，未发现功能缺失。** 重构是"行为等价的结构重写"，不是"重做一版"。

| 维度 | 判定 | 关键证据 |
|---|---|---|
| 功能迁移 | ✅ 完整 | 旧版 49 个 Kotlin 源文件全部有对应实现；新版 88 个文件（app 43 / data 28 / domain 11 / core 6）；源码中 **0 处** TODO / FIXME / NotImplementedError / 空实现 |
| 数据格式 | ✅ 零迁移 | prefs 名 `wcm` 与 26 个键名逐一对应；`Account` 20+ 字段逐字段一致；NDJSON 索引路径与 `t/a/p/f/d` 记录字段逐字段一致 |
| UI 文案 | ✅ 严格子集 | 字符串资源 378 → 362，**新增 0 条**，删除 16 条（12 条旧版即死资源），不存在"偷偷新增未验证功能" |
| 行为不变量 | ✅ 保留 | 6 小时自动签到去重、签到不顺带同步面板、抓取单闸门、三层去重、https + 域名白名单、`allowBackup=false` |
| 覆盖安装 | ✅ 可行 | 同 `applicationId`、同签名配置、同 `versionCode`（已递增到 33）；数据零迁移；无新增运行时权限 |
| 架构落地 | ✅ 完成 | 4 模块 + Hilt + 全量 Compose；`domain -> data` 反向依赖已消除（端口在 `:core`、实现在 `:data`、装配在 `:app`），CI 有依赖门禁 |
| 回归验证 | ⚠️ 不完整 | 322 个自动化用例全绿；**签到 / 自动重登 / 浏览博主 / 单条抓取 四条依赖真实登录态的链路从未跑过** |

**能否无缝升级：可以，前提是签名指纹一致（见 §五）。**

---

## 二、功能覆盖度对照

### 2.1 代码归属映射（旧 49 → 新 88）

| 旧文件 | 新版归属 | 关系 |
|---|---|---|
| `WeiboApi.kt`（1114 行） | `WeiboHttp` / `WeiboAuthApi` / `WeiboTopicApi` / `WeiboPostApi` / `WeiboImageUrl` | 拆分 |
| `Crawler.kt`（1681 行） | `CrawlEngine` + `CrawlSaver` + `PostAssets` + `AlbumCrawler` + `TimelineCrawler` | 拆分 |
| `AccountStore.kt`（933 行） | `AccountStore` + `PanelSettings` + `CrawlSettings` + `StorePrefs` + `SafePrefs` | 拆分 |
| `CrawlRunner.kt` | `CrawlService`（object → Hilt `@Singleton` class） | 类化 |
| `HomeFragment.kt` | `HomeTab` + `HomeViewModel` + `HomeHero` + `HomeAccounts` + `HomeCookieCard` + `HomeLogCard` + `HomeTopicsDialog` + `HomeReloginDialog` | 拆分 + Compose 化 |
| `TopicFragment.kt` | `TopicTab` + `TopicScreen` + `TopicViewModel` + `TopicUi` | 同上 |
| `GalleryFragment.kt` | `GalleryTab` + `GalleryScreen` + `GalleryViewModel` + `GalleryUi` | 同上 |
| `SettingsFragment.kt` | `SettingsTab` + `SettingsScreen` + `SettingsViewModel` | 同上 |
| `GalleryBrowseActivity.kt` | `GalleryBrowseScreen` + `GalleryBrowseViewModel` + `BrowseUi`（改为 NavHost route） | Activity → route |
| `BrowsingActivity.kt` | `BrowsingScreen` + `BrowsingViewModel` + `BrowsingUi` | 同上 |
| `PostPickActivity.kt` | `PostPickScreen` + `PostPickViewModel` + `PickUi` | 同上 |
| `ImagePreviewActivity.kt` | `ImagePreviewScreen` + `ImagePreviewViewModel` + `PreviewUi` + `ZoomPager` | 同上 |
| `RemotePreviewActivity.kt` | `RemotePreviewScreen` + `RemotePreviewViewModel` | 同上 |
| `MainActivity.kt` | `MainActivity`（ComponentActivity + NavHost + 底栏） | 壳重写 |
| `LogPanel.kt` / `UiExt.kt` | `HomeLogCard` / 各 `*Ui.kt` | 并入 Compose |
| 其余 33 个（`AppScope`/`AssetName`/`CrawlCursor`/`CrawlPlanner`/`Hashing`/`ReloginPolicy`/`GifEncoder`/`Takeover`/`SafAccess`/`TopicStore`/`GuestSession` 等） | 原文件同名迁入 `:core` / `:data` | 平移，逻辑未改 |

### 2.2 逐条功能与实现位置

| 域 | 功能 | 新版实现 |
|---|---|---|
| 账号 | 多账号表 / 增删改 / 昵称补拉 | `data/AccountStore.kt`、`HomeViewModel.kt` |
| 账号 | WebView 官方登录 + Cookie 判定 | `app/LoginWebViewActivity.kt`（唯一保留 ViewBinding 的页面） |
| 账号 | 账密自动重登（Keystore 加密 + 退避 + 六类失败） | `domain/AutoRelogin.kt`、`data/CredentialVault.kt`、`core/ReloginPolicy.kt`、`data/WeiboPasswordLogin.kt` |
| 签到 | 打开即签（6 小时去重 / 一次进程一次） | `HomeViewModel.kt:173` `PanelSettings.autoOnOpen` + `lastAutoAt` |
| 签到 | 一键签到 / 单账号 / 单超话 / 批量补签 | `domain/CheckinService.kt`、`data/WeiboTopicApi.kt`、`HomeTopicsDialog.kt` |
| 签到 | 签到判定四口径 + `errno=100015` 换 st 重试 | `WeiboTopicApi.sign`、`docs/design/topic-fields.md` |
| 超话 | 列表 / 三列概览 / 等级徽章 / 筛选 / 排序 / 详情懒加载 / 原始 JSON / 复制链接 | `TopicScreen.kt`、`TopicViewModel.kt`、`data/TopicStore.kt` |
| 面板 | Cookie 同步（`POST /api/mobile/sync`）/ 地址校验 / 令牌 | `data/PanelApi.kt`、`PanelSettings.kt`、`PanelUrl.kt` |
| 面板 | 有效期估算 + 临期/失效提示 | `data/CookieLife.kt`、`core/AccountStatusHead.kt`、`HomeCookieCard.kt` |
| 保活 | WorkManager 6h±1h，只探活/重登/推面板，不后台签到 | `app/CheckInWorker.kt` |
| 抓取 | 目标管理 / SAF 目录 / 起始日期 / 相册与时间线双模式 | `data/CrawlSettings.kt`、`GalleryScreen.kt` |
| 抓取 | 相册墙（游客两步握手）+ 18 图补全 + 相册记忆 | `domain/AlbumCrawler.kt`、`data/GuestSession.kt`、`data/WebAjax.kt`、`core/CrawlCursor.kt` |
| 抓取 | 时间线 RETRY/补新/补历史/全量扫描 + 断点续跑 | `domain/TimelineCrawler.kt`、`CrawlPlanner.kt` |
| 抓取 | 三层去重 + 接管已有文件 + NDJSON 索引 | `domain/CrawlSaver.kt`、`core/Hashing.kt`、`core/AssetName.kt`、`data/CrawlIndex.kt`、`data/Takeover.kt` |
| 抓取 | Live Photo → GIF（自研 GIF89a） | `data/GifEncoder.kt`、`data/LivePhotoConverter.kt` |
| 抓取 | 浏览博主 / 选图 / 跨帖批量 / 「已抓」标记 / 远程大图 | `BrowsingScreen.kt`、`PostPickScreen.kt`、`GalleryBrowseScreen.kt`、`RemotePreviewScreen.kt`、`data/CrawlMarks.kt` |
| 图库 | 网格 / 分组 / 筛选 / 搜索 / 5 种排序 / 多选分享删除 / 缩放预览 | `GalleryBrowseScreen.kt`、`ImagePreviewScreen.kt`、`ZoomableImageView.kt` |
| 日志 | 运行日志卡（收起摘要 / 展开终端 / 仅贴底跟随） | `HomeLogCard.kt`、`HomeViewModel.kt` |

---

## 三、重构与原实现的差异

### 3.1 工程结构

| 项 | 原实现 | 重构后 |
|---|---|---|
| 模块 | 单 `:app` | `:app` / `:domain` / `:data` / `:core` |
| 包名 | `com.wcm.checkin` | 四模块**同名包**（刻意，便于零 import 迁移；边界靠 Gradle 强制） |
| 大文件 | `WeiboApi` 1114 行、`Crawler` 1681 行、`AccountStore` 933 行 | 最大文件 `HomeViewModel` 1025 行，其余均 < 770 |
| 资源 | 102 个 | Phase 7 清理掉约 60 个旧布局与 18 个 drawable |

### 3.2 UI 技术栈

| 项 | 原实现 | 重构后 | 影响 |
|---|---|---|---|
| 页面承载 | 4 个常驻 Fragment（add + show/hide） | NavHost 9 条 route（`saveState`/`restoreState`） | Tab 切换仍保状态；子页改为入栈 |
| 列表 | RecyclerView + DiffUtil | `LazyColumn` / `LazyVerticalGrid` | 手写 DiffUtil 消失（旧版曾因此出现主线程 ANR） |
| 布局 | XML + ViewBinding | Compose（仅登录页保留 XML） | — |
| 主题 | `Theme.Wcm` + XML | `WcmTheme`（Material3，读同一套 `colors.xml` 令牌） | 需人工保持两边一致 |
| Tab 切换动效 | 淡入 + 上移 8dp / 180ms，尊重"移除动画" | **未实现**（切换即时） | 纯观感差异，不影响功能 |
| 底栏角标 | `Badge`，`maxCharacterCount=3` | `Badge`，超过 99 显示 `99+` | 已补 |

### 3.3 状态与依赖注入

| 项 | 原实现 | 重构后 |
|---|---|---|
| 长任务 | 绑在 Fragment `viewLifecycle` 上 | `AppScope`（进程级 SupervisorJob） |
| 跨组件状态 | Fragment 字段 + 手写回调 | ViewModel + `StateFlow` / `SharedFlow`，4 个 Tab 的 VM 挂在 Activity 作用域 |
| DI | 无 | Hilt（`@HiltAndroidApp` / `@HiltWorker` / `@HiltViewModel`），仅 1 个 Module |
| 业务单例 | 全部 `object` | 八个编排服务改为构造注入的 class（`CheckinService` / `AccountMaintenanceService` / `AutoRelogin` / `CrawlEngine` / `CrawlSaver` / `TimelineCrawler` / `AlbumCrawler` / `CrawlService`）；存储层仍是 `object` + 端口适配器 |

### 3.4 构建

| 项 | 原实现 | 重构后 |
|---|---|---|
| R8 / 资源收缩 | `isMinifyEnabled = false` | 开启，APK 6.2 MB → **3.5 MB** |
| 混淆 | 无 | 有（`HomeViewModel -> n6.c8`） |

### 3.5 经核查**不成立**的三处"疑似差异"

审查过程中有三处一度怀疑功能缺失，逐一核实后确认与旧版行为一致，记录在此以免重复排查：

1. **SAF 授权失效只显示"重新选择"**：旧版 `GalleryFragment.kt:258` 同样用的是 `crawl_saf_lost_action`；`crawl_saf_lost`（"目录授权已失效…"）在旧版就是**未被引用的死字符串**，删除是正确的。
2. **九宫格 `+N` 溢出标记**：已实现 —— `BrowsingScreen.kt:331` 用 `post.moreCount` 渲染 `+N` 方块。
3. **`Psgggp` 疑似语法损坏**：`git show` 输出中 `AccountStore.kt` 出现 `private var cacheRaw: String?`，但 `git grep` 在工作区与 5 个提交里均零命中，源码实际为 `String?`；这是工具输出的字符替换假象，不是代码缺陷。

### 3.6 真实的体验回归（本次已修）

| # | 问题 | 位置 | 状态 |
|---|---|---|---|
| 1 | 已抓取小勾无 `contentDescription`，TalkBack 不播报 | `BrowsingScreen.kt` | ✅ 已修（并入缩略图描述） |
| 2 | 超话等级徽章无描述 | `TopicScreen.kt` `LevelBadge` | ✅ 已修 |
| 3 | 超话行展开/收起无状态播报 | `TopicScreen.kt` `TopicRowCard` | ✅ 已修（`stateDescription`） |
| 4 | 全屏大图失败只留黑屏 | `PostPickScreen.kt` | ✅ 已修（恢复"大图加载失败"提示） |
| 5 | 三个列表页记录首可见项却无人消费，返回即回顶 | `MainActivity.kt` 三处 `onVisibleIndex = {}` | ✅ 已修（接回 ViewModel 并作为初始下标） |

---

## 四、仍存在的风险（按优先级）

### P1 — 必须由人执行，静态审查无法替代

1. **真实账号 E2E 未做**：签到、自动重登、浏览博主、单条抓取四条链路依赖登录态，从未在重构后跑过。`docs/architecture.md` 的"登录态 E2E 7 步"仍未勾选。这是唯一可能推翻"功能完整"结论的地方。
2. **签名指纹未核对**：`app/keystore/wcm-release.p12` 与 CI Secret 是否同一把钥匙，决定用户能否覆盖安装。本次因安全策略无法读取签名口令，未验证。

### P2 — 架构债务（本次已还）

3. ~~`domain -> data` 反向依赖~~ **已消除**：`domain/build.gradle.kts` 不再有
   `project(":data")`；端口定义在 `:core`（`AccountPort` 13 项 / `CrawlSettingsPort` 14 项 /
   `TopicCachePort` 1 项 / `PanelPort` 2 项 / `CredentialPort` 2 项），实现在 `:data` 的
   `PortAdapters.kt`，由 `:app` 的 `DomainModule` 装配。CI 加 `grep project(":data")` 门禁。
4. ~~`object` 单例~~ **八个服务已改为构造注入**；`CrawlSaver` 的三个纯函数留在
   `companion object`，单测调用点零改动。
5. **仍然保留**：9 个 ViewModel 直接调 `:data`（`app -> data` 是合法方向，不是债）。
   若要继续收敛，建议只对 `HomeViewModel`（1025 行 / 12 个协作者）与 `TopicViewModel`
   抽用例层，其余 7 个抽了只会多一层转发。

### P3 — 文档漂移

6. 测试用例数三处口径不一：CHANGELOG 写 312、`architecture.md` 写 315、实测 `@Test` 为 322。
7. `docs/architecture.md` 待办第 1 条与第 4 条完全重复；第 2 条其实已在"已完成"里标注完成。
8. `README.md` 结构节仍写 `include(":app")`（实际 4 模块）。
9. `docs/design/ui-system-v2.0.md` 仍残留"三屏 / 三块内容"（实为 4 Tab）。

---

## 五、无缝升级条件 checklist

| # | 条件 | 结论 | 说明 |
|---|---|---|---|
| 1 | 同 `applicationId` = `com.wcm.checkin` | ✅ | 两版一致 |
| 2 | 同签名证书 | ⚠️ **待人工核对** | 本地 p12 与 CI Secret 必须同钥匙；否则覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| 3 | `versionCode` 递增 | ✅ 已修 | 32 → **33**（原 32=32，虽可覆盖但不合规） |
| 4 | SharedPreferences 名与键不变 | ✅ | `wcm` + 26 键；`wcm_topics` / `wcm_credentials` / `wcm_guest` 亦同名 |
| 5 | 账号 JSON 字段不变 | ✅ | `Account` 20+ 字段逐字段一致，序列化/反序列化未改 |
| 6 | 抓取索引不变 | ✅ | `filesDir/crawl/index/<treeTag>.ndjson`，`t/a/p/f/d` 与字段名逐字一致 |
| 7 | SAF 目录授权 | ✅ | 持久授权绑定包名+URI，随覆盖安装保留 |
| 8 | WorkManager 既有任务 | ✅ | 同为 2.10.0、`ExistingPeriodicWorkPolicy.KEEP`；worker 类名已由 keep 规则钉住并实测未被混淆 |
| 9 | 运行时权限 | ✅ | 无新增（仅 `INTERNET` + `ACCESS_LOCAL_NETWORK`），升级不触发重授 |
| 10 | R8 后可用 | ⚠️ 部分验证 | release 构建通过、类名保留已验证；**冷启动与真实网络请求需真机确认** |

**升级前建议动作**：`adb install -r app-release.apk` 覆盖安装 → 确认账号列表、Cookie 有效期、抓取目标与索引「已抓」标记仍在 → 跑一轮签到 → 抓一轮增量。

---

## 六、本次实际执行的验证

```
./gradlew --offline :app:compileDebugKotlin     BUILD SUCCESSFUL
./gradlew --offline :app:testDebugUnitTest      BUILD SUCCESSFUL（322 个用例，0 失败）
./gradlew --offline :app:assembleRelease        BUILD SUCCESSFUL（R8 + 资源收缩，APK 3.5 MB）
```

产物核验：

- `app/build/outputs/mapping/release/mapping.txt` 中
  `com.wcm.checkin.CheckInWorker -> com.wcm.checkin.CheckInWorker`（类名保留），
  对照 `com.wcm.checkin.HomeViewModel -> n6.c8`（正常混淆）—— 证明升级后旧 WorkSpec 仍能按类名实例化 worker。

**未执行**：真机/模拟器安装、真实账号登录、签到与抓取联调、TalkBack 实听、lint 全量（仅构建期 lintVital）。
