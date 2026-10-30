# 代码审查报告（2026-10-30）

> 范围：`app/src/main`（34 个 Kotlin 文件 / 约 7.9k 行 + 16 个资源目录）、`docs/`、构建与 CI 配置。
> 方法：全量阅读源码 + 资源引用交叉核对（`R.string` / `R.id` / `@string` / 颜色令牌）+ 规范文档逐条比对 + Git 状态检查。
> **限制**：本次审查环境无 JDK，未能复跑 `gradlew testDebugUnitTest lintDebug`（项目日志记录当日曾 182 例全绿 + `assembleDebug` 通过）。因此下文结论均为静态推导，凡涉及运行时行为的已标注「待验证」；P0-1 的最终定性需要在真机上抓一次 `GuestSession.lastError` 才能坐实。

## 0. 结论摘要

| 维度 | 评价 |
| --- | --- |
| 安全与权限 | **合规**。https + 域名白名单（`WeiboApi.weiboUrl`）、WebView 导航白名单 + 关闭 file/content 访问、`allowBackup=false` + 备份排除、面板明文收敛内网（`PanelUrl`）均已落地并有单测。 |
| UI 规范一致性 | **基本合规**。四档尺寸（16/20/24/32dp、环 88/76/96/104dp、图库 3/4/4/5 列、日志 200/140/280/340dp）、颜色与字体令牌、挖孔 `shortEdges`、边到边 insets 全部与 `docs/design/ui-system-v2.0.md` 对齐。 |
| 资源一致性 | **合规**。无缺失引用；除 `app_name`（由 manifest 引用）外无死字符串资源。 |
| 功能正确性 | **存在 2 个高危缺陷**（游客身份链路可能整体失效、覆盖边界把置顶帖算进下界），会导致「功能静默降级 / 数据永久缺口」。 |
| 并发与异常处理 | **薄弱**。首页状态容器与账号存储存在多线程无锁读改写；索引写入异常被静默吞掉。 |
| 结构与可读性 | 巨型类集中（`Crawler` 1194 行、`HomeFragment` 1083 行），日志面板/筛选 chip 重复实现。 |
| 文档同步 | **部分失真**。4 处代码与文档互相矛盾（详见 §5），其中 1 处直接影响功能判断。 |
| 工程流程 | 版本已到 0.9.8，但仓库只有 1 个 commit、1442 行改动 + 5 个新文件未提交，无 CHANGELOG。 |

---

## 1. P0 · 高危（正确性 / 功能可能整体失效）

### P0-1 游客身份握手实现与项目自研审计结论冲突，相册模式可能永远跑不起来
- **位置**：`app/src/main/java/com/wcm/checkin/GuestSession.kt:141-195`（`handshake` / `requestVisitorCookie`）；对照 `docs/research/weibo-guest-endpoint-audit.md` §2.3、§4.4。
- **现象**：
  - 代码走的是 H5 端**单跳**入口 `visitor.passport.weibo.cn/visitor/visitor?entry=sinawap&a=enter`，KDoc 声称「一次 GET 就在 302 的 Set-Cookie 里带上 `_T_WM`」。
  - 项目自己的审计文档（同一份仓库、2026-10-30 实测）结论正相反：**该地址返回 200 HTML，Set-Cookie 只有 `WEIBOCN_FROM`，没有 `_T_WM`，纯 HTTP 客户端走不通**，并明确标注「勿用」；文档给出的唯一可用路径是 `passport.weibo.com` 的**两步握手**（`genvisitor2` → `visitor?a=crossdomain`）。
  - 两条链路都没实现 → `GuestSession.cookie()` 恒为 null → `WebAjax.imageWall()` 抛「游客身份不可用」→ `Crawler.runAlbum` 返回 false → `CrawlRunner.runAll` 每轮静默回退时间线。README 承诺的「相册模式默认用游客身份、不占账号频次」实际不成立，而回退机制把失败掩盖了，日志里只会看到一句「相册模式没跑起来」。
- **加重项**：`GuestSession.refresh()` / `invalidate()` **从未被调用**（全仓 0 处引用），即使某次握手成功，cookie 失效后也永不刷新，只能等进程重启。
- **建议**：
  1. 按审计文档实现 `genvisitor2` + `crossdomain` 两步握手（JSONP 解析 `tid/sub/subp`），或明确废弃游客链路、把相册模式改为「账号身份 + Web 端 ajax」并把 README/审计文档同步改口。
  2. `WebAjax` 失败且 `identitySuspect` 时调用 `GuestSession.refresh()` 重试一次，而不是整轮 `guest.blocked = true` 后就地放弃。
  3. 在 `runAlbum` 回退时把 `GuestSession.lastError` 写进日志，避免「点了没反应」式的静默。
  4. 文档侧：§1 说「游客 cookie 是 `.weibo.com` 域，m 站不接受」，而 `GuestSession.probe()` 偏偏用 `m.weibo.cn/api/config` 探活 —— 这条自相矛盾必须在定论后统一。

### P0-2 覆盖边界把置顶帖计入下界，可导致历史方向永久不抓（数据缺口 + 进度谎报）
- **位置**：`CrawlPlanner.kt:115-121`（`low` 统计全部帖，仅 `high` 排除 pinned）、`:144`（`SINCE_DATE` 用 `slice.minAt`）、`:213`（历史阶段同样含 pinned）、`CrawlCursor.kt:162-168`（`afterNewPhase` 直接采用 `coverageLow`）。
- **原理**：置顶帖是**被置顶的老帖**，它的 `at` 远早于页面真实位置。把它算进 `low` / `minAt`，会让「本页最老时间」变成一个虚假的深值。
- **两条后果**：
  - **A（设了起始日期，最常见）**：目标博主有 2020 年置顶帖、起始日期设 2024-01-01 → `planNew` 第 1 页就命中 `slice.minAt < sinceSec`，立即 `stop = SINCE_DATE`；`afterNewPhase` 把 `oldestCoveredAt` 写成 2020-xx（早于 since）→ `CrawlPlanner.shouldRunHistory()` 第 76 行判定「已推到起始日期以下」→ **后续每一轮都不再跑历史阶段**。置顶帖之后到 2024-01-01 之间的图片永远抓不到，而界面显示「已推到起始日期」。
  - **B（未设起始日期）**：首轮只走 6 页，游标却声称覆盖「2020-xx ~ 今天」；进度卡与日志的覆盖区间是假的，Phase A 每轮还要重扫 6 页。
- **单测缺口**：`CrawlPlannerTest.new_pinnedPostDoesNotRaiseWatermark` 只覆盖「置顶帖更新」的情形，未覆盖「置顶帖更老」，因此 182 个用例全绿也发现不了。
- **建议**：`low` / `minAt` 一律排除 `pinned`（`posts.filter { !it.pinned }`，并保留「整页全是置顶帖」时回退到全量）；`SINCE_DATE` 判定同样只用非置顶帖的时间；补两条单测（置顶帖早于起始日期 / 置顶帖早于既有覆盖下界）。

---

## 2. P1 · 严重（稳定性 / 数据一致性 / 明显性能问题）

| # | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| P1-1 | `HomeFragment.kt:102`（`topicStates`）、`:648/:664/:808/:1013`（IO 线程写）、`:148/:355/:386/:471`（主线程读/遍历） | `topicStates` 是普通 `HashMap`，`Dispatchers.IO` 里写、主线程 `render()` 里遍历与读；并发扩缩容会丢更新，极端情况抛 `ConcurrentModificationException`（`renderTopicProgress` 的 `values.filter`、Hero 卡点击的 `entries`） | 换成 `ConcurrentHashMap` 只是止血；正解是删掉这份镜像态，直接订阅已有的 `TopicStore.state`（StateFlow，`:51-52` 已定义却**全仓 0 处订阅**） |
| P1-2 | `AccountStore.kt:110-124` + `HomeFragment.kt:696-717` + `TopicFragment.kt:230-237` + `CheckInWorker.kt:50` | 账号表是「全量读 → 改 → 全量写」，至少 4 个写入方在不同线程（签到 finally 的 `mergeSave`、刷新时的 `updateCookieAndName`、超话页 `rotateCookie`、Worker）。并发下会互相覆盖：轮换后的新 cookie 被旧快照写回、`lastResult` 丢失 | 给 `AccountStore` 加进程级锁（与 `CrawlIndex.LOCK` 同思路）或改成单写者（统一走一个 `AccountRepository` + `Mutex`），读侧只读内存快照 |
| P1-3 | `GalleryBrowseActivity.kt:260-304`（`rebuild`）、`:150-153`（搜索监听） | `filter + sort + group + DiffUtil + labelTabs` 全在主线程，每次按键都跑一遍。README 自述规模到 **10 万张**：单次全量排序 + DiffUtil 足以造成数百 ms 卡顿直至 ANR | 搜索加 200–300ms 防抖；`applyFilter/sortRows/groupBy*` 放 `Dispatchers.Default`；改用 `ListAdapter + AsyncListDiffer` 把 diff 也移出主线程；`labelTabs` 结果缓存到 `allRows` 变化为止 |
| P1-4 | `Crawler.kt:1173-1193`（`tryDownload`） | 先 `body.bytes()` 把整个响应读进内存，**之后**才判 `bytes.size > 64MB`。一个异常的超大响应会先 OOM，上限形同虚设 | 先看 `body.contentLength()`（>0 且超限直接放弃），读流时累计计数并在超过上限时中断 |
| P1-5 | `CrawlIndex.kt:219-221`（`byHash`） | L3 去重靠 `filesByName.values.firstOrNull { it.hash == hash }`——**每保存一张图 O(n) 线性扫描**。10 万文件时是每秒上万次级全表比较，抓取后段明显变慢 | 增加 `filesByHash: HashMap<String, FileRec>`，在 `onFileSaved` / `adopt` / `applyLine("f")` / 墓碑回退处同步维护 |
| P1-6 | `HomeFragment.kt:742`、`TopicFragment.kt:192`、`GalleryFragment.kt:654` | 长任务绑在 `viewLifecycleOwner.lifecycleScope`：旋转屏幕、切深浅色、进程被回收都会**静默取消整轮签到**（已签一半的账号不会续跑，也没有任何提示） | 签到/抓取这类「不能半途而废」的任务挂到 `Application` 或前台 Service 的作用域（抓取已有 `CrawlRunner` 单闸门可承载），UI 只订阅状态 |

---

## 3. P2 · 中等（局部错误 / 体验缺陷 / 健壮性）

| # | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| P2-1 | `GalleryBrowseActivity.kt`（全文无 `onResume`） | 在预览页删除图片后返回，网格仍显示已删除的文件（只有手动刷新/重新进入才更新） | `onResume` 里对比 `allRows.size` 与目录实际数量，或让预览页 `setResult` 回传删除结果 |
| P2-2 | `CrawlProgress.kt:54-58` + `Crawler.kt:379`（相册 + 回退时间线会再发 `Begin`） | `discovered` 只增不减，多目标/多阶段累加；`percent = processed/discovered` 会**下降**，与文件头「保证进度条单调」的注释相反。相册模式回退到时间线时再发一次 `Begin`，计数整体清零 | 进度按「当前阶段 + 当前目标」计分母，或显式 `Reset` 事件；相册回退前先发 `Finish` |
| P2-3 | `TopicFragment.kt:617-621`（`markDone`） | 签到成功回写快照时沿用 `snap.at` 旧时间戳；若快照是昨天的，刚签的这一条仍被判「非今日」，指标区继续显示 `—` | 成功回写时用 `at = System.currentTimeMillis()` |
| P2-4 | `GalleryFragment.kt:59-82`（`pickDir` 回调） | `requireContext()` 在回调里被多处直接调用（`:65/:71/:77`），Fragment 分离后抛 `IllegalStateException`；`runCatching` 只包住了 `takePersistableUriPermission` | 统一先 `val ctx = context ?: return` |
| P2-5 | `CrawlIndex.kt:446-456`（`append`）、`:462-468`（`flushLocked`） | 索引写盘失败被**完全静默**吞掉（注释只说「不能阻断抓取」）。磁盘满/索引文件被删时，用户看到的是「进度莫名回退、重复下载」，无从排查 | 首次失败记一次到 `log`/`Log.w`，并置一个 `degraded` 标志让 UI 提示 |
| P2-6 | `CheckInWorker.kt:23-52` | `CoroutineWorker.doWork` 默认在 `Dispatchers.Default` 上跑阻塞 OkHttp；且全部账号失败仍 `Result.success()`，WorkManager 不会重试 | 包一层 `withContext(Dispatchers.IO)`；全失败时返回 `Result.retry()` |
| P2-7 | `PanelApi.kt:32`（复用 `WeiboApi.client`） | 客户端默认跟随重定向；`X-Panel-Token` 是自定义头，OkHttp 在跨域 302 时不会剥离它 —— 面板被劫持/配置错时会外泄同步令牌与 cookie | 同步专用 client 设 `followRedirects(false)` |
| P2-8 | `LoginWebViewActivity.kt:288-310`、`:321-354` | 用裸 `Thread` + `Handler` 做网络与回调，与项目其余部分的协程风格不一致；Activity 销毁后线程仍在跑（最长 25s），`onVerified` 可能在已 finish 的页面上 `Toast/finish` | 改 `lifecycleScope` + `withContext(IO)`，用 `repeatOnLifecycle` 兜住生命周期 |
| P2-9 | `TopicFragment.kt:62-64`（`expanded` / `detailBusy` / `detailErrors` 均只用 `topic.key` 做键） | 「全部账号」模式下两个账号关注同一个超话（同一 `topicId`）→ 展开一条会把另一条也展开、详情加载态互相串 | 键改成 `uid + "\u0000" + key`（与 `buildRows` 的去重键保持一致） |
| P2-10 | `GalleryFragment.kt:273-304`（`refreshRetryCount`） | 每次进入页面都 `CrawlIndex.open(...).load()` 全量解析 NDJSON（10 万张 ≈ 17MB / 25MB 内存），只为拿一个待重试计数 | 用轻量计数文件（`.count` 旁挂）或在索引里维护 `retryCount`，`load()` 时增量更新 |
| P2-11 | `GifEncoder.kt:23-67` | `indices` 数组按 `frames[0]` 尺寸分配，却按每帧自身 `pixels.size` 写入；帧尺寸不一致时越界（当前上游都缩放到同一长边，故被 `LivePhotoConverter` 的 catch 掩盖成「转换失败」） | `encode` 入口校验所有帧尺寸一致，不一致直接 `require` 失败并给出明确原因 |

---

## 4. P3 · 低 / 可读性 / 结构优化

**4.1 结构（改动成本最低、收益最高）**

| # | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| P3-1 | `HomeFragment.kt:509-566` 与 `GalleryFragment.kt:567-619` | 「日志追加 + 贴底判断 + 展开/收起 + 摘要行」两处几乎逐行重复（约 60 行 × 2） | 抽 `LogPanel`（自定义 View 或小类），两页共用；顺带统一 `LOG_MAX_LINES` |
| P3-2 | `TopicFragment.kt:450-459` 与 `GalleryBrowseActivity.kt:358-366`；`HomeFragment.kt:414` 与 `GalleryFragment.kt:447` | `paintChip`、`buildEmptyHint`、`toast` 三处各写一遍 | 收进 `Ui.kt` 扩展函数 / 公共基类 |
| P3-3 | `WeiboApi.kt`（848 行，单例）、`Crawler.kt`（1194 行） | `WeiboApi` 同时承担 cookie 工具、域名白名单、超话接口、微博列表、图片 URL、UID 解析；`Crawler` 同时含 `TreeStore`、资产解析、规划执行、下载、相册模式 | 按职责拆分：`WeiboUrls`（白名单）/ `TopicApi` / `PostApi` / `Crawler.TreeStore` / `AlbumCrawler`。当前状态下任何一处改动都要读千行文件，回归风险高 |
| P3-4 | `Crawler.kt:634` 的 `RunState` 与 `CrawlRunner.kt:28` 的 `RunState` | 同包内两个同名类型，语义完全不同（一个抓取上下文、一个 UI 运行态） | 改名 `CrawlContext` / `RunnerState` |
| P3-5 | `CrawlProgress.kt:41/68/74` | 顶层函数 `reduce` / `percent` / `describeFailures` 直接暴露在包级，`reduce` 与 stdlib 的 `Iterable.reduce` 同名易误读 | 收进 `CrawlProgress` 伴生对象，或用 `@JvmName` + 更具体的名字 |

**4.2 死代码**

> 复核说明：初稿的「0 引用」判断用的是带前缀的 grep（如 `CrawlIndex.expectedSlots`），
> 漏掉了测试里的 `re.expectedSlots(...)` 调用，因此把几个**被单测覆盖的公开 API**
> 误判成了死代码。下表是复核后的结论。

| 位置 | 内容 | 处置（v0.9.9） |
| --- | --- | --- |
| `TopicStore.kt` | `prune`（`orphanKeys` 保留：纯函数、有单测） | 已删除 |
| `CrawlCursor.kt:126` | `CrawlDate.today()` | 已删除 |
| `CrawlProgress.kt:27` | 字段 `message`（从未赋值） | 已删除 |
| `WeiboThrottle.kt:50` | `reset()` | 已删除 |
| `GuestSession.kt` | `refresh()` | **不是死代码**：已接入 `Crawler` 的身份失效重试 |
| `CrawlPlanner.kt` | `RetryItem` / `planRetry` | 保留（`CrawlPlannerTest` 覆盖） |
| `GalleryFiles.kt` | `Summary` / `summarize` | 保留（`GalleryFilesTest` 覆盖） |
| `CrawlIndex.kt` | `expectedSlots` / `postCount` | 保留（`CrawlIndexTest` 覆盖） |

**4.3 可读性 / 注释**

- `WeiboApi.kt:196-197`：连续两条 KDoc（「出站白名单…」+「是否是微博自家域名…」）叠在 `isWeiboHost` 上，第一条成了孤儿注释。
- `WeiboApi.kt:11-18`：文件头只列 `verify / listTopics / sign` 三个接口，实际已有 20+ 个（列表、详情、图片、UID 解析、昵称等），头注释严重滞后。
- `Crawler.kt:23-24`、`:195-203`：仍写「列表与详情都**必须账号登录**、游客身份只服务昵称/相册这类与抓取无关的读取」，与 v0.9.8 新增的「相册模式全程游客身份」直接矛盾。
- `TopicFragment.kt:246-248`：`setRefreshing(on)` 只处理 `on=true`，`on=false` 是空实现（可见性实际由 `render()` 控制），API 语义误导。
- `GalleryBrowseActivity.kt:414`：`private fun toggleSearch(...) {        val open = ...` 语句与左大括号同行，疑似格式化时误删换行。

---

## 5. 文档与代码同步核对

| # | 文档位置 | 文档说法 | 代码实际 | 判定 |
| --- | --- | --- | --- | --- |
| D-1 | `docs/research/weibo-guest-endpoint-audit.md` §2.3 / §4.4 | H5 单跳访客入口「实测已失效，勿用」；唯一可用是 `passport.weibo.com` 两步握手 | `GuestSession.requestVisitorCookie()` 正是 H5 单跳，且 KDoc 声称可用；两步握手未实现 | **矛盾（P0-1）** |
| D-2 | 同上 §1 | 游客 cookie 是 `.weibo.com` 域，「在 m.weibo.cn 上不被接受（实测 ok:-100）」 | `GuestSession.probe()` 恰好用 `m.weibo.cn/api/config` 判活 | **自相矛盾**，需一次定论 |
| D-3 | `docs/design/topic-fields.md:41` | 「页间 `Thread.sleep(500)`」 | `WeiboApi.listTopics` 已改为 `WeiboThrottle.await(KEY_TOPIC_PAGE, 500)`（`delay`，可取消） | **过期** |
| D-4 | `docs/design/topic-fields.md:81` | 昵称缺失时「走 `containerid=100803` 兜底补拉」 | `fetchProfile` 用的是 `getIndex?type=uid&value=<uid>` / `data.screen_name` | **不准确** |
| D-5 | `docs/design/ui-system-v2.0.md:88`、`:175-179` | 「三屏一套骨架」「本 App 只有三块内容」「不做 5 Tab」 | 实际 4 个 Tab（首页/超话/图片/设置），§8 已补超话页却未回头改 §2/§5 | **过期** |
| D-6 | `README.md:173` 章节标题「抓取机制（v0.5）」 | 只描述时间线的 RETRY / A / B 三阶段 | v0.9.8 新增相册模式（`runAlbum`、`AlbumPass`、40 帖/20 分钟预算、相册游标）未在该章展开，只在功能列表里一行带过 | **不完整** |
| D-7 | `README.md:132-171` 结构树 | 列到 `GifEncoder / LivePhotoConverter` 后以 `...` 结尾 | `PanelUrl.kt`、`SafePrefs.kt` 未列出（被 `...` 掩盖） | 轻微 |
| D-8 | `app/build.gradle.kts:26-47` 版本注释 | 逐条登记 0.9.1–0.9.6 | `versionName = "0.9.8"`，0.9.7 / 0.9.8 的变更未登记；全仓无 `CHANGELOG.md` | **缺失** |
| D-9 | `docs/design/ui-system-v0.7.md` | 已被 v2.0 取代 | 文件本身没有「已废弃」标注 | 轻微（易误读） |

**已核对一致（无需改动）**：README 的功能清单（自动签到 3–8s、超话页四块固定、图库筛选/批处理、目录授权自检、WorkManager 6h 保活、权限清单）、`docs/design/ui-system-v2.0.md` §1/§3/§4 的令牌与四档尺寸、`CrawlIndex` 的 a/p/f/d 记录结构、`docs/design/topic-fields.md` 的字段与解析口径（除 D-3/D-4）。

---

## 6. 工程规范与流程

| # | 问题 | 说明 | 建议 |
| --- | --- | --- | --- |
| E-1 | **Git 历史与版本严重脱节** | `git log` 仅 1 个 commit（`v0.9.1`），工作区有 1442 行改动 + 5 个未跟踪文件（`GuestSession.kt`、`WebAjax.kt`、`WeiboThrottle.kt`、`AlbumAssetsTest.kt`、审计文档），`versionName` 已到 0.9.8。任何回滚、bisect、责任追溯都无从下手 | 按主题拆 5–6 个 commit（游客身份 / 相册模式 / 超话链路 / 登录白名单 / UI），每个打 tag；提交前先跑 CI 等价命令 |
| E-2 | 无 CHANGELOG | 变更说明散落在 `build.gradle.kts` 的版本注释里，且缺 0.9.7/0.9.8 | 新增 `CHANGELOG.md`（Keep a Changelog 格式），gradle 注释只留一句指向它 |
| E-3 | Release 未做混淆/裁剪 | `release { isMinifyEnabled = false }` 且无 `proguard-rules.pro`；同时 keystore 缺失时 release 会**静默无签名**产出 | 打开 R8 + 资源压缩并做一次冒烟；keystore 缺失时让 release 任务直接失败（或用 `assembleDebug` 明确 CI 语义） |
| E-4 | 本地无法验证 | 本机无 JDK，`testDebugUnitTest` / `lintDebug` 未跑；CI 仅在 push `main` 与手动触发时运行 | 至少给 `pull_request` 加同一工作流，避免「改完没人跑测试」 |
| E-5 | 测试覆盖偏斜 | 182 个用例集中在纯函数层（规划器/游标/索引/命名/面板地址）；`Crawler`(1194)、`CrawlRunner`、`WebAjax`、`GuestSession`、`LoginWebView` **零测试** —— 而 P0 两个缺陷正好落在 `CrawlPlanner`（缺边界用例）与 `GuestSession`（无测试） | 补 3 类用例：置顶帖边界、相册模式分页/回退、游客身份失败降级 |
| E-6 | 换行符 | `.gitattributes` 声明 `eol=lf`，但 `AccountStore.kt` / `Crawler.kt` / `GalleryFragment.kt` / `TopicStore.kt` / `fragment_gallery.xml` / `strings.xml` 工作区为 CRLF，git 已在告警 | 统一 IDE 行尾为 LF 并提交一次纯换行规范化 |

---

## 7. 建议的修复顺序

1. **定论游客身份链路**（P0-1）：二选一（实现两步握手 / 废弃并改口文档），并接上 `refresh()`。
2. **修覆盖边界**（P0-2）：`low` / `minAt` 排除 pinned + 补两条单测 —— 这是会**永久丢图**的缺陷，优先级等同 P0-1。
3. **消掉首页状态竞态**（P1-1、P1-2）：订阅 `TopicStore.state`，`AccountStore` 加单写者。
4. **性能三连**（P1-3、P1-4、P1-5）：图库主线程重建、下载先判长度、哈希索引。
5. **长任务生命周期**（P1-6）：签到/抓取移出 `viewLifecycleOwner`。
6. 文档批量同步（D-1～D-9）+ 死代码清理 + 重复实现抽取。
7. 工程收尾（E-1、E-2、E-4）：拆提交、打 tag、补 CHANGELOG、CI 加 PR 触发。

---

## 8. 修复落实（v0.9.9）

> 本节的「✓」表示代码已改；**尚未经过编译与真机验证**（本次审查环境无 JDK，
> 需在 CI 或 Android Studio 跑一次 `testDebugUnitTest assembleDebug` 才能定论）。

| 项 | 状态 | 落地位置 |
| --- | --- | --- |
| P0-1 游客身份两步握手 + 探活口径 + `refresh()` 接入 | ✓ | `GuestSession.kt`（重写）、`Crawler.kt` 的 `GuestGate.wall()` / `processAlbumPost()` |
| P0-2 置顶帖污染覆盖边界 | ✓ | `CrawlPlanner.PageSlice` / `planNew`，新增 4 条回归用例 |
| P1-1 首页超话状态竞态 | ✓ | `HomeFragment.topicStates` 改不可变快照 + `setTopicState()` |
| P1-2 账号表读改写竞态 | ✓ | `AccountStore.LOCK` + 新增 `patchAccount` / `setLastResult` |
| P1-3 图库主线程重建 | ✓ | `GalleryBrowseActivity.rebuild()/applyComputed()`、搜索 250ms 防抖 |
| P1-4 下载先判长度 | ✓ | `Crawler.tryDownload()` 先看 `Content-Length` |
| P1-5 哈希索引 O(n) | ✓ | `CrawlIndex.filesByHash` |
| P1-6 长任务生命周期 | ✓ | 新增 `AppScope.kt`；签到/抓取/补签改挂应用级 |
| P2-1 删图后图库不刷新 | ✓ | `ImagePreviewActivity.contentChanged` + 图库 `onResume` |
| P2-2 进度条倒退 / >100% | ✓ | `CrawlProgress.reduce()`（换目标才重置，分母不小于已处理） |
| P2-3 `markDone` 旧时间戳 | ✓ | `TopicFragment.markDone` 用 `System.currentTimeMillis()` |
| P2-4 `pickDir` 崩溃风险 | ✓ | `GalleryFragment` 回调改 `context ?: return` |
| P2-5 索引写盘静默失败 | ✓ | `CrawlIndex.lastWriteError` + `Crawler` 写日志 |
| P2-6 Worker 线程 / 重试 | ✓ | `CheckInWorker` `withContext(IO)` + `Result.retry()` |
| P2-7 面板同步跟随重定向 | ✓ | `PanelApi` 专用 client `followRedirects(false)` |
| P2-8 登录页裸 Thread | ✓ | `LoginWebViewActivity` 改 `lifecycleScope` |
| P2-9 超话展开态串号 | ✓ | `TopicFragment.rowKey(uid, key)` |
| P2-10 重试计数全量读索引 | ✓ | `CrawlIndex.retryHintOf` + 旁挂 `.retry` 文件 |
| P2-11 GIF 帧尺寸 | ✓ | `GifEncoder.encode` 增加尺寸校验 |
| P3-1 日志面板重复 | ✓ | 新增 `LogPanel.kt`，首页与图片页共用 |
| P3-2 chip / 空态 / toast 重复 | ✓ | 新增 `UiExt.kt` |
| P3-4 `RunState` 同名 | ✓ | `Crawler.RunState` → `CrawlContext` |
| P3-5 顶层函数命名 | 保留 | `reduce` 与 stdlib 的 `Iterable.reduce` 不构成重载冲突，改名会牵动测试；已在 KDoc 说明用途 |
| P3-3 巨型类拆分 | 未做 | `Crawler`(1194) / `HomeFragment`(1050+) / `WeiboApi`(848) 的按职责拆分涉及大量跨文件引用，属独立一轮重构；在没有编译验证的环境里动它风险大于收益 |
| D-1～D-9 文档同步 | ✓ | README / `ui-system-v2.0.md` / `topic-fields.md` / 审计文档 / 新增 `CHANGELOG.md` |
| E-1 Git 历史 / E-2 CHANGELOG | 部分 | CHANGELOG 已补；提交拆分需要人工按主题 `git add -p` 后逐条提交 |
| E-3 release 混淆 | 未做 | 需一次 R8 冒烟（缺设备/编译环境），建议单独一轮 |
| E-4 CI 未覆盖 PR | 未做 | 建议把 `.github/workflows/build-apk.yml` 的 `on:` 加 `pull_request` |
| E-5 测试覆盖偏斜 | 部分 | 新增 4 条置顶帖回归 + 2 条进度用例；`Crawler`/`WebAjax`/`GuestSession` 仍无测试（依赖真实网络，建议先抽出可注入的解析层） |
