# 设计方案：交互式抓图（博主预览 · 单图抓取 · 批量勾选）

> 对应需求：① 预览指定博主微博内容；② 抓取微博中指定的某一张图；③ 批量勾选博主原创微博中的多张图抓取。
> 前置调研：`docs/research/weibo-guest-endpoint-audit.md`（接口档位）、`docs/research/batch-image-crawl-and-ui.md`（同类项目与存储基线）。
> 起草日期：2026-10-31　｜　状态：**已实施（v0.9.11，2026-10-31）**

## 实施回填（v0.9.11）

| 计划项 | 落地情况 |
| --- | --- |
| T1 API 缩略图 | ✅ `RawPost.thumbs` + `fetchUserPosts` 解析 `pics[]` + `thumbnailUrl()` + `sanitizeThumbUrl()`（http→https 强制、白名单外丢弃） |
| T2 内核 | ✅ `Crawler.PickTask`（picIds=null=整帖）+ `slotsForPics()` + `crawlPicked()`（返回 `PickedSummary`）+ `CrawlRunner.runPicked()`（独立闸门实现，透出汇总）；槽位函数开放为 internal |
| P2 选图页 | ✅ `PostPickActivity` + `item_pick_pic` + `menu_pick_actions`；全屏远程预览改为**选图页内黑底 Dialog**（复用 `ZoomableImageView` + 「只下这张」），不改 `ImagePreviewActivity` —— 它与本地文件/删除/分享深度耦合，加远程模式分支爆炸 |
| P3 浏览页 | ✅ `BrowsingActivity` + `item_browse_post`：UID 输入 + 目标 chips + 原创/有图筛选 + 九宫格 + 触底翻页 + 长按跨帖多选；入口接在 `fragment_gallery` 抓取运行卡（`btnBrowseAuthor`，Secondary） |
| P4 回归 | ✅ `testDebugUnitTest` 全绿（新增 `PickCrawlTest` 15 例）；`lintDebug` 见 CI；版本 0.9.11 / 25 |
| 与设计的偏差 | ① 全屏预览载体从「改造 ImagePreviewActivity」改为「选图页内 Dialog」（理由如上）；② `runPicked` 直接返回 `PickedSummary?`（null=Busy）而非 `RunResult`，因为选图页/浏览页没有日志面板，需要 saved/failed 计数做结果 toast；③ 批量整帖任务用 `PickTask(bid, null)`，避免了「浏览页估张数」的不可靠口径 |

---

## 一、GitHub 调研结论

### 1.1 直接对标「浏览 + 选图 + 下载」的项目

| 项目 | 形态 | 语言 | 维护状态 | 选择性下载能力 | 对本项目的价值 |
| --- | --- | --- | --- | --- | --- |
| [`gbandszxc/weibo-image-downloader`](https://github.com/gbandszxc/weibo-image-downloader) | Tampermonkey 脚本（微博/X Web 端） | TypeScript | **活跃**（v1.5.7，2026-10） | ✅ **长按下载按钮展开选图弹窗**：媒体项以同源缩略图回显（微博取 `orj360`），Live 带 `LIVE` 角标、GIF 带 `GIF` 角标；**超 9 项按四列平铺滚动**，不遗漏列表页被 `+N` 遮罩的图；缩略图仅预览，**下载仍走原图** | ★★★★★ 需求②③的交互范式直接对标：九宫格缩略图 + 角标 + 勾选。其「列表缩略图与详情以详情接口为准」「文件命名含帖子 ID」两个细节同样适用 |
| [`just4fun/sina-weibo-album-downloader`](https://github.com/just4fun/sina-weibo-album-downloader) | Chrome 扩展 | TS | 2026-05 | ⚠️ 粗粒度：抓全量链接后**按年月分组**，用户选日期范围批量下载；无单张选择 | ★★★☆☆ 验证了「先枚举链接→再选择性下载」的两段式流程；LivePhoto 成对文件（.jpg+.mov）注入 Apple 元数据的做法可对照本项目 `LivePhotoConverter` |
| [`lw900925/weibod`](https://github.com/lw900925/weibod) | Java jar + 本机 Web UI | Java | 2026-04 | ❌ 只有「指定 uid 全量 / 只 Live Photo / 增量」 | ★★☆☆☆ 与本项目增量抓取能力重叠，无浏览与选图；无新增可借鉴点 |
| [`yukinotech/WeiboDL`](https://github.com/yukinotech/WeiboDL) | Tampermonkey（相册页） | TS | 2025-08 | ❌ 全量抓链接生成 txt 交给 IDM | ★☆☆☆☆ 无交互，仅确认相册链路可行性 |
| [`NO-ob/LoliSnatcher_Droid`](https://github.com/NO-ob/LoliSnatcher_Droid) | **Android**（Flutter，booru 图站客户端） | Dart | 活跃 | ✅ 浏览流中长按勾选 → 批量入队下载；队列 + 限速 + exists/failed/cancelled 三态 | ★★★★☆ Android 端「浏览→勾选→批量下载」交互与队列管理的最佳参照（已在 batch-image-crawl 调研中记录） |
| [`deckerst/aves`](https://github.com/deckerst/aves) | **Android** 相册 | Dart/Kotlin | 活跃 | ✅ 网格多选 + 批处理（分享/删除/移动） | ★★★☆☆ 多选模式（长按进入、顶栏计数、底栏主行动）的交互参照 |

### 1.2 关键结论

1. **没有可直接复用的 Android 端「微博浏览 + 选图抓取」开源实现。** 微博侧工具集中在 Web 脚本 / 桌面 CLI / Chrome 扩展；Android 端同类是 booru/画廊客户端（LoliSnatcher、EhViewer），不覆盖微博接口。本项目已有完整的接口知识与抓取内核，**方案 = 复用内核 + 自研两个页面**，而非引入外部项目。
2. **接口层无新增风险**：浏览与选图用的都是已接入且实测过的端口 —— 列表 `m.weibo.cn 107603<uid>`（账号）、详情 `m.weibo.cn/statuses/show`（账号）、缩略图与原图 `wx*.sinaimg.cn`（L0 匿名、host 白名单内）。`docs/research/weibo-guest-endpoint-audit.md` §6 已定论：这条链路**必须账号登录**，游客身份无替代，设计不绕开这一点。
3. **落盘层能力已存在**：`Crawler.savePost(..., onlySlots: Set<Int>?)`（`Crawler.kt:973`）本来就支持「只落指定槽位」，当前仅用于失败补抓（RETRY 阶段）。**单图/多图抓取就是给它加一个新调用方**，三层去重（文件名 / picId / 内容哈希）与 `CrawlIndex` 全部照常生效。
4. **交互口径以 gbandszxc 脚本为参照**：缩略图用 `orj360` 尺寸（sinaimg CDN，L0 免登录），预览与下载分离；Live/GIF 在网格上打角标，避免「勾了才发现下载的是动图」。

---

## 二、现状盘点（可复用能力 → 缺口）

| 能力 | 现状 | 位置 | 缺口 |
| --- | --- | --- | --- |
| 拉博主微博列表 | `fetchUserPosts(cookie, uid, page)`，每页 10 条，`RawPost(id,bid,createdAt,pinned,isRetweet,picNum,text)` | `WeiboApi.kt:784` | **`RawPost` 未解析列表卡片里的 `pics[]` 缩略图**（`mblog.pics[].url` 是 orj360 尺寸），预览页拿不到图 |
| 拉帖子详情 | `fetchPostDetail(cookie, bid)` → 完整 `pic_ids` + `pics[]`（含 `videoSrc` Live 配对）+ 帖级 `live_photo` | `WeiboApi.kt:871` | 无缺口 |
| 按槽位落盘 | `savePost(..., onlySlots)`：未选槽位跳过、失败槽位继承 | `Crawler.kt:973` | 无缺口，需要新的公开入口 |
| 槽位编码 | 静图 `i*2`、配对 Live `i*2+1`、帖级 Live `1000+n` | `Crawler.kt:160` | 需要一个「picId → 槽位」的映射函数 |
| 命名 | `prefixOf(时间, 博主)` + `AssetName.stillName(prefix, i+1)` | `Crawler.kt:953` | 勾选抓取必须**沿用同一命名**，才能与增量抓取在 L1 层互认 |
| 任务闸门 | `CrawlRunner.guarded`：单闸门 + `requestStop` + `CrawlProgress` 事件 | `CrawlRunner.kt:118` | 新增 `runPicked` 入口（复用 guarded） |
| 单条抓取 UI | 输入框（链接→bid / UID→拉最近 2 页）→ `showPostPicker` 纯文本单选 → 抓整条 | `GalleryFragment.kt:677` | 文本列表**无图无预览**、单选且粒度是「帖」，需升级为图形化浏览页 |
| 缩略图加载 | `ThumbLoader`：本地文件采样解码 + LruCache | `ThumbLoader.kt` | 需要远程版（OkHttp 拉字节 + 采样解码 + LruCache），不做磁盘缓存 |
| 全屏预览 | `ImagePreviewActivity` + `ZoomableImageView`（双指/双击缩放） | 现有 | 当前面向本地文件，需增加远程 URL 模式 |
| 限速 | `WeiboThrottle`（Mutex + delay，按 key） | `WeiboThrottle.kt` | 列表/详情请求直接走既有闸门；缩略图是 L0 CDN，不用节流但要限并发 |
| 安全 | CDN host 白名单（`sinaimg.cn` 等）+ 只允许 https | `Crawler`/README §安全策略 | 列表给的 `pics[].url` 可能是 `http://`，**必须强制 https 化** |

---

## 三、功能设计

三个功能共用一套「浏览 → 选图 → 抓取」链路，拆两个新页面：

```
图片页 ──「浏览博主」──▶ BrowsingActivity（F1 博主预览）
                              │  列表卡片：正文 + 九宫格缩略图
                              │  筛选：全部 / 原创 / 有图；下拉翻页
                              ▼
                        PostPickActivity（F2+F3 选图页）
                              │  全量 pic_ids 九宫格，角标 LIVE/GIF
                              │  单击勾选 · 全选 · 长按全屏预览
                              ▼
                     「下载选中 N 张」→ CrawlRunner.runPicked
                              │  单闸门 · 可停止 · 结构化进度
                              ▼
                     savePost(onlySlots) —— 与增量抓取同一落盘/去重/命名
```

### F1 · 博主预览页（`BrowsingActivity`，新建）

- **入口**：图片页现有「单条抓取」按钮升级为两个入口——「浏览博主抓图」（新主入口）与「粘贴链接抓整条」（保留原对话框，直达 `runSingle`）。
- **博主指定**：顶部输入 UID / 主页链接 / 个性域名（复用 `WeiboApi.resolveUid`）；下拉框带出**已添加的抓取目标**直接切换（省去重复输入）。
- **列表数据**：`fetchUserPosts` 逐页加载（每页 10 条，触底续拉 + 顶部下拉刷新），请求走 `WeiboThrottle`；身份用 `Crawler.pickCrawlCookie`（账号，与抓图同一份）。
- **筛选**：`全部 / 原创 / 有图` 三个 chip；「原创」= `!isRetweet`（客户端过滤，m 站列表接口没有 feature 参数）。默认「原创 + 有图」——对齐需求③的口径。
- **卡片展示**：正文摘要（已有 `summarize`）+ 九宫格缩略图（3 列 `GridLayout`，上限 9 张，超出显示「+N」，N 来自 `picNum`）；置顶/转发/图数标记沿用现有 `showPostPicker` 的文案体系。卡片上**已下载过的帖子不打标记**（保持只读浏览，不做索引反查——见 §6 取舍）。
- **缩略图**：远程加载器（§四 T4），内存 LruCache（`maxMemory/8`），失败显示占位不重试（列表图太多，重试交给用户下拉刷新）。
- **点卡片** → 进入选图页；**长按卡片** → 直接「抓该帖全部」（沿用 `runSingle`，老用户习惯不变）。

### F2 · 单图抓取（`PostPickActivity` 承载）

- 进入时拉一次详情（`fetchPostDetail`），以 `pic_ids` 为权威清单——天然解决列表 9 图截断问题（audit 文档 §8 的 18 图口径）。
- 网格 2 列（大图易辨认），每张右上角勾选框，左下角标：`实况`（`pics[].type == "livephoto"`，勾选后落 GIF/mov）、`GIF`、`视频`不列出（视频帖不可抓，与现有「无图（或为视频微博）」口径一致）。
- **单击 = 勾选/取消**；点「预览」图标或长按图 = 全屏远程预览（`ImagePreviewActivity` 远程模式，可缩放），预览页内提供「只下这张」快捷按钮——这是需求②的最短路径。
- 底部单一主行动（对齐 M3「一页一个高强调按钮」，batch-image-crawl 调研 S20 结论）：「下载选中 N 张」；N=0 时置灰。
- **单张落盘语义**：勾第 i 张 → 若是 Live 项映射到 `pairedLiveSlot(i)`，否则 `stillSlot(i)` → `savePost(onlySlots)`。文件名仍是 `AssetName.stillName(prefix, i+1)`，与「以后增量抓全量」完全同位——L1 文件名去重直接命中，**不会出现同一张图两份文件**。

### F3 · 批量勾选（跨帖）

- 两个层级的批量：
  1. **帖内批量**：选图页全选按钮 + 逐张勾选（一帖最多 18 图）。
  2. **跨帖批量**：浏览页多选模式——长按进入，卡片级勾选（整帖全图），顶栏显示「已选 N 帖 · M 张」；提交时若只想在某帖里再精选，进该帖的选图页二次勾选。
- 提交结构：`List<PickTask(bid, picIds)>`，一次任务内**逐帖顺序**处理（拉详情 → 过滤槽位 → 落盘），全程吃 `CrawlRunner.guarded` 闸门 + `requestStop` 停止信号 + `CrawlEvent.Post` 进度事件，图库页现有运行卡无需改动即可展示。
- 跨帖去重天然成立：同一 picId 在两帖出现（转发动机，但原创集合也可能同图重发）时，L2 picId 索引在第二次直接判 EXISTS 跳过，零成本。

---

## 四、技术方案

### T1 · API 层：列表缩略图（`WeiboApi.kt`）

- `RawPost` 增加两个字段：
  ```kotlin
  data class RawPost(
      ...,
      val thumbs: List<String> = emptyList(),   // orj360 缩略图 URL（强制 https，≤9 张，列表截断口径）
      val picTotal: Int = 0,                    // cardPicNum 的值，卡片「+N」用
  )
  ```
- `fetchUserPosts` 解析 `mblog.pics[]`：取 `url`（通常 `orj360`；GIF 保留 `.gif` 后缀），`http://` → `https://`，host 校验入白名单（`sinaimg.cn` 系）才收，否则丢弃该张（宁缺毋滥，对齐安全策略）。
- 新增 `fun thumbnailUrl(pid: String, size: String = "orj360")`：与 `originalImageUrl` 相同的 CRC32 host 轮转 + `/thumbnail|orj360|mw690/` 尺寸段，供详情页兜底拼图。

### T2 · 内核：勾选抓取入口（`Crawler.kt` + `CrawlRunner.kt`）

```kotlin
// Crawler.kt 新增
data class PickTask(val bid: String, val picIds: Set<String>)

suspend fun crawlPicked(ctx: Context, tasks: List<PickTask>, log: (String) -> Unit) {
    // cookie/目录检查与 crawlSinglePost 相同
    withCrawlContext(ctx, treeUriRaw, log) { state ->
        for (task in tasks) {
            if (isStopped()) break                      // 闸门停止信号透传
            val detail = WeiboApi.fetchPostDetail(cookie, task.bid)   // 失败记日志继续下一帖
            val assets = assetsFromDetail(detail)
            val slots = buildSet {
                task.picIds.forEach { pid ->
                    val i = assets.picIds.indexOf(pid)
                    if (i >= 0) add(if (assets.liveByIndex.containsKey(i)) pairedLiveSlot(i) else stillSlot(i))
                }
            }
            if (slots.isEmpty()) continue
            // prefix/label/epoch 解析与 processPost 相同（resolveLabel 保持博主名一致性）
            savePost(ctx, state, prefix, postId, at, assets, slots, log)
        }
    }
}
// CrawlRunner.kt 新增
suspend fun runPicked(ctx: Context, tasks: List<PickTask>, log: (String) -> Unit): RunResult =
    guarded("勾选抓取") { _, stopped -> Crawler.crawlPicked(ctx, tasks, log) }
```

要点：
- **不发新请求策略**：`savePost` 内三层去重原样生效；`onlySlots` 语义（未选槽位跳过、失败槽位继承）已在，`crawlPicked` 只是把 picId 翻译成槽位。
- **帖级 `live_photo`（extraLive）不参与勾选**：它没有对应的 picId，无法在网格上呈现；保持「勾选=图」的直觉，独立 Live 仍只由增量/整帖抓取负责。
- 单测：`picId→slot` 映射（含 Live 项）、`slots` 为空跳过、`thumbs` 的 https 化与白名单过滤，进 `CrawlPlannerTest` 同级的纯函数测试。

### T3 · UI：两个页面（自研，零新依赖）

| 项 | 方案 |
| --- | --- |
| 布局 | `activity_browsing.xml`（顶部输入 + chip 行 + RecyclerView）、`activity_post_pick.xml`（顶栏全选 + 2 列网格 + 底部主行动）；全部走 `styles.xml` 令牌（`Widget.Wcm.Card.*` / `Button.Primary` / `touch_min=48dp`），对齐 v2.0 UI 系统 |
| 网格 | 帖子页 2 列 `GridLayoutManager`；浏览页卡片内 3 列 `GridLayout`（静态 ≤9 格，无需 RecyclerView 嵌套滚动问题） |
| 远程缩略图 | 新 `RemoteThumb.kt`：OkHttp（复用 `WeiboApi.client`）拉字节 → `BitmapFactory` 采样解码 → `LruCache(maxMemory/8)`；协程 `Semaphore(4)` 限并发；视图复用时先 cancel（tag 绑定 url 防错位） |
| 全屏预览 | `ImagePreviewActivity` 增加远程模式：extra 传 URL，加载复用 T3 缩略图器（不采样）+ 已有 `ZoomableImageView` |
| 进度 | `runPicked` 发 `CrawlEvent`，图库页运行卡零改动复用 |

### T4 · 限速与安全

- 列表翻页 / 详情：`WeiboThrottle.await`（既有闸门），与增量抓取同口径。
- 缩略图：CDN L0 免登录，不做节流；并发上限 4；**强制 https + host 白名单**（非法 host 丢弃），对齐 README §安全策略。
- 与增量任务并发：`guarded` 闸门保证同一时刻只有一个抓取任务；浏览页只读接口（列表/详情/缩略图）不占闸门，可与增量并行跑——但详情接口与增量共用限速闸，天然错峰。

---

## 五、实施计划

| 阶段 | 内容 | 工作量 | 交付判据 |
| --- | --- | --- | --- |
| P1 | T1 + T2（API 扩展 + `crawlPicked` + 单测） | 0.5 天 | `testDebugUnitTest` 绿；`crawlPicked` 对单帖 1 张/全张/Live 张跑通 |
| P2 | F2 选图页 + 全屏远程预览 | 1.5 天 | 真机：18 图帖全量显示；勾 1 张只落 1 个文件且命名同增量 |
| P3 | F1 浏览页 + 跨帖多选 + 接线「浏览博主抓图」入口 | 1.5 天 | 翻页/筛选/长按整帖/跨帖批量；中断续跑不产生 ` (1)` 副本 |
| P4 | 回归（增量抓取、单条抓取、去重、CHANGELOG/README） | 0.5 天 | `testDebugUnitTest assembleDebug` + `lintDebug` 绿 |

风险与对策：
1. **列表 `pics[]` 缺失/非 orj360**（部分老帖只给 mw690 或无 pics）→ 缩略图缺失显示占位，进入选图页以详情为准；不做列表级补拉（请求量不可控）。
2. **Live 勾选的预期管理**：角标 + 提示文案「实况图将保存为动图」；GIF 转换开关（设置页现有项）继续生效，失败回退 mov + 封面（既有逻辑）。
3. **浏览页请求量**：一页列表 + 每帖详情各一次；默认只对「点开的帖子」拉详情，列表不预取——总请求 ≈ 用户浏览量，不会放大频控压力。

---

## 六、有意不做（本轮取舍）

| 项 | 理由 |
| --- | --- |
| WebView 加载博主主页并注入下载 | 注入 JS 桥脆弱且要扩导航白名单，违背登录 WebView 的安全收敛方向；「浏览体验」由原生列表承担 |
| 浏览页显示「已下载」角标 | 需要 picId→索引反查；等 T2 落地后若用户需要再加（`CrawlIndex` 已有 `hasPic` 能力，改造成本低，纯 UI 增强） |
| 相册墙（游客）浏览模式 | 图墙只有 mid 无正文，预览价值低于 107603 列表；且 v0.9.8 相册模式已覆盖「全量抓」，本轮聚焦交互选图 |
| 转发帖的图 | 需求口径是「博主原创微博」；转发帖跳过（卡片标注即可） |
