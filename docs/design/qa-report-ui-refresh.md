# QA 报告：UI 主题与布局层级改造（v0.6）

- 验证者：严过关（QA Engineer）
- 验证对象：`E:/项目/微博签到app`（Kotlin / View，Material 3，minSdk 23 / targetSdk 36 / compileSdk 36）
- 基线快照：`.workbuddy/baseline/src-2032/main/`（权威对照）
- 验证方式：独立跑通构建、单测、lint；用基线快照做 diff；静态资源解析；WCAG 公式实算；逐行读状态机。
- **本报告不采信任何"已完成"声明，所有结论均附命令输出或文件行号。**

---

## 0. 总体结论

| 项目 | 结果 |
|---|---|
| **QA_PASS** | **YES** |
| Blocker | **0** |
| Major | **0** |
| Minor | **10** |
| 干净构建 | ✅ BUILD SUCCESSFUL |
| 单元测试 | ✅ 119 / 119 通过，0 失败 0 错误 |
| Lint | ✅ **0 error**、52 warning（无 `NewApi`） |
| 抓取内核零改动 | ✅ 16/16 未出现在 diff |
| 业务动作回归 | ✅ 全部动作仍可触发，副作用一致（见 §5 表格） |
| 行为契约核对 | ✅ 工程侧 14/14 契约成立（见 §B4） |

**无阻塞项。** 10 项 Minor 均为清理/加固性质，不影响合并。

> **修订记录（v2，依据复核意见修正）**：初版把"动态取色覆盖品牌色"判为 Major，并写成"两条无障碍修正在 Android 12+ 上同时失效"。**该表述不成立，已更正**：动态取色替换进来的是 M3 由壁纸种子色生成的 tonal palette，其前景/背景配对本身按对比度约束设计（实算：浅色 tone40+tone100 = **6.46:1**，深色 tone80+tone20 = **7.72:1**）。准确说法是"**用哪套色**"的区别，不是"有没有无障碍"。故该条降级为 Minor（文档/声明准确性）。详见 §M1 与 §M10。

---

## 1. 环境与方法说明（重要，避免误读结论）

| 项 | 值 |
|---|---|
| JAVA_HOME | `C:/Users/adsm/dev/jdk17`（OpenJDK 17.0.20.1 Temurin） |
| Android SDK | `C:/Users/adsm/AppData/Local/Android/Sdk`（`local.properties:1`） |
| Gradle | 8.13 |

**关于"干净构建"的一处偏差，如实记录：**

`./gradlew clean` 在本机 Windows 上连续两次失败于文件锁：

```
> Task :app:clean FAILED
java.io.IOException: Unable to delete directory 'E:\项目\微博签到app\app\build'
  Failed to delete some children. ... (R.jar 被占用)
  New files were found. This might happen because a process is still writing to the target directory.
    - .../unit_test_lint_partial_results/...
```

处置：`./gradlew --stop` + `rm -rf app/build`（确认 `app/build GONE`）后，**不调用 `clean` 任务**直接跑 `assembleDebug testDebugUnitTest lintDebug`。此时构建目录为空，等价于干净构建。关键任务的执行状态如下（非 UP-TO-DATE，即真实重跑）：

- `:app:compileDebugKotlin` → **执行**
- `:app:testDebugUnitTest` → **执行**
- `:app:lintAnalyzeDebug` → **执行**
- `:app:assembleDebug` → **执行**，产出 `app/build/outputs/apk/debug/app-debug.apk`（8,680,836 bytes，21:13）

> 部分前置任务（如 `processDebugMainManifest`）显示 UP-TO-DATE，是从 Gradle 构建缓存还原的——缓存键包含全部输入，输入变了不会命中，故不影响结论。

**验证快照（证明结论对应当前源码，而非某个中间态）：**

- `app/src` 内最后一次修改发生在 **21:05:44**（`fragment_gallery.xml`、`values/styles.xml`、`drawable/ic_stop.xml` 是工程侧最后触碰的三个文件）。
- 本次验证的构建窗口为 **21:12–21:16**，`find app/src -newermt "2026-10-27 21:12"` **无输出** → 构建与所有静态核对均基于当前最终源码，其后源码未再变化。
- 该工程**不是 git 仓库**（`fatal: not a git repository`），因此无法用 `git diff` 做版本比对，全部改动范围结论均以 `.workbuddy/baseline/src-2032/` 快照为准。
- ⚠️ 若工程侧在 21:16 之后又改了 `app/src`，本报告需重跑（构建 + §C 资源绑定 + §D1 对比度最敏感）。

---

## A. 构建与测试

### A1. 干净构建 — ✅ PASS

```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 4m 15s
53 actionable tasks: 18 executed, 35 up-to-date
```

编译期仅有 3 条既有 Kotlin 警告，且全部落在**未改动**的抓取内核文件上（`Crawler.kt:577/710`、`GifEncoder.kt:177`），与本次改造无关。

### A2. 单元测试 — ✅ PASS

解析 `app/build/test-results/testDebugUnitTest/*.xml`（11 个套件）：

| 测试类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| AccountStorePureTest | 7 | 0 | 0 | 0 |
| AssetNameTest | 16 | 0 | 0 | 0 |
| CrawlCursorTest | 17 | 0 | 0 | 0 |
| CrawlIndexTest | 17 | 0 | 0 | 0 |
| CrawlPlannerTest | 26 | 0 | 0 | 0 |
| GalleryFilesTest | 5 | 0 | 0 | 0 |
| GifEncoderTest | 2 | 0 | 0 | 0 |
| PanelUrlTest | 7 | 0 | 0 | 0 |
| TakeoverTest | 10 | 0 | 0 | 0 |
| ThumbLoaderTest | 4 | 0 | 0 | 0 |
| WeiboApiPureTest | 8 | 0 | 0 | 0 |
| **合计** | **119** | **0** | **0** | **0** |

失败用例全名：**无**。

### A3. Lint — ✅ PASS

解析 `app/build/reports/lint-results-debug.xml`：

- **Error：0 条**
- **Warning：52 条**
- `NewApi`：**未出现**（硬红线未触碰）

52 条 warning 按 id 归并：

| id | 条数 | 归属判断 |
|---|---|---|
| `UseKtx` | 19 | 既有风格建议（AccountStore/Crawler 等未改动文件为主） |
| `UnusedResources` | 10 | 其中 1 条为本次新增（见 M5） |
| `Overdraw` | 4 | 既有 |
| `NotifyDataSetChanged` | 4 | 既有模式 |
| `NestedScrolling` | 2 | **既有**：基线 `fragment_home.xml`、`fragment_gallery.xml` 同样含 4 处 `ScrollView`（嵌套日志滚动区），非本次引入 |
| `CutPasteId` | 3 | 本次新增（见 M7） |
| `NewerVersionAvailable` / `AndroidGradlePluginVersion` | 4 | 依赖升级提示，与本次无关 |
| 其它（`UnusedAttribute`/`SetTextI18n`） | 2 | 既有 |

---

## B. 改动范围正确性

### B1. 抓取内核与业务文件零改动 — ✅ PASS（16/16）

`diff -rq` 基线快照 vs 当前 `app/src/main`，Kotlin 侧**只有 4 个文件**不同：

```
Files BASE/.../GalleryBrowseActivity.kt and CUR/.../GalleryBrowseActivity.kt differ
Files BASE/.../GalleryFragment.kt        and CUR/.../GalleryFragment.kt differ
Files BASE/.../HomeFragment.kt           and CUR/.../HomeFragment.kt differ
Files BASE/.../SettingsFragment.kt       and CUR/.../SettingsFragment.kt differ
```

逐项核对声称"未动"的 16 个文件，**全部未出现在 diff 中**：

`Crawler.kt` ✅ `CrawlRunner.kt` ✅ `CrawlIndex.kt` ✅ `CrawlPlanner.kt` ✅ `CrawlCursor.kt` ✅ `WeiboApi.kt` ✅ `PanelApi.kt` ✅ `CheckInWorker.kt` ✅ `LivePhotoConverter.kt` ✅ `GifEncoder.kt` ✅ `Hashing.kt` ✅ `AssetName.kt` ✅ `Takeover.kt` ✅ `AccountStore.kt` ✅ `MainActivity.kt` ✅ `ImagePreviewActivity.kt` ✅

**声明侧两处不精确（登记为 M6，文档问题，非代码缺陷）：**

| 声明 | 事实 | 证据 |
|---|---|---|
| 新增 10 个 vector drawable | 实际 **11 个**，漏报 `ic_stop.xml`（且它是被使用的：`fragment_gallery.xml:204` `app:icon="@drawable/ic_stop"`） | `diff -rq` 中 `Only in CUR/res/drawable: ic_stop.xml` |
| 改了「5 个资源文件」 | 实际被修改的是 **4 个** values 文件：`colors.xml`、`strings.xml`、`themes.xml`、`values-night/themes.xml` | `diff -rq` 输出 |
| 布局改动 12 个 | ✅ 属实，且集合与声明完全一致 | 见下 |

12 个被修改的布局（与声明逐一吻合）：
`activity_gallery_browse` `activity_image_preview` `activity_login` `dialog_add_target` `dialog_single_post` `dialog_topic_list` `fragment_gallery` `fragment_home` `fragment_settings` `item_account` `item_crawl_target` `item_gallery_grid`

> 附带确认：`activity_main.xml` **未改动**（`diff -q` 无输出），底部导航不受影响。

### B2. 4 个 UI 文件的改动是否只动呈现层 — ⚠️ PASS（有 1 处例外，见 M8）

逐文件结论：

| 文件 | 改动性质 | 判定 |
|---|---|---|
| `SettingsFragment.kt` | 仅新增 2 行整行点击监听（`rowAutoOnOpen`、`rowCrawlGif` → `switch.toggle()`） | ✅ 纯呈现 |
| `HomeFragment.kt` | 日志卡折叠、chip 状态渲染、账号行溢出菜单、空态样式化 | ✅ 纯呈现（`setBusy`/`setTopicBusy`/`pendingAutoRefresh`/`NonCancellable` 全部未改，见 §13） |
| `GalleryFragment.kt` | 日志折叠、`setRunningUi` 单按钮状态机、目标行溢出菜单 | ✅ 纯呈现 |
| `GalleryBrowseActivity.kt` | 除网格尺寸外，**把固定 3 列改为"宽度 ≥600dp 用 4 列"** | ⚠️ 属布局自适应，但已越出"只改样式"范围 → 登记 M8 |

**调用契约核对（`AccountStore`/`WeiboApi`/`CrawlRunner`/`PanelApi`）：**
- 调用次数、参数、顺序：**零变化**——这 4 个文件的所有调用点在 diff 中均未出现增删改，仅在其前后插入了 `setLogExpanded(true)`（纯视图副作用）。
- `HomeFragment.kt:315-326` 的 `setBusy`/`setTopicBusy` 逻辑与基线**逐字相同**（diff 未触及）。
- `HomeFragment.kt:402-416`、`484-495` 的 `withContext(Dispatchers.Main + NonCancellable)` + `pendingAutoRefresh` 补刷逻辑**逐字相同**。

### B3. 用户动作 → 触发控件 → 副作用 映射表

| # | 动作 | 改造前触发控件 | 改造后触发控件 | 副作用是否一致 | 证据 |
|---|---|---|---|---|---|
| 1 | 立即签到 | `btnCheckin` | `btnCheckin`（未变） | ✅ 一致 | `HomeFragment.kt:84` → `runAll(false)` |
| 2 | 仅同步面板 | `btnSync` | `btnSync`（未变） | ✅ 一致 | `HomeFragment.kt:85` → `syncOnly()` |
| 3 | 添加账号 | `btnAdd` | `btnAdd`（未变） | ✅ 一致 | `HomeFragment.kt:86-88` → `LoginWebViewActivity` |
| 4 | 刷新超话 | `btnRefreshTopics` | `btnRefreshTopics`（未变） | ✅ 一致 | `HomeFragment.kt:89-91` → `refreshTopics(manual=true)` |
| 5 | 点行进超话清单 | `item_account` 整行 | 整行短按 **+ 新增长按** | ✅ 一致（新增入口，未删旧路径） | `HomeFragment.kt` `row.setOnClickListener { showTopics(...) }` / `setOnLongClickListener` |
| 6 | 删除账号 | 行内常驻 `btnDelete` | 溢出菜单 `action_delete_account` | ✅ 一致 | `HomeFragment.kt:204-217`，仍为 `AccountStore.remove` + `topicStates.remove` + `render()`，弹窗文案与按钮不变 |
| 7 | 查看超话清单（菜单） | —（原本只能点行） | 溢出菜单 `action_view_topics` | ✅ 新增等价入口 | `HomeFragment.kt:184-203` |
| 8 | 选择目录 | `btnPickDir` | `btnPickDir`（未变） | ✅ 一致 | `GalleryFragment.kt:74` |
| 9 | 打开图库 | `btnOpenGallery` | `btnOpenGallery`（未变） | ✅ 一致 | `GalleryFragment.kt:79` |
| 10 | 添加博主/目标 | `btnAddTarget` | `btnAddTarget`（未变） | ✅ 一致 | `GalleryFragment.kt:75` |
| 11 | 单条抓取 | `btnSinglePost` | `btnSinglePost`（未变） | ✅ 一致 | `GalleryFragment.kt:76` → `pickSinglePost()` |
| 12 | 开始抓取 | `btnCrawlStart` | `btnCrawlStart`（未变，仅运行态隐藏） | ✅ 一致 | `GalleryFragment.kt:77` → `start()` |
| 13 | 停止抓取 | `btnCrawlStop` | `btnCrawlStop`（同上） | ✅ 一致 | `GalleryFragment.kt:78` → `crawlJob?.cancel()` |
| 14 | 自动抓图开关 | `switchCrawlAuto` | `switchCrawlAuto` **+ 整行** `rowCrawlAuto` | ✅ 一致 | `GalleryFragment.kt:81-87` → `AccountStore.setCrawlAuto` |
| 15 | 起始日期 | 行内文字按钮 `btnTargetSince` | 溢出菜单 `action_target_since` | ✅ 一致 | `GalleryFragment.kt` → `pickSince(target)` |
| 16 | 移除目标 | 行内文字按钮 `btnTargetDelete` | 溢出菜单 `action_target_delete` | ✅ 一致 | 同文案弹窗 + `removeCrawlTarget` + `renderTargets()` |
| 17 | 打开时自动签到开关 | `switchAutoOnOpen` | `switchAutoOnOpen` **+ 整行** `rowAutoOnOpen` | ✅ 一致 | `SettingsFragment.kt:71-79` |
| 18 | Live→GIF 开关 | `switchCrawlGif` | `switchCrawlGif` **+ 整行** `rowCrawlGif` | ✅ 一致 | `SettingsFragment.kt` |
| 19 | 保存面板 | `btnSavePanel` | `btnSavePanel`（未变） | ✅ 一致 | id 保留（`fragment_settings.xml`），Kotlin 未改 |
| 20 | 预览页分享/打开/删除 | `btnShare`/`btnOpen`/`btnDelete` | 同名 id（未变） | ✅ 一致 | `ImagePreviewActivity.kt:65-67`（该文件未改，id 均在 `activity_image_preview.xml` 内） |

**结论：20 个用户动作无一丢失，副作用全部等价。** 有 3 个动作（#6 #15 #16）从"常驻按钮"移到溢出菜单，多了一步点击——这是设计意图（降低误触），非缺陷，但属于交互成本上升，已在 §M3 提示。

### B4. 对照工程侧行为契约清单的二次核验（14 条，独立复核）

工程侧（Alex）在交接中给出 14 条"必须等价"的行为契约，其中多条涉及**状态与校验**、超出"控件→动作"映射范围。逐条复核结果：

| # | 契约 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | `btnCheckin`/`btnSync` 行为不变 | ✅ | `HomeFragment.kt:84-85` |
| 2 | `btnRefreshTopics` 移位但 id/行为不变 | ✅ | `:89-91` |
| 3 | `btnAdd` 变 48dp 图标按钮，仍开登录页 | ✅ | `fragment_home.xml:84-89` `IconButton`；`HomeFragment.kt:86-88` |
| 4 | 点行/菜单/长按/删除语义一致，chip 不残留 | ✅ | `:204-217` `remove`+`topicStates.remove`+`render()` → 整行重建，chip 随之消失 |
| 5 | **任务开始才展开；打开页面时的自动刷新**不**展开** | ✅ | `:348-361` `if (manual) setLogExpanded(true)`；自动路径 `:341` `manual=false`，手动 `:90` `manual=true` |
| 6 | 图库横幅整行可点；无目录 toast | ✅ | `GalleryFragment.kt:127-134` → `gallery_need_dir`「请先选择相册目录」 |
| 7 | ①无目标 ②无目录 ③已有任务在跑 → 均须回空闲态 | ✅ | ①② 在 `busy=true` **前** early return（`:379-386`）无残留；③ 见 §E1 |
| 8 | 目标行菜单行为/文案一致 | ✅ | `action_target_since`/`action_target_delete` |
| 9 | 单条抓取降级 + 文案改「单条抓取」 | ✅ | `fragment_gallery.xml:214` `single_title`；旧 `single_btn` 变孤儿（lint 同报） |
| 10 | 图片页日志默认收起 + `start()`/单条时展开 | ✅ | `:389`、`:499` |
| 11 | **表单校验必须还在** | ✅ | `SettingsFragment.kt:48-67`：留空→保存+toast；非法→`etUrl.error` 后 **return 不保存**；合法→保存+`ensureLocalNetworkPermission` |
| 12 | 空态按钮 `finish()`；工具栏仅「刷新」 | ✅ | `gallery_browser.xml` `<item` 计数 = 1 |
| 13 | 预览页三图标按钮 + 语义转 CD | ✅ | `activity_image_preview.xml:59-79`；`ImagePreviewActivity.kt:65-67` 未动 |
| 14 | 登录页只改样式 | ✅ | 仅样式/间距；另把写死中文「等待微博登录…」资源化（额外修了 i18n） |

**14/14 全部成立，未发现被 UI 重构破坏的契约。**

其中第 5 条（自动刷新刻意不展开日志）是最容易被误判为 bug 的一条，已确认是**有意设计且实现正确**：`manual` 参数从 `btnRefreshTopics` 与 `maybeAutoRefreshTopics` 两条链路分别传入，分支判断精确。

---

## C. 资源与绑定一致性

### C1. Kotlin 侧资源引用全部可解析 — ✅ PASS

对 `app/src/main/java/**/*.kt` 全量提取 `R.{id,layout,menu,string,style,drawable,color,dimen}` 并逐一验证目标存在：

| 类型 | 引用数 | 悬空引用 |
|---|---|---|
| `R.id` | 79 | **0** |
| `R.layout` | 全量 | **0** |
| `R.menu` | 2 | **0** |
| `R.string` | 70 | **0**（`ok`/`cancel` 为 `android.R.string.*`，正则误捕，已人工排除） |
| `R.style` | 1（`Widget_Wcm_Text_Empty`） | **0**（点/下划线映射到 `Widget.Wcm.Text.Empty`） |
| `R.drawable` | 全量 | **0** |
| `R.color` / `R.dimen` | 全量 | **0** |

### C2. XML 侧资源引用全部可解析 — ✅ PASS

| 类型 | 引用数 | 悬空 |
|---|---|---|
| `@string` | 61 | **0** |
| `@color` | 全量 | **0** |
| `@dimen` | 全量 | **0** |
| `@drawable` | 全量 | **0** |
| `@style`（`Widget.Wcm.*` 段） | 全量 | **0** |

### C3. 按页面核对 id 绑定（防"id 在别处存在→编译通过→运行时静默失效"）— ✅ PASS

这是本次改造最高危的隐性风险（改布局忘改 Kotlin）。逐页核对 Kotlin 引用的 id 是否**落在该页自己的布局/菜单里**：

| 页面 | 核对文件 | 结果 |
|---|---|---|
| MainActivity | `activity_main.xml` + `menu/bottom_nav.xml` | ✅ 全中 |
| ImagePreviewActivity | `activity_image_preview.xml` | ✅ 全中 |
| LoginWebViewActivity | `activity_login.xml` | ✅ 全中 |
| GalleryBrowseActivity | `activity_gallery_browse.xml` + `item_gallery_grid.xml` + `menu/gallery_browser.xml` | ✅ 全中 |
| HomeFragment | `fragment_home.xml` + `item_account.xml` + `dialog_topic_list.xml` + `menu/item_account_actions.xml` | ✅ 全中 |
| GalleryFragment | `fragment_gallery.xml` + `item_crawl_target.xml` + `dialog_add_target.xml` + `dialog_single_post.xml` + `menu/item_crawl_target_actions.xml` | ✅ 全中 |
| SettingsFragment | `fragment_settings.xml` | ✅ 全中 |

**运行时 NPE 重点复核**：`HomeFragment.kt:229` `bindAccountStatus` 里 `val chipRow = row.findViewById<View>(R.id.chipRow)`、以及 `:188` `val more = row.findViewById<View>(R.id.btnAccountMore)` 均为**平台类型、无 `?:` 兜底**，id 缺失即 NPE。已确认 `chipRow`(`item_account.xml:31`)、`chipDone`(:39)、`chipPending`(:43)、`btnAccountMore`(:58) 均存在 → **当前无 NPE 风险**，但建议后续加 `?:` 加固。

### C4. 自定义 style 均被引用 / values-land 兼容 — ✅ PASS

25 个 `Widget.Wcm.*` style 引用计数（`used_as_style` + `used_as_parent`）全部 ≥1；唯一 XML 侧计数为 0 的 `Widget.Wcm.Text.Empty` 由 **Kotlin** 引用（`setTextAppearance(R.style.Widget_Wcm_Text_Empty)`），非孤儿。

`values-land/dimens.xml` 覆写 3 个 key：`log_window_height`、`crawl_log_window_height`、`topic_list_height` —— **全部存在于 `values/dimens.xml`**，无孤立 key，横屏不会因缺默认值而编译失败或取到 0。✅

（附：`topic_list_height` 横屏从 320dp 压到 200dp，用于 `dialog_topic_list.xml`；`log_window_height` 220→132dp，用于 `Widget.Wcm.LogScroll`。逻辑合理。）

### C5. 新增 vector drawable 合法性 — ✅ PASS

16 个 `ic_*.xml` 全部具备 `android:width/height`、`android:viewportWidth/Height`、非空 `pathData`：

```
ic_add / ic_chevron_right / ic_delete / ic_expand_more / ic_folder / ic_image
ic_more_vert / ic_open_in_new / ic_share / ic_stop / ic_sync
        → 均为 width=24dp height=24dp viewport=24x24 pathData=1
```

无 `android:pathData=""`。且 aapt 编译通过（构建成功），语法层已被工具链证实。语义层（图形是否表意正确）无法静态验证 → 见 §16。

---

## D. 视觉 / 可访问性硬指标

### D1. 对比度实算（WCAG 2.1：sRGB 线性化 + 相对亮度）— ✅ PASS

计算方法：`L = 0.2126*R + 0.7152*G + 0.0722*B`（各通道先线性化），`ratio = (L_light+0.05)/(L_dark+0.05)`。

| 场景 | 色值 | 实算比值 | 正常文本 ≥4.5 | 大字 ≥3.0 |
|---|---|---|---|---|
| 浅色：填充按钮白字 / colorPrimary | `#FFFFFF` on `#C4344E` | **5.32 : 1** | ✅ PASS | ✅ |
| 浅色：colorPrimary 作前景压白底 | `#C4344E` on `#FFFFFF` | **5.32 : 1** | ✅ PASS | ✅ |
| 深色：colorOnPrimary on colorPrimary | `#1C1B1F` on `#F54868` | **4.90 : 1** | ✅ PASS | ✅ |
| 深色：对照（若不修 onPrimary） | `#FFFFFF` on `#F54868` | 3.49 : 1 | ❌ FAIL | ✅ |
| 浅色 chip「已签」 | `#17512C` on `#DCF2E1` | **7.93 : 1** | ✅ PASS | ✅ |
| 浅色 chip「待签」 | `#6E4500` on `#FDEED2` | **7.29 : 1** | ✅ PASS | ✅ |
| 深色 chip「已签」 | `#A8DCB8` on `#1E3B28` | **7.94 : 1** | ✅ PASS | ✅ |
| 深色 chip「待签」 | `#F0C67E` on `#3D2E12` | **8.19 : 1** | ✅ PASS | ✅ |
| 预览页主文字 | `#FFFFFF` on `#000000` | **21.00 : 1** | ✅ PASS | ✅ |
| 预览页次要文字 | `#B3FFFFFF` on `#000000` | **10.02 : 1** | ✅ PASS | ✅ |
| 预览页危险色 | `#FF8A80` on `#000000` | **9.20 : 1** | ✅ PASS | ✅ |
| 预览页分享图标（图形元素 ≥3:1） | `#F54868` on `#000000` | **6.01 : 1** | — | ✅ |

**两处计算说明（体现"没信声明"）：**

1. `#B3FFFFFF` 带 alpha，**不能按不透明白色算**。按 0.702 alpha 与纯黑合成 → 实际呈现 `#B3B3B3`，对比度 **10.02:1**（若误按纯白算会得到 21:1，是错的）。仍达标。
2. 声明里的两个数值**不精确**（登记 M5）：声明 `5.27:1` → 实算 **5.32:1**；声明 `4.58:1` → 实算 **4.90:1**。实算**优于**声明，结论方向不变。
3. 预览页分享图标在 `Theme.Wcm.Preview`（`colorPrimary=@color/brand`）下为 6.01:1 ✅；若哪天误用浅色 `#C4344E` 压黑底则为 3.95:1（仍过 3:1，但余量很小）。

> **重要限定**：以上是本项目自定义色值在**品牌模式**下的实算。Android 12+ 启用动态取色时，`colorPrimary`/`colorOnPrimary` 会被 M3 由壁纸种子色生成的 tonal palette 替换，此时 AA **仍由系统调色板保证**（实算浅色 tone40+tone100 = 6.46:1、深色 tone80+tone20 = 7.72:1），只是"用哪套色"变了。唯一例外是预览页的恒黑背景（见 §M10）。

### D2. 硬编码残留 — ✅ PASS（1 处令牌遗漏，M4）

对 `app/src/main/res/layout/*.xml` 全量扫描：

| 检查项 | 基线 | 当前 | 判定 |
|---|---|---|---|
| `textSize="..sp"` | **37 处** | **0 处** | ✅ 声明"30+ → 0"**属实**（实际 37→0） |
| 硬编码颜色 `#RRGGBB(AA)` | — | **0 处** | ✅ |
| `@color/text_secondary` | 3 处 | **0 处** | ✅ 且该色值已从 `colors.xml` 彻底删除，全工程无定义无引用 |
| 硬编码中文 `android:text="中文"` | 5 处 | **0 处** | ✅ 额外收益（基线 `login`/`item_account`/`item_crawl_target`/`dialog_add_target` 的写死文案已全部资源化） |

**裸 dp 值共 20 处，逐条判定：**

| 行号 | 值 | 判定 |
|---|---|---|
| `activity_gallery_browse.xml:23`、`activity_image_preview.xml:20/35`、`activity_main.xml:13`、`fragment_gallery.xml:46/95/138/178/230`、`fragment_home.xml:47/80/113`、`fragment_settings.xml:88/118`、`item_account.xml:13`、`item_crawl_target.xml:11` | `0dp` | ✅ 合理例外（`0dp`+weight 布局写法，非魔法数） |
| `fragment_gallery.xml:254`、`fragment_home.xml:140` | `0dp` | ✅ 合理例外（显式覆盖 style 里的 `layout_marginTop`） |
| **`activity_gallery_browse.xml:47-48`** | **`48dp`** ×2 | ⚠️ **令牌遗漏**（见 M4） |
| `activity_image_preview.xml:14` `@android:color/black` | — | ✅ 合理例外（预览页恒黑沉浸背景，`colors.xml:22-23` 有注释说明） |

### D3. 深浅双态覆盖 — ✅ PASS（品牌/动态双模各自成立；预览页为例外，见 M10）

- **`text_secondary` 已彻底消失**：`grep -rn "text_secondary" app/src/main/` → 无任何结果（定义与引用双清零）。因此不存在"深色下这个色值对比不足"的隐患。✅
- **`values-night/` 只覆写状态 chip 4 个色**（`values-night/colors.xml:11-14`）：这是**正确的最小集**——其余颜色（`text_secondary` 已删；`on_preview_*` 因预览页恒黑不需深色版；`ic_launcher_background` 与主题无关）确实不需要深色版。✅
- **`themes.xml` vs `values-night/themes.xml` item 对称性：**

| item | 浅色 | 深色 | 判断 |
|---|---|---|---|
| `colorPrimary` | `@color/brand_dark` (:13) | `@color/brand` (:8) | ✅ 有意不同 |
| `colorOnPrimary` | 未声明 | `@color/on_primary_dark` (:11) | ✅ **有意缺失**：浅色下 `colorPrimary=#C4344E` 与 M3 默认 onPrimary(白) 已达 5.32:1，无需覆写 |
| `colorPrimaryDark` | `@color/brand_dark` (:14) | `@color/brand_dark` (:12) | ⚠️ 轻微不一致：深色 `colorPrimary` 是亮红而 `colorPrimaryDark` 是深红。因该 app **不设置 `statusBarColor`**（`themes.xml:3-4` 注释说明改用 insets 方案），此属性实际不生效 → 登记 M9，不影响观感 |
| `android:windowBackground` | ✅ | ✅ | 对称 |
| `windowLayoutInDisplayCutoutMode` | ✅ | ✅ | 对称（均带 `tools:targetApi="o_mr1"`） |
| `android:windowLightStatusBar` | `true` (:23) | `false` (:17) | ✅ 有意不同 |
| `Theme.Wcm.Preview` | 定义于 `values/themes.xml:29-36` | **无 night 版本** | ✅ 合理：该页背景恒 `#FF000000`，前景全用写死的 `on_preview_*`，深浅两态表现一致，无需覆写 |

### D4. 触控目标 — ✅ PASS

样基层（`values/styles.xml`）：

| style | 尺寸声明 | 行号 | 判定 |
|---|---|---|---|
| `Widget.Wcm.Button.Primary` | `minHeight=@dimen/touch_min`(48dp) | :145 | ✅ |
| `Widget.Wcm.Button.Secondary` | `minHeight=48dp` | :152 | ✅ |
| `Widget.Wcm.Button.Text` | `minHeight=48dp` | :159 | ✅ |
| `Widget.Wcm.IconButton` | `width=height=@dimen/touch_min`(48dp) | :167-168 | ✅ |
| `Widget.Wcm.ListRow` | `minHeight=@dimen/list_row_min_height`(48dp) | :180 | ✅ |
| `Widget.Wcm.LogHeader` | `minHeight=48dp` | :231 | ✅ |
| `Widget.Wcm.Chip` | 无 minHeight | :200-211 | ✅ **合理**：chip `clickable=false`/`focusable=false`，非交互元素，不受 48dp 约束 |

布局层可点元素实际高度核查：

| 元素 | 依据 | 判定 |
|---|---|---|
| `btnCheckin` | `Widget.Wcm.Button.Primary` | ✅ 48dp |
| `btnSync` / `btnRefreshTopics` | Secondary / Text | ✅ 48dp |
| `btnAdd` / `btnAccountMore` / `btnLogToggle` | `IconButton` 48dp | ✅ |
| `MaterialSwitch` 整行 `rowAutoOnOpen` / `rowCrawlGif` / `rowCrawlAuto` | `Widget.Wcm.ListRow` minHeight 48dp | ✅ |
| 日志卡头 `logHeader` / `crawlLogHeader` | `LogHeader` minHeight 48dp | ✅ |
| `btnCrawlStart` / `btnCrawlStop` / `btnSinglePost` / `btnPickDir` / `btnAddTarget` | Primary / Secondary / Text | ✅ 48dp |
| 图库入口卡 `btnOpenGallery` | `Card.Clickable` + 内层 `minHeight=48dp` + `card_padding` 16dp | ✅ 远大于 48dp |
| 网格项 `item_gallery_grid` | 兜底 `gallery_cell_min_height`=96dp；运行态由 `cellSizePx` 按列宽计算 | ✅ ≥96dp |

---

## E. 交互回归风险（静态推理）

### E1. 运行态单按钮状态机（最高危点）— ✅ PASS

逐行读出 `GalleryFragment.start()` 的三条路径（`GalleryFragment.kt:373-416`）：

```
387  busy = true
388  setRunningUi(true)      → btnCrawlStart GONE / btnCrawlStop VISIBLE / addTarget disabled
390  crawlJob = lifecycleScope.launch(Dispatchers.IO) {
391    try {
394      if (CrawlRunner.runAll(...) == RunResult.Busy) { log(...) } else { log("结束") }
399    } catch (e: CancellationException) { log("已停止") }     ← 路径② 停止按钮
401    catch (e: Exception)             { log("抓取异常") }     ← 路径③ 抛异常
403    finally {
406      withContext(Dispatchers.Main + NonCancellable) {
407        busy = false
408        setRunningUi(false)   → start VISIBLE / stop GONE / start+addTarget enabled
```

| 路径 | 触发 | 是否恢复初始态 | 判定 |
|---|---|---|---|
| ① `RunResult.Busy` | 与首页自动抓图抢锁失败 | 走 finally → `busy=false` + `setRunningUi(false)` | ✅ 恢复 |
| ② 协程被取消（点「停止抓取」`crawlJob?.cancel()`） | `CancellationException` 被 catch，不重抛 → finally 用 `NonCancellable` 执行 | ✅ 恢复 | ✅ |
| ③ 任意异常 | `catch (Exception)` → finally | ✅ 恢复 | ✅ |

**关键防线验证**：`finally` 里的 `withContext(Dispatchers.Main + NonCancellable)` 与基线**逐字一致**（diff 只把两行 `isEnabled=true` 换成 `setRunningUi(false)`），`NonCancellable` 保证「停止」取消本协程时仍能复位 → **不存在 `busy` 卡死、开始抓取永久灰掉的回归**。✅

初始态一致性：`btnCrawlStop` 在布局里就是 `android:visibility="gone"`（`fragment_gallery.xml:203`），与 `setRunningUi(false)` 的目标态一致，首帧不会出现两个按钮并排。✅

`startSingle()`（`:494-521`）：`busy` 闸门 → `finally` **同样用 `NonCancellable`** 复位 `busy=false` 并恢复 `btnCrawlStart`/`btnSinglePost` 的 `isEnabled`。✅

`HomeFragment` 侧（`setBusy`/`setTopicBusy`/`pendingAutoRefresh`/`NonCancellable`）经 diff 确认为**零改动**，行为与基线等价。✅

### E2. 日志折叠 + 自动展开 — ⚠️ PASS（1 处脆弱点，M2）

展开/收起实现（`HomeFragment.kt:295-311`、`GalleryFragment.kt:342-356`）：
- 收起：`logScroll.visibility=GONE`，`tvLogSummary.visibility=VISIBLE`，箭头 `rotation=0`
- 展开：反向 + `scroll.post { fullScroll(FOCUS_DOWN) }`

**「高度为 0 会不会抛异常 / 永远判在底部」的正面回答：不会崩，也不会误判**，因为：
1. `renderLog` 在收起状态下**直接 `return`**（`HomeFragment.kt:283`、`GalleryFragment.kt:335`），根本不执行滚动逻辑；GONE 视图的 `canScrollVertically` 与 `fullScroll` 都无副作用。
2. 展开时 `LogScroll` 有固定高度（`values/styles.xml:242` → `@dimen/log_window_height` 220dp / 横屏 132dp），不存在"展开后高度仍为 0"的情况。
3. 展开路径主动 `fullScroll(FOCUS_DOWN)`，用户展开即看到最新一行。

**但发现一处脆弱点（M2）**：基线是
```
val wasAtBottom = !scroll.canScrollVertically(1)   // 先取状态（旧文本布局）
tv.text = text
if (wasAtBottom) scroll.post { fullScroll }
```
现改为
```
tv.text = text
...
if (!scroll.canScrollVertically(1)) scroll.post { fullScroll }
```
即「是否贴底」的判定从"赋文本**前**"挪到了"赋文本**后**"。当前能正常工作，是因为 `tv.text=` 触发 `requestLayout` 但测量尚未执行，`canScrollVertically` 读到的仍是旧布局尺寸——**这是靠时序巧合保持等价**。若将来在两步之间插入任何强制同步测量（或换成 `setText` 后立即 `measure`），行为会反转成"停在底部时反而不跟随新日志"。建议改回先取值。

### E3. 溢出菜单可访问性 — ✅ PASS（附 1 处 M3）

| 检查项 | 结果 |
|---|---|
| 账号行菜单锚点 `btnAccountMore` 有 `contentDescription` | ✅ `item_account.xml:60` = `@string/account_more`（"更多操作"） |
| 目标行菜单锚点 `btnTargetMore` 有 `contentDescription` | ✅ `item_crawl_target.xml:35` = `@string/crawl_target_more`（"目标操作"） |
| 长按路径锚点 | ✅ `HomeFragment.kt` 长按传的是同一个 `more` |
| 菜单项文案来自 `strings.xml` | ✅ 4/4：`account_action_topics`、`account_action_delete`、`crawl_since_btn`、`crawl_remove_short` 全部为 `@string` 引用，无硬编码 |
| 菜单项 id 与 Kotlin 分支匹配 | ✅ `action_view_topics`/`action_delete_account`/`action_target_since`/`action_target_delete` 与 `when(item.itemId)` 逐一对应 |

**M3**：日志卡头的箭头按钮 `btnLogToggle`(`fragment_home.xml:117-125`) 与 `btnCrawlLogToggle`(`fragment_gallery.xml:234-242`) 都设了 `android:importantForAccessibility="no"`，而 `setLogExpanded()` 恰好把"展开/收起"的 `contentDescription` 写在这两个被屏蔽的按钮上（`HomeFragment.kt:303`、`GalleryFragment.kt:348`）→ **TalkBack 用户听不到展开/收起状态变化**。可点的是父容器 `logHeader`，但父容器没有 `contentDescription`，也不会播报状态。

---

## 10. 风险分级与「必须修清单」

### 必须修清单（按 team-lead 决策收敛后，仅 2 项 + 1 项文档动作）

| 项 | 内容 | 负责 | 阻塞合并 |
|---|---|---|---|
| **M10** | 预览页 `btnShare` 的 `?attr/colorPrimary` 换固定色资源 | 工程 | 否（排期中） |
| **O1** | 目标行"假可点"：接监听 **或** 去 clickable+波纹 | 工程 | 否（排期中） |
| **M1** | 文档补一句"品牌模式 / 动态取色模式"口径说明 | 文档 | 否 |
| ~~M5~~ | ~~注释数值不精确~~ | — | ✅ 已关闭 |

> 明确**不做**的两项（已决策）：不关闭 `DynamicColors`（`WcmApp.kt` 不改）；不把动态取色当作无障碍缺陷。

### Blocker（0）
无。

### Major（0）

**无。**

> 初版曾把「动态取色覆盖品牌色」判为 Major，并把预览页连带影响写在其中。经复核，**该 Major 不成立**（理由与重算数据见 §Minor 的 M1）；预览页那个问题独立成立，但性质与等级也已修正（见 §M10）。

### Minor（10）

**M1（原 Major，已降级）— 对比度声明未区分「品牌模式 / 动态取色模式」**

- 现象：文档与代码注释把 `5.32:1` / `4.90:1` 表述为全设备保证，但 `WcmApp.kt:10` 的 `DynamicColors.applyToActivitiesIfAvailable(this)` 会在 **API 31+** 用壁纸派生的 tonal palette 替换 `colorPrimary`/`colorOnPrimary`（`WcmApp.kt` 本身未被本次改动，属既有行为）。
- **为什么不是无障碍回归（此处为初版错误，已更正）**：M3 的 tonal palette 配对是**按对比度约束设计**的。Tone 即 L\*，相对亮度 Y 只由 L\* 决定、**与色相无关**，故可精确推算且可外推至任意壁纸种子：

| 动态取色模式下的配对 | tone | 实算 Y | 对比度 |
|---|---|---|---|
| 浅色 `primary`(tone40) + `onPrimary`(tone100 白) | 40 / 100 | 0.1125 / 1.0000 | **6.46 : 1** |
| 深色 `primary`(tone80) + `onPrimary`(tone20) | 80 / 20 | 0.5668 / 0.0299 | **7.72 : 1** |

  两者均远超正常文本 4.5:1。**因此准确表述是：品牌模式下 AA 由本项目 `#C4344E`+白（5.32:1）与 `#F54868`+`#1C1B1F`（4.90:1）保证；动态取色模式下改由 M3 tonal palette 保证。差异是"用哪套色"，不是"有没有无障碍"。**
- 影响：维护者可能误以为所有设备都落在 5.32，属**信息披露准确性**问题，无用户可感知的无障碍回归。
- 已定决策（team-lead 下发）：**不关闭 `DynamicColors`**，`WcmApp.kt` 不改；改为在文档补一句口径说明。本项因此收敛为纯文档动作。
- 建议文案：「品牌模式下对比度由 `#C4344E`/`#F54868` 保证；Android 12+ 动态取色模式下，前景/背景配对由 M3 tonal palette 按其对比度规则生成（浅色 ≈6.46:1、深色 ≈7.72:1）。」

**M2 — 日志自动跟随底部的判定顺序变化（脆弱，非当前缺陷）**
`HomeFragment.kt:284`、`GalleryFragment.kt:336`：`canScrollVertically(1)` 的取值时机从"赋文本前"移到"赋文本后，依赖尚未重测量的旧布局"才与基线等价。建议恢复为赋值前先取 `wasAtBottom`。

**M3 — 展开/收起状态对读屏用户不可知**
`fragment_home.xml:123`、`fragment_gallery.xml:240` 的 `importantForAccessibility="no"` 使 `setLogExpanded` 写入的 `contentDescription`（`HomeFragment.kt:303`、`GalleryFragment.kt:348`）失效。建议把 `contentDescription`（含"已展开/已收起"）写到可点的父容器 `logHeader`/`crawlLogHeader` 上。

**M4 — 令牌遗漏：48dp 硬编码**
`activity_gallery_browse.xml:47-48` 空态 ImageView 写死 `48dp`×2。建议改用 `@dimen/touch_min`（若视为触控尺寸）或 `@dimen/icon_size`（若视为图标尺寸）。该 ImageView 本身不可点，属装饰件，故仅 Minor。

**M5 — 声明中的对比度数值不精确 → ✅ 已由工程侧修复（21:22），且经我实算校验**

初版问题：注释写 `5.27:1` / `4.58:1`，实算为 `5.32:1` / `4.90:1`。

工程侧已更新三处注释，我**逐个实算复核，全部吻合**（不是默认他改对了）：

| 文件:行 | 注释声明 | 我的实算 | 判定 |
|---|---|---|---|
| `colors.xml:15` | `#F54868` 上近黑字 = 4.90 | 4.90 : 1 | ✅ |
| `values-night/themes.xml:7` | `#F54868` 压 `#1C1B1F` = 4.90 | 4.90 : 1 | ✅ |
| `values-night/themes.xml:7` | `#C4344E` 压 `#1C1B1F` = 3.22 | 3.22 : 1 | ✅ |
| `colors.xml:17` | `#381E72` 压 `#F54868` = 3.76 | 3.76 : 1 | ✅ |
| `themes.xml:11` | `#C4344E` 压 M3 浅色表面 `#FEF7FF` = 5.06 | 5.06 : 1 | ✅ |

本项**关闭**。附带确认一处改善：`themes.xml:10` 已把白字对比从旧的 `3.56` 更正为 `3.49`（我初版也算得 3.49），并把"压白底"精确化为"压 M3 浅色表面 `#FEF7FF`"，比原文更严谨。

**M6 — 变更清单与事实不符（2 处）**
新增 vector 实为 11 个（漏 `ic_stop.xml`，且它被 `fragment_gallery.xml:204` 使用）；"5 个资源文件"实为 4 个被修改的 values 文件。纯文档问题。

**M7 — `CutPasteId`（3 处，lint 提示）**
`HomeFragment.kt:303`（`btnLogToggle` 在同一方法内查了 2 次，`:302` 与 `:303`）、`GalleryFragment.kt:350`、`GalleryFragment.kt:369`。不是 bug，但可合并成一次 `findViewById` + 局部变量，顺便消除 lint 噪音。

**M8 — `GalleryBrowseActivity` 的列数逻辑属行为变更（唯一越出"纯呈现"的改动）**
`GalleryBrowseActivity.kt:84` 把固定 3 列改为「宽度 ≥600dp 用 4 列」（`:192`）。仍属 UI 层，但已不是"只改样式"。附带风险：`:68` 的 `addOnLayoutChangeListener` 回调里会重建 `GridLayoutManager` 并 `notifyDataSetChanged()`（`:85-92`），存在"布局变化 → 换 span → 再触发布局变化"的自激可能。当前靠 `manager.spanCount != span` 与 `size != cellSizePx` 双重守卫收敛（第二次回调即不再变更），**推理上不会死循环**，但每次进入会多一次重布局。建议挪到 `onSizeChanged`/`doOnLayout` 一次性计算，或对 span 变更做显式去抖。

**M9 — 深色主题 `colorPrimaryDark` 与 `colorPrimary` 不同族（当前不生效）**
`values-night/themes.xml:8` 用 `@color/brand`（亮红），`:12` 的 `colorPrimaryDark` 仍是 `@color/brand_dark`（深红）。因应用不设 `statusBarColor`（`themes.xml:3-4`），该属性实际不被读取，仅一致性瑕疵。

**M10 — 预览页分享图标用主题色压恒黑背景：余量偏薄且不确定（必修）**

- 位置：`activity_image_preview.xml:64` `btnShare` 的 `app:tint="?attr/colorPrimary"`，而该页背景恒为 `@android:color/black`（`:14`，`Theme.Wcm.Preview` 的 `windowBackground=#FF000000`）。
- **这里 M3 的配对保证不适用**：M3 保证的是 `primary` 对 `onPrimary`、或对 `surface` 的对比度，而本页背景是写死的纯黑，不是 `surface`。所以这是**唯一**一处动态取色会削弱本项目自身色彩契约的地方。
- 实算（品牌模式 vs 动态取色模式）：

| 模式 | 图标色 | 对纯黑对比度 | 图形元素门槛 3:1 |
|---|---|---|---|
| 品牌模式（现状常态） | `#F54868` | **6.01 : 1** | ✅ 余量充足 |
| 动态取色·浅色（tone40） | tone 40 | **3.25 : 1** | ✅ **仍达标**，但仅余 0.25（7.7%） |
| 动态取色·深色（tone80） | tone 80 | **12.34 : 1** | ✅ 充足 |

- **对初版表述的修正**：初版写"可能跌破图形 3:1"，**不准确**。M3 浅色 `primary` 固定为 tone 40，而 tone 40 的相对亮度恒为 0.1125（与色相无关），对纯黑恒为 **3.25:1** —— 即动态取色**不会**把它压到 3:1 以下，下限是 3.25:1。
- 那为什么仍列必修：① 余量只有 0.25（比品牌模式的 6.01 掉 46%），是全表最薄的一处；② 取值**非确定性**（随壁纸变），无法在发布前保证观感；③ 它是该页的主行动图标，理应稳定可辨。
- 建议（与 team-lead 决策一致）：把 `?attr/colorPrimary` 换成**固定色资源**（如 `@color/brand` 或新增 `@color/on_preview_accent`），使预览页完全脱离主题/壁纸影响 —— 与同排 `btnOpen`/`btnDelete` 已用固定 `on_preview_*` 的做法保持一致。

### Minor 状态汇总（便于跟踪）

| ID | 问题 | 状态 |
|---|---|---|
| M1 | 对比度声明未区分品牌/动态模式（原 Major，已降级） | 开放（文档动作） |
| M2 | 日志滚动判定顺序脆弱 | 开放 |
| M3 | 展开/收起 CD 写在被屏蔽按钮上 | 开放 |
| M4 | `48dp` 裸值 | 开放 |
| M5 | 注释对比度数值不精确 | ✅ **已关闭**（21:22 工程侧修复，我已实算复核 5/5 吻合） |
| M6 | 变更清单不实（11 vectors / 4 文件） | 开放（文档） |
| M7 | `CutPasteId` ×3 | 开放 |
| M8 | 图库列数逻辑 + 布局回调自激风险 | 开放（加固） |
| M9 | 深色 `colorPrimaryDark` 不同族 | 开放（瑕疵） |
| M10 | 预览页分享图标 tint（必修） | 开放（工程侧排期中） |
| O1 | 目标行"假可点" | 开放（工程侧排期中） |

**计数口径**：Minor 合计 **10**（M5 已关闭不计入）+ 1 项已关闭。

### 附带观察（不计入问题数）

**O1 — `item_crawl_target` 整行变成"假可点"（已在必修队列）**
`item_crawl_target.xml:8` 根布局用 `Widget.Wcm.ListRow`（`styles.xml:186` 含 `android:clickable="true"` + `:185` 有 ripple 背景），而 `GalleryFragment.kt:185-190` 只给 `btnTargetMore` 设了监听，**整行没有任何点击监听**。基线该行不可点（原本只有 2 个文字按钮）。结果：用户点行会看到水波纹但没反应，TalkBack 还会把它念成可点击。建议二选一：给行加 `setOnClickListener { showTargetMenu(more, t) }`（与账号行长按一致），或在布局里 `android:clickable="false"` 并换成无 ripple 背景。

**O1 的验收判据（修完后按此逐条核对，防止"改一半"留下错配）：**

| 若采用「点行开菜单」 | 若采用 `clickable=false` |
|---|---|
| ① 点行弹出与 `btnTargetMore` **同一个**菜单 | ① 点行**无任何波纹**（波纹来自 `selectableItemBackground`，必须一并去掉） |
| ② `tvTargetName`/`tvTargetUid`/`tvTargetProgress` 三块**仍不可单独点击**（不得各自触发不同动作） | ② 整行不再被 TalkBack 念成"可点击"（`clickable`/`focusable` 均为 false） |
| ③ TalkBack 不再出现"念成可点击但无动作"的错配 | ③ `btnTargetMore` 自身仍可点、仍有 `contentDescription`（"目标操作"） |
| ④ 三个点按钮的点击**不冒泡**到行（否则会连弹两次菜单） | ④ 三个点按钮的 48dp 触控区不受影响 |

> 共性红线：**不得出现"有波纹但不可点"或"可点但无波纹"的错配** —— 二者都属状态自相矛盾，比原问题更糟。

> 之所以列为"附带观察"而非 M10：它不导致崩溃、不丢功能，只影响可发现性。但它确实是本次引入的**新增**问题，建议随手修掉。

**O2 — chip 在「全部已签」时会显示「待签 0」**
`HomeFragment.kt:238-241`，`st.total - st.done == 0` 时 `chipPending` 仍显示"待签 0"。基线文案同样会带 0，非回归；纯观感建议。

**O3 — 嵌套 `ScrollView`（lint `NestedScrolling`）为既有问题**
基线 `fragment_home.xml`、`fragment_gallery.xml` 同样各含 4 处 `ScrollView`（页面滚动 + 日志滚动区）。非本次引入，记录备查。

---

## 16. 无法静态验证项（明确列出，不含糊放行）

| 项 | 为什么无法静态验证 | 需要什么条件 |
|---|---|---|
| 真机/模拟器上的实际观感（阴影层级、卡片 elevation 视觉效果、横竖屏切换表现、MaterialSwitch 动画） | 需渲染 | 在 API 23 / 30 / 31 / 36 各一台设备或模拟器上 `installDebug` 后人工走查 |
| **动态取色在 Android 12+ 的对比度（M1）** | **对比度本身可静态推算**（tone 即 L\*，Y 与色相无关，故 6.46/7.72 是确定值）；无法静态得知的是**具体壁纸派生出的色相与观感** | 若要核对"某台设备上实际是什么色"，需在 API 31+ 模拟器换壁纸后打印 `colorPrimary`/`onPrimary` 的 hct 值比对 |
| TalkBack 实际播报内容（M3、菜单可读性） | 需无障碍服务 | 开启 TalkBack 后逐项走查日志卡头、溢出菜单锚点、chip |
| 溢出 `PopupMenu` 在深色主题/边到边下的实际弹出位置与遮挡 | 需运行时布局 | 真机横竖屏 + 深色模式下手动点击行尾「⋮」 |
| 图库网格在平板/横屏下的 4 列切换与正方形效果（M8） | 需真实测量宽度 | 平板或旋转到横屏，确认列数切换且无死循环卡顿（重点看 `applyGridMetrics` 反复触发时是否掉帧） |
| vector 图形的语义正确性（图标是否表意恰当） | 静态只能验语法 | 人眼比对 Material Icons 原图 |
| `busy` 卡死的实测复现 | 静态推理已覆盖三条路径 | 建议真机上"开始抓取 → 立刻切 Tab → 切回 → 点停止 → 再点开始"，确认按钮可复用 |
| 滚动到底部的自动跟随（M2） | 依赖运行时测量时序 | 真机连续产出日志（抓取 50+ 条），期间上翻再到底，确认不打断阅读、贴底时能跟随 |

---

## 17. 附录：关键命令与原始输出

```bash
# 1) 干净构建（等效：手动清空 app/build 后不加 clean 任务）
./gradlew --stop && rm -rf app/build
./gradlew assembleDebug testDebugUnitTest lintDebug
# → BUILD SUCCESSFUL in 4m 15s / 53 actionable tasks

# 2) 改动范围（权威基线对照）
diff -rq .workbuddy/baseline/src-2032/main app/src/main
# → Kotlin 仅 4 个文件不同；16 个内核文件全部未出现在输出中

# 3) 单测汇总
python -c "…解析 app/build/test-results/testDebugUnitTest/*.xml…"
# → 合计: tests=119 failures=0 errors=0 skipped=0

# 4) lint
python -c "…解析 app/build/reports/lint-results-debug.xml…"
# → 严重度分布: {'Warning': 52}   （Error = 0，无 NewApi）

# 5) 硬编码残留
grep -rn 'textSize="'      app/src/main/res/layout/   # → 无输出（基线 37 处）
grep -rnP '#[0-9A-Fa-f]{6,8}' app/src/main/res/layout/ # → 无输出
grep -rn 'text_secondary'  app/src/main/               # → 无输出

# 6) 对比度（WCAG 2.1 线性化 + 相对亮度）
python /tmp/contrast.py
# → 白/#C4344E=5.32  #1C1B1F/#F54868=4.90  chip 7.29~8.19  preview 9.20~10.02
```

**产物**：`app/build/outputs/apk/debug/app-debug.apk`（8,680,836 bytes）

---

## 18. 交付判定

- **QA_PASS: YES**（无阻塞项）
- 代码层面：构建、单测、lint、资源绑定、业务动作回归、14 条行为契约**全部通过**；抓取内核确认零改动；未发现崩溃/卡死/功能丢失路径。
- 无 Blocker、无 Major。10 项 Minor 均为清理/加固性质，不阻塞合并。
- 已在队列中的两项必修（team-lead 决策，工程师执行中）：
  1. **M10** 预览页分享图标改为固定色资源（脱离主题/壁纸）；
  2. **O1** 目标行"假可点"（按 §O1 验收判据核对，重点是不得留下"波纹/可点"错配）。
- 一项关闭：**M5** 注释对比度数值不精确 —— 工程侧 21:22 已修，我已实算复核 5/5 吻合。
- 遗留问题数：**Blocker 0 / Major 0 / Minor 10（+1 已关闭）**。

*本报告仅验证，未修改 `app/src` 下任何文件。*
