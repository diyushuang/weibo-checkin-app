# 设计方案：单博主全量 / 增量抓取（v0.9.12）

> 对应需求：「图片抓取加入可选某一个博主单独进行全量或增量抓取」。
> 前置：`docs/design/interactive-crawl-design.md`（v0.9.11 浏览/选图）之后的抓取内核。
> 起草日期：2026-10-31　｜　状态：**已实施（v0.9.12，2026-10-31）**

## 实施回填（v0.9.12）

| 计划项 | 落地情况 |
| --- | --- |
| T1 范围收窄 | ✅ `AccountStore.selectTargets(list, uids)` 纯函数 —— 空集合 = 全部，保持列表顺序，未知 UID 静默忽略；`Crawler.runIncremental` / `runAlbum` / `crawlTarget` 全链路透传 `onlyUids` |
| T2 全量规划器 | ✅ `CrawlPlanner.planScan` + `nextScanPage`（纯函数）；`Budget.FULL`（页数 20）；`Phase.SCAN` / `CrawlPhase.SCAN` |
| T3 断点 | ✅ `CrawlCursor.fullScanPageHint`（JSON `scanPage`），与增量 `anchorPageHint`（`hint`）分开存 |
| T4 执行 | ✅ `Crawler.crawlFullScan`；相册模式全量 = 每轮扫 20 页（`ALBUM_FULL_PAGES`）而非 8 页 |
| T5 UI | ✅ 目标行 ⋮ 菜单 `action_target_incremental` / `action_target_full`；运行卡 `btnCrawlFull`「全量抓取（全部目标）」+ 确认弹窗 |
| T6 回归 | ✅ `testDebugUnitTest` 全绿（新增 11 例）；版本 0.9.12 / 26 |
| 与设计的偏差 | ① 「跳过 RETRY」的范围比原计划更宽 —— 原计划只让全量跳过，实现上**单博主增量也跳过**（见 §三.3）；② 相册模式没有照搬 `planScan`，因为相册墙游标本来就是「从头往下的位置」，只把每轮页数从 8 提到 20 就等价于全量 |

---

## 一、要解决的问题

增量的目标只有一个：**跟上水线**。它沿着时间锚点往下补，一旦「本页全部已处理 且
已回到已覆盖区间」就停 —— 这个判定在三种情况下会误判，留下永久性的空洞：

1. **旧版本按连续计数判定**：更早的实现用「连续 N 帖都已处理」当停止条件，
   中间夹一帖新的就把计数打断，后面的历史再也走不到。
2. **置顶帖绕过判定**：置顶帖的 `createdAt` 很老却永远在第一页，
   `planNew` 为它单独做了 `pinned` 旁路；绕过判定的同时也就绕过了「它属于哪段区间」的记录。
3. **中途断掉**：预算耗尽、用户点停止、翻页失败后跳到下一页重试成功 ——
   游标的覆盖区间只写「我以为走过的」，没走的部分不会补。

这些缺口增量**永远不回头**（回头就得重扫全部历史，代价不可接受），所以需要一条
显式的「我不信区间、只信索引终态」的链路。这就是全量。

## 二、为什么是「连续下行 + 只信终态」

`planScan` 与 `planNew` / `planHistory` 的取舍差别只有两条：

| | 增量（planNew / planHistory） | 全量（planScan） |
| --- | --- | --- |
| 停止条件 | 回到已覆盖区间 / 锚点 / 起始日期 | 空页 / 起始日期 / 预算 / 200 页硬墙 |
| 帖子取舍 | 时间在新旧水线之间才进批次 | 只要索引里不是**终态**就进批次 |
| 断点 | 时间锚点 `(postId, createdAt)` | 页码 `scanPage` |

不去「只扫已知空洞的页」是因为：想知道哪页有空洞，就得先有一份完整的地图 ——
而地图正是缺的东西。连续下行的代价只是列表请求数随深度线性增长；
终态帖不拉详情、不重复下载，所以真实成本远低于「重抓一遍」。

## 三、四个关键决策

### 1. 全量断点用页码，增量断点用时间锚点

增量必须用锚点：它只补一段区间，页码会被新帖插入整体下移、被删帖上移，
当搜索起点可以，当断点会漏。全量则是**单向连续下行**，页码就是位置本身：
新帖插入最多让一页重复扫（幂等，终态跳过），删帖最多少扫一页（下一轮继续下行会补上），
都不会漏一段。

两者必须**分开存**（`hint` vs `scanPage`）：复用同一个字段会让「跑完一轮全量」
把增量的锚点覆盖成 0，下一轮增量就从第 1 页重走。`fullScanPageHint` 缺省 0 =
没有进行中的全量扫描，语义上干净。

### 2. 预算只放宽页数

`Budget.FULL = Budget(maxPages = 20)`：详情数 / 文件数 / 25 分钟墙与 `Budget.NEW` 一致。
理由是一次全量可能覆盖几千帖，若同步放宽文件数与时间墙，点一下就把手机跑几小时，
用户既看不到进度也不好中途判断。20 页 ≈ 100~150 帖，跑不完下一轮从下一页续，
体验上是「可中断的推进」而不是「一次性长任务」。

### 3. 单博主与全量都跳过 RETRY

索引里的「待重试」队列按 `treeTag`（相册目录）共享，`PostRec` **没有 uid 字段**。
所以一旦允许 RETRY 阶段跑起来，它会把别的博主的失败帖一并重试 ——
在「只抓这个博主」的语义下这是越界。选择跳过而不是给索引加 uid：
加字段要迁移已存在的 NDJSON 索引，收益只是省一次点击（需要补失败时右上角
「待重试 N 帖」入口本来就在）。**宁可少做，也不偷偷扩大范围。**

### 4. 相册模式不引入 planScan

相册墙的游标 `since_id` 本身就是「从头往下的位置」，`crawlAlbumTarget` 每轮
从存档游标扫 `historyPages` 页。全量与增量的唯一区别就是走多深（8 → 20 页），
没有第二套状态机可写。时间线模式的区间/锚点/覆盖概念在相册模式里根本不存在。

## 四、语义边界

- **起始日期对全量同样是硬下界**：`planScan` 遇到 `p.at < sinceSec` 直接跳过，
  不会因为「全量」就把用户设的日期边界踩掉。
- **纯文字帖不计入批次**：`imageBearingOriginal == false` 的帖没有图可抓，
  进批次只会白拉一次详情。
- **翻页失败不跳过**：`PAGE_ERROR` 时 `nextScanPage = lastPage + 1`，
  失败页本身留在下次重试。跳过去才是真的漏。
- **HARD_CAP 视为完成**：`endPage >= MAX_PAGES(200)` 时 `nextScanPage = 0`，
  200 页 ≈ 数千帖，再往下走基本是已删内容或超出索引价值。

## 五、测试锚点

`app/src/test/java/com/wcm/checkin/CrawlPlannerTest.kt` 的全量段 7 例钉住了上面每一条：
`scan_doesNotStopAtAlreadyCovered` / `scan_collectsPendingPostsAscendingAndRecordsCoverage` /
`scan_resumesFromScanPageHint` / `scan_stopsAtSinceDate` /
`scan_pageErrorKeepsHintAtFailedPage` / `scan_firstPageEmptyMeansReachedBottom` /
`scan_skipsTextOnlyPosts`。
`CrawlCursorTest` 3 例钉住序列化互不干扰，`AccountStorePureTest` 3 例钉住范围收窄。
