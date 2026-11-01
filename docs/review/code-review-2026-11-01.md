# 全项目代码审查报告（v0.9.12 / HEAD 15c0c94）

- 审查日期：2026-11-01
- 审查对象：`app/src/main/java/com/wcm/checkin/` 全部 39 个主源码文件 + 17 个单测类
- 审查方式：6 路平行只读审查（UI/生命周期、存储/IO、网络/API、抓取进度模型、图库浏览、登录/Worker）+ 主线逐段精读复核，**所有结论都回到源码行号逐条核对过**
- 基线（审查前实测）
  - `./gradlew --offline :app:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL，239 tests / 0 failures / 0 ignored
  - `./gradlew --offline :app:lintDebug` → BUILD SUCCESSFUL，lint-results-debug.txt 内容为 `No issues found.`
  - 编译警告仅 2 条：`Crawler.kt:1346:9 Parameter 'ctx' is never used`、`GifEncoder.kt:184:13 Variable 'h' is never used`
  - 工作区干净（无未提交修改），审查全程只读

## 修复状态（2026-11-01 补记）

本报告列出的问题已按「修复批次建议」全部落地：**P0 × 4、P1 全部、P2 全部**，P3 15 条中 14 条已处理、
P3-3（三段翻页循环抽模板）经评估后**刻意不做**（三处判据已在 BUG-003 修复后完全一致，抽模板属于纯重构，
收益不抵回归风险）。逐条的「状态」行见各条正文，P2/P3 见本节末尾的统一说明。

验证（串行执行，两条命令不可并行 —— 共享 build 目录会互相破坏）：

~~~bash
./gradlew --offline :app:testDebugUnitTest --rerun-tasks   # BUILD SUCCESSFUL，248 tests / 0 failures
./gradlew --offline :app:lintDebug                         # BUILD SUCCESSFUL，No issues found.
~~~

编译警告由 2 条降为 0 条。源码改动全部集中在 `app/src/main/java/com/wcm/checkin/` 与 `app/src/main/res/values/strings.xml`，
另有 4 个单测类新增回归用例（CrawlPlannerTest / CookieLifeTest / CrawlIndexTest / AccountStorePureTest）。

> 注：本报告首版对 BUG-016（空页判到底）给出的回归用例只写到「两页为空 → 第一次不置位、第二次置位」，
> 落地时补成了更严的三条（单页空不成底、断点续跑跨过空页继续翻、预算用尽保留 lastPage），
> 其中两条还暴露了首版用例自身的假设错误（旧用例把「预算 5 页」配「只有 3 页数据」，第 4、5 页的空响应
> 正好凑成两连空，反而会被正确地判到底）—— 现已在用例里把预算卡到第 3 页并显式断言 `StopReason.BUDGET`。

## 严重度定义

| 级别 | 判据 |
|---|---|
| **P0** | 数据永久损坏 / 静默丢失，且不会自愈；或核心功能必现失效 |
| **P1** | 特定条件下崩溃、ANR、永久性数据缺失，或核心业务判定错误 |
| **P2** | 功能缺陷、体验/一致性问题、并发竞态但可自愈，或明确的资源浪费 |
| **P3** | 代码质量、可维护性、微性能、文档与注释失真 |

统计：**P0 × 4、P1 × 16、P2 × 36、P3 × 15**，另附「已核查无问题」清单，避免后续重复排查。

---

# P0：数据静默丢失 / 核心流程必现失效

## BUG-001 相册模式提前中断仍推进 page 游标 → 本页剩余微博永久丢失

- **位置**：`Crawler.kt:653-676`（主循环）、`:696`（落盘）
- **现象**：相册抓取在「本页还没处理完」时中断，游标却已推进到下一页。日志里那句「下轮重试」是假的——这些 mid 再也不会被访问。
- **触发条件**：以下任一在本页 `for (mid in page.mids)` 循环中途命中：
  1. 用户点停止（`isStopped()`）
  2. 单次抓取上限 `handled >= maxPosts`（`ALBUM_MAX_POSTS = 40`）
  3. 时间墙 `System.currentTimeMillis() - startedAt > ALBUM_MAX_MILLIS`（20 分钟）
- **根因**：

  ~~~kotlin
  for (mid in page.mids) {
      if (isStopped() || handled >= maxPosts) break        // ← 提前退出，本页剩余 mid 未处理
      if (now - startedAt > ALBUM_MAX_MILLIS) break
      ...
  }
  pages++
  if (page.nextSinceId.isEmpty() || !seen.add(page.nextSinceId)) return AlbumPass(..., "", true)
  cursor = page.nextSinceId                              // ← 无论如何都推进
  ~~~

  `nextSinceId` 是「本页最后一条」的位置，但本页并未走完。
- **影响**：一页 10-20 条微博的图片永久缺失，且不属于任何 failed 槽位、不进 RETRY、UI 无提示。40 条上限 + 20 分钟时间墙意味着**大账号每次跑相册模式都在丢尾部**。
- **修复方案**：区分「本页走完」与「本页中断」：

  ~~~kotlin
  var pageDone = true
  for (mid in page.mids) {
      if (isStopped() || handled >= maxPosts || now - startedAt > ALBUM_MAX_MILLIS) { pageDone = false; break }
      ...
  }
  pages++
  if (!pageDone) return AlbumPass(enumerated, saved, cursor, false)   // 游标回退为本页起始 cursor
  cursor = page.nextSinceId
  ~~~

- **验证**：单测让 `maxPosts` 在第 1 页第 3 条命中，断言返回的 `nextCursor` 仍是本页起始游标；真机用 40+ 条的账号跑一次相册模式，对比微博计数。
- **状态**：✅ 已修复（2026-11-01）

## BUG-002 抓详情失败不落索引，而断点按「页」推进 → 该帖永久空洞

- **位置**：`Crawler.kt:1156-1162`（`processPost`）、`:769-773`（`processAlbumPost`）
- **现象**：某条微博详情接口失败（432 / 超时 / `ok:-100`），日志只打一行「✗ xxx 详情失败」，索引里既没有 DONE 也没有 OPEN 记录。
- **根因**：

  ~~~kotlin
  val detail = try { WeiboApi.fetchPostDetail(...) } catch (e: Exception) {
      log("  ✗ ~DL~postId 详情失败：~DL~{e.message}")
      onEvent(CrawlEvent.Post(saved = 0, failed = 0))
      return 0                       // ← 无 onPostSealed / 无 onVoid，索引完全不知道这帖存在
  }
  ~~~

  而断点推进只看「页」：`processedA / processedB` 只数循环次数（`Crawler.kt:966-982`、`:1001-1009`），不看 `processPost` 返回值；`CrawlCursor.afterHistoryPhase` 直接把 `anchorAt` 推到最后页最老帖（`CrawlCursor.kt:192-212`）。
- **影响**：失败帖落在覆盖区间内部，`planNew` 判「已覆盖」直接跳过，`pendingRetries` 也捞不到（只捞 `failed` 非空的帖）。只有「全量扫描」能补，用户看到的日志一切正常。
- **修复方案**（建议两条都做）：
  1. 详情异常也落一条可重试记录（注意不能直接 `onPostSealed(..., failed = emptySet())`，那会被判 DONE，需要新增 `onPostUnknown(postId)` 或让 `expected = -1` 表示语义未知）；
  2. 推进游标前对 `plan.batch` 重跑 `index.isTerminal`，全部终态才调用 `afterNewPhase / afterHistoryPhase`。
- **验证**：单测注入必然抛异常的详情桩，跑两轮，断言第二轮仍会重新访问该 mid。
- **状态**：✅ 已修复（2026-11-01）

## BUG-003 历史锚点取到置顶帖时间 → 历史补抓永久卡死（不动点死循环）

- **位置**：`CrawlPlanner.kt:231`（锚点取值）、`:405-411`（`crossesAnchor`）；关联 `Crawler.kt:970-978`
- **现象**：Phase B（补历史）每轮「0 帖可下，照样抓 20 页列表」，日志循环打「B 补历史：0 帖」；`oldestCoveredAt` 再也不下降，第 21 页及更深的图永远抓不到。
- **根因**：同文件 `KDoc:43-47` 明确写了「置顶帖的时间不代表页面位置，会让历史阶段永久停摆」，`positionHint`（`:49-50`）与 `planNew`（`:133`）都排除了置顶，唯独锚点这一处漏了：

  ~~~kotlin
  // CrawlPlanner.kt:231
  val pageOldest = slice.posts.filter { it.at > 0L }.minByOrNull { it.at }!!   // ← 未排除 it.pinned
  anchorAt = pageOldest.at
  ~~~

  后果链：`anchorAt` 被写成置顶帖那天（如 2020-01）、`anchorPageHint` 停在 20 → `locateAnchor` 探 20/21/19/22/18/23/17 全无 `hasOlder`（真正的老帖在第 21 页之后）→ 返回 1 → 又从第 1 页走 20 页 → 置顶帖再次当选、游标状态完全一致 → **完全不动点**，每轮白烧 20 次列表请求且不报错。
- **触发入口**：(a) Phase A 停在第 1 页（第 2 页列表失败 → `planA.lastPage = 1`）；(b) 锚点漂移超过 `ANCHOR_HOPS = 3` 页。
- **影响**：历史图片补抓对含置顶帖的账号**整体失效**，且没有任何错误提示。
- **修复方案**：

  ~~~kotlin
  // :231
  val pageOldest = slice.posts.filter { it.at > 0L && !it.pinned }.minByOrNull { it.at }
  // :405-411 crossesAnchor 同样排除置顶
  slice.posts.any { !it.pinned && it.at >= cursor.anchorAt }
  ~~~

  另建议给 `afterHistoryPhase` 的 `anchorAt` 加「只能变老」单调约束（`min(old, new)`）。
- **验证**：新增回归单测——第 1 页含一条 2020 年的置顶帖 + 第 21 页有内容，断言 `anchorAt != 置顶帖时间`；真机跑一轮 Phase B，日志「B 补历史：>0 帖」。
- **状态**：✅ 已修复（2026-11-01）

## BUG-004 勾选抓取把整帖标记 DONE，未勾选槽位永不补

- **位置**：`Crawler.kt:1225-1227`（failed 初始集合）、`:1315`（`onPostSealed`），配合 `CrawlIndex.kt:330-341`
- **现象**：用户只勾了第 2、4 张，抓完后这条微博在索引里是 `DONE / expected = 全部张数`。之后任何增量/历史/全量扫描都用 `isTerminal` 跳过它，1、3、5 张永远不下载。
- **根因**：

  ~~~kotlin
  fun attempted(slot: Int) = onlySlots == null || slot in onlySlots
  if (onlySlots != null) failed.addAll(onlySlots)     // ← 只把「要抓的」塞进 failed
  ...
  state.index.onPostSealed(postId, at, assets.expectedFiles, saved > 0 || failed.isEmpty(), failed)
                                                  // expected 是全部张数，failed 为空 → DONE
  ~~~

  未勾选槽位既没下载、也不在 `failed` 里，于是 `failed.isEmpty()` 成立。
- **影响**：勾选抓取会**永久毒化**该帖的进度状态；`Crawler.kt:316-318` 注释承诺的「只抓勾选的，其余留待以后」不成立，与 BUG-002 叠加后可能整帖几图全缺。
- **修复方案**：把未勾选槽位记入 failed，或让 `expected` 按实际选择计（前者更简单）：

  ~~~kotlin
  val missing = if (onlySlots == null) emptySet() else
      (1..assets.expectedFiles).filter { it !in onlySlots }.toSet()
  state.index.onPostSealed(postId, at, assets.expectedFiles, saved > 0 || failed.isEmpty(), failed + missing)
  ~~~

- **验证**：单测 onlySlots = {2,4}，断言 `status != DONE` 且 `failedSlots` 含 1/3/5。
- **状态**：✅ 已修复（2026-11-01）

---

# P1：崩溃、ANR、永久性缺失与业务判定错误

## BUG-005 `BrowsingActivity`：主线程与 IO 线程同时修改同一个 ArrayList → CME 崩溃

- **位置**：`BrowsingActivity.kt:255`、`:310-324`、`:138-147`
- **现象**：浏览博主微博时点「原创」「带图」筛选 chip，偶发 `ConcurrentModificationException` 崩溃；或列表条目错位、`getItemCount` 与实际不符导致 `IndexOutOfBoundsException`。
- **根因**：`posts / visible` 没有线程归属约定：
  - IO 线程（`AppScope`）：`fetchOnePage:255` 里 `posts.addAll(list); applyFilter()`
  - 主线程：`:138-147` chip 点击直接 `applyFilter()`（chip 从不禁用，全文无 `isEnabled = false`）
  - `applyFilter` 内部 `visible.clear(); for (p in posts) ... visible.add(p)` —— 一边 `posts.addAll` 一边迭代，必然可能抛 CME。

  附带 `:317` 在 IO 线程读非 `@Volatile` 的 `listBusy`，主线程点 chip 时可能静默不刷新（用户以为筛选失效）。
- **影响**：用户可见崩溃；即使不崩，列表与筛选状态也会错乱。
- **修复方案**（推荐 A）：
  - A. 数据归主线程：IO 只返回 `List`，`withContext(Dispatchers.Main) { posts.addAll(it); applyFilter() }`；`loadMore` 的 `onListArrived` 已有 Main 切换，合并即可。
  - B. 快速止血：对 `posts / visible` 的所有访问加同一把锁，`listBusy` 加 `@Volatile`。
- **验证**：`startBrowse()` 后立刻连点两个 chip，反复 20 次不崩。
- **状态**：✅ 已修复（2026-11-01）

## BUG-006 `GalleryBrowseActivity`：DiffUtil 在主线程同步计算 → 大库 ANR

- **位置**：`GalleryBrowseActivity.kt:622-641`（`submit`），调用点 `:320-333`（`applyComputed`），上游 `:299-318`（`rebuild`）
- **现象**：10 万张规模的目录里切换排序/分组/筛选，或在搜索框里每敲一个字，主线程卡住数秒到数十秒，可能 ANR。
- **根因**：v0.9.9 的「重建移出主线程」只搬走了 `sortRows / groupBy`，最贵的两步仍在主线程：

  ~~~kotlin
  private fun applyComputed(page: Computed) {          // ← lifecycleScope.launch 默认 Main
      val items = ArrayList<GridItem>(page.sorted.size + ...)
      page.sorted.forEach { items.add(GridItem.Cell(it)) }   // ← O(n) 主线程堆对象
      gridAdapter.submit(items)                              // ← 内部 DiffUtil.calculateDiff 同步
  }
  fun submit(list: List<GridItem>) {
      val diff = DiffUtil.calculateDiff(...)                // ← Myers O(n·D)，排序变化时 D≈n
      items.clear(); items.addAll(list); diff.dispatchUpdatesTo(this)
  }
  ~~~

  `:620` 注释假设「n ≤ 几百，可忽略」，与同文件 `:296`「10 万张的库」的前提直接矛盾。
- **影响**：图库页搜索框卡顿到不可用；切换排序大概率 ANR。
- **修复方案**：摊平也放进 `Dispatchers.Default`（分组标题文案一并后台拼），主线程只 `dispatchUpdatesTo`；并加规模闸门 `if (list.size > 5000) { items.clear(); items.addAll(list); notifyDataSetChanged() } else DiffUtil...`（超大列表逐条动画无意义）。
- **验证**：造 5 万行假 `GalleryFiles.Row`，断言 `calculateDiff` 不在主线程执行（可用 `Looper.myLooper()` 断言或加计时日志）。
- **状态**：✅ 已修复（2026-11-01）

## BUG-007 `AccountStore.mergeAccounts` 让快照无条件覆盖 cookie / lastResult → 前台重登被回退

- **位置**：`AccountStore.kt:159-178`（`mergeAccounts`）、`:181-183`（`mergeSave`）；调用点 `CheckInWorker.kt:79`、`HomeFragment.kt:982`
- **现象**：后台保活 Worker 跑着的同时用户在前台重新登录，Worker 结束时把**旧快照里的旧 cookie** 写回账号表 → 用户立刻「掉登录」；「上次结果」也回退成 Worker 启动时的文案（例如刚跑完的「签到 3/3 · 12:10」变回「后台保活 ✓」）。
- **根因**：

  ~~~kotlin
  cur.copy(
      cookie = snap.cookie,               // ← 无条件快照胜出
      lastResult = snap.lastResult,       // ← 同上
      name = if (needsNameResolve(cur) && !needsNameResolve(snap)) snap.name else cur.name,
      cookieIssuedAt = maxOf(cur.cookieIssuedAt, snap.cookieIssuedAt),   // ← 新者胜
      cookieRotatedAt = maxOf(...), cookieSeenAt = maxOf(...), cookieSyncedAt = maxOf(...),
  )
  ~~~

  同一个 `copy()` 里，时间锚点用 `maxOf`（新者胜），cookie/lastResult 却用「快照胜」，方向自相矛盾；`:146-158` 的 KDoc 还写着「以 current 为准」。更糟的是 `cookieSeenAt` 用 `maxOf` 会把旧 cookie 的验证时间也保留，UI 显示「刚刚验证」掩盖了回退。
- **影响**：掉登录（用户需重新扫码）；CookieLife 过期预警失真。
- **修复方案**：按「谁新用谁」——比较两侧最新锚点：

  ~~~kotlin
  fun newest(a: Account): Long = maxOf(a.cookieRotatedAt, a.cookieSeenAt, a.cookieSyncedAt, a.cookieIssuedAt)
  val snapNewer = newest(snap) > newest(cur)
  cur.copy(
      cookie = if (snapNewer) snap.cookie else cur.cookie,
      lastResult = if (snapNewer) snap.lastResult else cur.lastResult,
      ...
  )
  ~~~

  更彻底：`mergeSave` 只回填白名单字段，cookie 永不由快照回写（Worker 已用 `patchAccount` 逐字段落盘）。
- **验证**：`AccountStorePureTest` 增加用例——current 的 `cookieRotatedAt` 更新，断言合并后 cookie 仍是 current 的。
- **状态**：✅ 已修复（2026-11-01）

## BUG-008 签到结果判定：`SUCCESS_WORDS` 无否定保护、`errno` 不参与判定 → 「签到未成功」被判成功

- **位置**：`WeiboApi.kt:616-626`（判定）、`:134`（`SUCCESS_WORDS`）
- **现象**：服务端返回 `ok=0 / errno!=0` 但提示文案含「成功」二字（如「签到未成功，请稍后重试」「领取失败，本次未成功」）时，App 判成功、不重试、界面显示已完成。连续多日漏签用户完全无感。
- **根因**：

  ~~~kotlin
  val ok = parseLoginFlag(payload.opt("ok")) || parseLoginFlag(payload.opt("result")) ||
           code == "100000" || SUCCESS_WORDS.any { msg.contains(it) }
  val errno = payload.optInt("errno", 0)      // :625  解析了却没用
  ~~~

  `SUCCESS_WORDS = listOf("成功", "已签到", "已簽到")`，`contains` 对否定句同样成立。
- **影响**：签到类 App 的核心结果判定错误；用户失去唯一的失败反馈信号。
- **修复方案**：

  ~~~kotlin
  val negative = listOf("未成功", "不成功", "没有成功", "失败", "未签到", "无法签到")
      .any { msg.contains(it) }
  val ok = (parseLoginFlag(payload.opt("ok")) || parseLoginFlag(payload.opt("result")) ||
            code == "100000" || SUCCESS_WORDS.any { msg.contains(it) }) &&
           errno == 0 && !negative
  ~~~

- **验证**：`WeiboApiPureTest` 增加用例：`{"errno":100001,"msg":"签到未成功"}` → false；`{"code":"100000","msg":"签到成功"}` → true。**改动前先用真实账号抓一次响应确认字段名**。
- **状态**：✅ 已修复（2026-11-01）

## BUG-009 落盘失败留下 0 字节文档 → 判定 EXISTS → 坏图永久留存

- **位置**：`Crawler.kt:93-107`（`TreeStore.save`）
- **现象**：抓取过程中偶发写入失败（存储满 / provider 抖动 / SAF 中断），相册里出现 0 字节或截断的 `.jpg`；重启后仍被判「已存在」，永远不再重下。
- **根因**：

  ~~~kotlin
  val docUri = DocumentsContract.createDocument(resolver, rootUri, mime, name) ?: return false
  resolver.openOutputStream(docUri)?.use { it.write(bytes) } ?: return false   // ← 返回 null 或 write 抛异常
  remember(name, bytes.size.toLong())                                        // ← 都不会执行
  true
  ~~~

  文档已由 `createDocument` 创建，写入失败时不回滚；下次 `loadExisting()` 把它收进 `canonicals`，`saveOne` 的 L1 判 `EXISTS`。
- **影响**：用户相册里的坏图永久存在（点开空白），该槽位永远算「已完成」。
- **修复方案**：

  ~~~kotlin
  val docUri = try { DocumentsContract.createDocument(resolver, rootUri, mime, name) } catch (e: Exception) { null }
      ?: return false
  val wrote = try {
      resolver.openOutputStream(docUri)?.use { it.write(bytes); true } ?: false
  } catch (e: Exception) { false }
  if (!wrote) { runCatching { DocumentsContract.deleteDocument(resolver, docUri) }; return false }
  remember(name, bytes.size.toLong()); true
  ~~~

- **验证**：单测用故意抛异常的 `OutputStream` 桩，断言 `deleteDocument` 被调用。
- **状态**：✅ 已修复（2026-11-01）

## BUG-010 抓取上下文资源清理不在 `try` 内 → 异常时索引句柄与内存映射泄漏

- **位置**：`Crawler.kt:838-900`（`withCrawlContext`）
- **现象**：`CrawlIndex` 的 `BufferedWriter` 与 companion 里的 `LIVE` 强引用集合不释放，长进程内反复抓取后内存与 fd 逐步增长；`LIVE` 还会让已关闭实例继续收到墓碑广播，产生难以复现的「幽灵回退」。
- **根因**：

  ~~~kotlin
  val store = TreeStore(...); store.loadExisting()
  val index = CrawlIndex.open(ctx.filesDir, treeTag)     // ← 分配了资源
  index.load(); index.migrateLegacy(...); Takeover.claimAll(...)
  try { block(...) }                                    // ← try 从 :889 才开始
  finally { index.maybeCompact(); index.flush(); index.close() }
  ~~~

  `open / load / migrateLegacy / claimAll` 任一抛异常（坏文件、磁盘满、`JSONException`）都不会走到 `close()`。
- **影响**：fd 泄漏 + `LIVE` 集合无限增长；`siblings()` 广播到已废弃实例。
- **修复方案**：把 `open..claimAll` 全部包进 `try`，`index` 声明为 `var index: CrawlIndex? = null`，`finally` 里 `index?.let { ... }`。
- **验证**：单测让 `load()` 抛异常，断言该 tag 不在 `LIVE` 中（加 `@VisibleForTesting liveCount()`）。
- **状态**：✅ 已修复（2026-11-01）

## BUG-011 `TopicFragment.rotateCookie` 绕过 `AccountStore.patchAccount` → 失效状态不恢复、锚点不更新、并发吞写

- **位置**：`TopicFragment.kt:236-244`
- **现象**：账号被标记失效后，在超话页刷新成功，首页仍显示「已失效」；CookieLife 的过期倒计时明显偏早或显示未知；偶尔「刚存的账号没了」。
- **根因**：

  ~~~kotlin
  val accounts = AccountStore.load(ctx)
  val index = accounts.indexOfFirst { it.uid == uid }
  if (index < 0 || accounts[index].cookie == cookie) return
  accounts[index] = accounts[index].copy(cookie = cookie)   // ← 只改 cookie
  AccountStore.save(ctx, accounts)                          // ← 无锁整表覆盖
  ~~~

  不写 `cookieDead = false`、不写 `cookieIssuedAt / cookieRotatedAt`、不写 `cookieSeenAt`；整表写又没有 `AccountStore.LOCK`，与首页签到/面板同步/Worker 的 `patchAccount` 并发时互相吞写。
- **影响**：失效状态卡死（用户以为号掉了）；CookieLife 预估失真；账号表偶发丢更新。
- **修复方案**：一行替换为 `AccountStore.markVerified(ctx, uid, cookie)`。
- **验证**：手测——标 dead 后在超话页刷新，断言首页不再显示失效；`AccountStorePureTest` 可覆盖。
- **状态**：✅ 已修复（2026-11-01）

## BUG-012 `HomeFragment.resignTopics` 用弹窗打开时的旧 `st` 与未 verify 的 cookie 批量补签

- **位置**：`HomeFragment.kt:1226-1252`（对照 `TopicFragment.kt:563-577`）
- **现象**：从超话页弹窗批量补签，若弹窗停留超过 `st` 有效期或 cookie 恰在期间被轮换，整批全部失败；日志「补签 0/N」。
- **根因**：

  ~~~kotlin
  val acc = AccountStore.load(ctx).firstOrNull { it.uid == uid }   // 只 load，不 verify
  for (topic in topics) {
      delay(Random.nextLong(3000, 8000))
      if (WeiboApi.sign(acc.cookie, topic.scheme ?: "", st)) ...   // st 来自弹窗打开时刻
  }
  ~~~

  而 `TopicFragment.checkin` 每次签到前都会 `WeiboApi.verify + rotateCookie` 刷新 `st`。两条路径约定不一致。
- **影响**：批量补签在真实使用节奏下（用户会犹豫、会切后台）成功率低，失败还要等风控间隔。
- **修复方案**：循环内每 N 个（或每次）先 `verify` 取新 cookie + 新 `st`，失败则中断并在日志里说明「登录状态失效，请到超话页刷新」。
- **验证**：手测——打开补签弹窗后故意停留 2 分钟再确认。
- **状态**：✅ 已修复（2026-11-01）

## BUG-013 `ImagePreviewActivity` 删除无并发闸门、无位置复核 → 删错图或崩溃

- **位置**：`ImagePreviewActivity.kt:184-219`
- **现象**：连点两次删除（或删除过程中滑到别的图再点）会删错图，或在删最后一页时 `IndexOutOfBoundsException`。
- **根因**：

  ~~~kotlin
  val pos = pager.currentItem
  val item = items[pos]
  lifecycleScope.launch(Dispatchers.IO) {
      val ok = DocumentsContract.deleteDocument(resolver, item.uri)   // 挂起点
      ...
      withContext(Dispatchers.Main) {
          items.removeAt(pos)          // ← pos 是挂起前的旧值；期间列表可能已被改
          pagerAdapter.notifyItemRemoved(pos)
      }
  }
  ~~~

  两次并发删除：第一次移除后列表整体左移，第二次再用旧 `pos` 移除 → 移掉的是**另一张**（磁盘删 B、列表移除 C），或用 `pos == size` 直接抛 IOOBE。确认框每次点击新建（`:174-182`），不检查是否已有删除在跑。
- **影响**：用户相册被删错图（不可恢复）；崩溃。
- **修复方案**：加 `private var deleting = false`（进入即设、`finally` 清）；主线程回写前用 `items.indexOfFirst { it.uri == item.uri }` 复核，`< 0` 则只 `notifyDataSetChanged` 不 remove；确认框 `.show()` 前判断 `deleting`。
- **验证**：手测连点两次删除、删最后一页、删除中左右滑。
- **状态**：✅ 已修复（2026-11-01）

## BUG-014 `HomeFragment` 失败分支新建空 `TopicState` 覆盖快照 → 首页「N 个待签」角标瞬间清零

- **位置**：`HomeFragment.kt:873`、`:883`（`TopicState.topics` 默认 `emptyList()`）
- **现象**：断网或风控时刷新超话失败，首页底部「待签 N」直接变 0；TopicStore 里旧清单还在，只是内存快照被空表覆盖，要等 `onResume` 才恢复。
- **根因**：失败分支不复用旧快照的 `topics`：

  ~~~kotlin
  setTopicState(acc.uid, TopicState(atMs = now, error = "登录已失效"))   // topics 为空
  ~~~

  正确写法是基于旧值 `copy`（同文件 `:1253` 在别处就是这么写的）：
  `setTopicState(uid, (topicStates[uid] ?: TopicState()).copy(atMs = now, error = ..., loading = false))`
- **影响**：UI 状态误导（用户以为超话全签完了/全丢了）；角标闪烁。
- **验证**：手测飞行模式下点刷新，断言角标不变、错误提示出现。
- **状态**：✅ 已修复（2026-11-01）

## BUG-015 `CrawlPlanner.nextScanPage`：全量扫描在首页失败时把断点重置到第 1 页

- **位置**：`CrawlPlanner.kt:377-380`；同类 `CrawlCursor.kt:209`
- **现象**：`fullScanPageHint` 从 41 变回 1，图库页显示「全量已扫到第 1 页」，200 页的账号要重爬 10 轮；若第 41 页是稳定失败页，断点在 1→21→41→1 之间永久往返。
- **根因**：

  ~~~kotlin
  StopReason.BUDGET, StopReason.PAGE_ERROR -> (plan.lastPage + 1).coerceAtMost(MAX_PAGES)
  ~~~

  `lastPage` 的语义是「最后一个成功页」，失败页正好是窗口首页时仍为 0（`planScan:321-323` 的 `break` 未赋值），于是 `0 + 1 = 1` 覆盖了 `plan.startPage`。
- **影响**：断点丢失，白烧列表请求与风控额度；用户看到进度倒退。
- **修复方案**：`if (plan.lastPage > 0) plan.lastPage + 1 else plan.startPage`；`CrawlCursor.kt:209` 改为 `anchorPageHint = if (hintPage >= 1) hintPage else c.anchorPageHint`（现在 `coerceAtLeast(1)` 会把「一页都没走成」写成第 1 页）。
- **验证**：`CrawlPlannerTest` 增加「首页 fetch 失败、startPage=41」用例，断言 `nextScanPage == 41`。
- **状态**：✅ 已修复（2026-11-01）

## BUG-016 单次空页即判「已到底」，且 `reachedBottom` 不可逆

- **位置**：`CrawlPlanner.kt:125-128`、`:223-227`、`:346-349`；`WeiboApi.kt:807-813`
- **现象**：某次翻页恰好整页都是运营位/推荐位（`card_type != 9` 被丢弃、无 `mblog` 被 `continue`），App 判「已到底」并永久闩锁，新的微博再也不抓。
- **根因**：`fetchUserPosts` 会过滤卡片类型，空列表 ≠ 到底；`reachedBottom` 一旦置位没有复位路径（`onSinceDateChanged` 也不清）。
- **影响**：增量抓取静默停止。
- **修复方案**：连续两次空页才判到底，或用已取到的 `cardlistInfo.total` 交叉校验；至少日志区分「本页无可识别帖」与「已到底」；`onSinceDateChanged` 清 `reachedBottom`。
- **验证**：单测两页返回空 → 第一次不置位、第二次置位。
- **状态**：✅ 已修复（2026-11-01）

## BUG-017 `ImagePreviewActivity` 全尺寸图无取消、无缓存、无并发上限

- **位置**：`ImagePreviewActivity.kt:257-277`（`Holder.bind`）
- **现象**：快速左右滑动后内存暴涨，低端机 OOM；同一张图反复解码。
- **根因**：每次 `bind` 都 `lifecycleScope.launch(Dispatchers.IO) { ThumbLoader.fullDrawable(...) }`，不保存 Job、`onViewRecycled` 不取消、不走 `LruCache`；ViewPager2 常驻 3 页 × 2048 长边 ≈ 12-16MB/页。
- **修复方案**：保存 `Job` 并在 `onViewRecycled / onDetachedFromWindow` 取消；加 `Semaphore(2)`；按屏幕尺寸降采样。
- **验证**：Profiler 连滑 50 张，内存不持续增长。
- **状态**：✅ 已修复（2026-11-01）

## BUG-018 图库 `MOV` 筛选是死入口（`isImage` 不含 mov，数据源也只收图片）

- **位置**：`GalleryFiles.kt:55-64`、`:82-90`；`GalleryBrowseActivity.kt:262`、`:386-389`
- **现象**：图库页有 4 个筛选 chip（全部/图片/动图/视频），点「视频」永远空列表。
- **根因**：`IMAGE_EXTS = setOf("jpg","jpeg","png","gif","webp","bmp")` 不含 `mov`，`listImages` 只返回图片；`applyFilter` 里 `KindFilter.MOV -> row.ext == "mov"` 永远不成立。
- **修复方案**：要么把 mov 纳入数据源（注意 Live 照片的 mov 是配对资源，会翻倍），要么删掉该 chip。
- **状态**：✅ 已修复（2026-11-01）

## BUG-019 `BrowsingActivity.submitSelected` 用筛选后的 `visible` 生成任务 → 被筛掉的勾选帖静默丢失

- **位置**：`BrowsingActivity.kt:398`
- **现象**：勾选 5 条（其中含被「只看原创/只看有图」挡掉的），按钮显示「下载选中(5)」，实际只抓了可见的 2 条，Toast 还报「完成 2 张」。
- **根因**：`val tasks = visible.filter { it.bid in selectedBids }.map { Crawler.PickTask(it.bid, null) }` —— 任务从「当前筛选结果 visible」生成，而不是从 `selectedBids` 生成；筛选 chip 只改 `filterOriginal / filterPic`，从不清 `selectedBids`，两者就此脱钩。
- **影响**：用户以为在跑，实际静默少抓，且无任何提示。
- **修复方案**：`posts.filter { it.bid in selectedBids }` 生成任务；若 `tasks.size != selectedBids.size` 明确提示「N 条被当前筛选隐藏，已跳过」。
- **验证**：勾选含被筛掉的帖后提交，断言任务数 == 勾选数。
- **状态**：✅ 已修复（2026-11-01）

## BUG-020 登录轮询 `WeiboApi.verify(cookie)` 未传 `force` → 命中 90s 探活缓存，登录态判定竞态

- **位置**：`LoginWebViewActivity.kt:300`；`WeiboApi.kt:279-287`（`verify(cookie, force = false)`）、`:149`（`VERIFY_TTL_MS = 90_000L`）
- **现象**：登录页显示「已登录」并写入账号，随后所有请求返回 -100/401；或 cookie 实际已失效却闪一下「验证成功」然后 finish。
- **根因**：`verify` 有 90 秒结果缓存，而 `WeiboApi.kt:276-277` 的注释明确写「登录轮询这类必须看到实时状态的场景要显式传 `force = true`」——调用点违反了自家约定。触发前提：进登录页前 90 秒内对同一份 cookie 做过一次成功探活（首页签到前就会做）。
- **影响**：登录态判定错误，用户进入「假登录」状态，后续全盘失败。
- **修复方案**：改 `WeiboApi.verify(cookie, force = true)`；并先 flush `CookieManager` 再读候选 cookie。
- **验证**：登录页进入前 90 秒内先跑一次首页签到，再登录退出态账号，断言不会误判成功。
- **状态**：✅ 已修复（2026-11-01）

---

# P2：功能缺陷、并发竞态、资源浪费

| 编号 | 位置 | 问题与修复 |
|---|---|---|
| BUG-021 | `CrawlIndex.kt:191` | 加载路径的 `filesByPic` 清理条件写反：`if (pic.isNotEmpty() && it.picId == pic) remove`，而运行期 `:265` 是 `it.picId != picId`。重启后残留旧 picId→FileRec 映射，`picOwner` 指向别的图 → 该图被误判 EXISTS 漏下。修复：与 `:265` 同条件 |
| BUG-022 | `CrawlIndex.kt:508-514` | `flushLocked()` 空 catch 后无条件 `dirtySinceFlush = 0`：刷盘失败完全静默，`lastWriteError` 不置位，`Crawler.kt:895` 的「索引写盘失败」日志永不触发 → 用户只看到「进度莫名回退 / 图片重复下载」。修复：catch 里写 `lastWriteError` |
| BUG-023 | `CrawlIndex.kt:330-353` | `onPostSealed` 每封一帖就 `flushLocked() + writeRetryHint()`，后者内部 `pendingRetryCount()` 全表 count → O(n²) 且全程持 `LOCK`。修复：每 8 帖或按时间节流写 hint |
| BUG-024 | `CrawlIndex.kt:527-580` | `compact` 先 delete 主文件再 `renameTo`：进程在窗口期被杀 → 索引整体丢失；且只 reopen 自己的 writer，兄弟实例 writer 指向已 unlink 的 inode，墓碑静默丢失。修复：改覆盖式 rename + compact 后让 siblings 重开句柄 |
| BUG-025 | `SafAccess.kt:43-51` | `contentResolver.query(...)?.use { it.moveToFirst() }`：`query` 返回 null 与查询成功走同一 `Status.Ok` 分支 → provider 掉线时抓取照常开始然后全失败；catch 又把 `SecurityException` 归成 Unreadable。修复：null → Unreadable；`SecurityException` → NoPermission |
| BUG-026 | `AccountStore.kt:384-395` | 换抓取目录时在 **UI 线程**（`GalleryFragment.kt:92` 的 `OpenDocumentTree` 回调）执行 `listFiles + delete`，`runCatching` 还静默吞失败。修复：挪到 IO；失败记账提示 |
| BUG-027 | `CookieLife.kt:94-100` | 未配面板（`syncedAt` 恒 0）的账号，`anchor = listOf(issuedAt, rotatedAt, seenAt).filter{>0}.min()`，而 `issuedAt` 只在首次写入 → 锚点永远钉在**首次登录**时刻，第 7 天起恒定显示「已过期」，即使刚 `markVerified` 成功。修复：未配面板时优先 `maxOf` 或 `seenAt` |
| BUG-028 | `CheckInWorker.kt:73-75` | `catch (e: Exception) {}` 吞 `CancellationException`（同项目 `Crawler.runIncremental / runAlbum` 都有 rethrow，唯独这里漏）。修复：先 `catch (e: CancellationException) { throw e }` |
| BUG-029 | `CheckInWorker.kt:82` | 全账号失效仍 `Result.retry()` → WorkManager 指数退避反复拉起死账号探活，耗电且刷风控。修复：全 dead 时返回 `success()` |
| BUG-030 | `Crawler.kt:223-226` | `pickCrawlCookie` 用到回退账号时不回写 `AccountStore` → 下个阶段再 verify 一遍，白耗一次请求。修复：`markVerified(ctx, uid, v.cookie)` |
| BUG-031 | `Crawler.kt:170` | 相册帖 `pic_ids` 空串不过滤（`:805-809` 有 trim + 跳过）→ 拼出 `https://wxN.sinaimg.cn/large/.jpg`，重试 3 次后该帖永久 PARTIAL。修复：同 `:805-809` 加 `.trim()` + `isNotEmpty()` |
| BUG-032 | `TopicStore.kt:90`、`:100` | `_state.value = _state.value + (uid to snap)` 非原子读改写，并发丢更新。修复：`update { it + (uid to snap) }` |
| BUG-033 | `TopicStore.kt:112-127` | `detail_v1_*` 缓存只增不减（`clearDetail` 无调用方），`ensureLoaded` 在主线程 `prefs.all` 全量读。修复：加 TTL 淘汰 + 启动时搬 IO |
| BUG-034 | `TopicFragment.kt:330`、`:342` | 空态用**未过滤**的 `rows` 判断（`showEmpty = rows.isEmpty()`、`list.visibility = if (rows.isEmpty())`），筛选后无结果时整屏空白，`:335-339` 的 `topic_empty_filter` 分支永远走不到。修复：改按 `shown` |
| BUG-035 | `TopicFragment.kt:139` | 隐藏的超话页也会自动刷新（`MainActivity` 全部 add + hide 不改变生命周期，`onViewCreated` 仍执行 `maybeAutoRefresh`），与首页 `runAll(true)` 重复打接口。修复：`if (!isHidden)` |
| BUG-036 | `TopicFragment.kt:563` | 签到无 per-row in-flight 集合（`detailBusy` 有、签到漏了）→ 连点触发多次 `signDetailed`，耗风控额度并叠 Toast。修复：加 `checkinBusy: MutableSet<String>` |
| BUG-037 | `TopicFragment.kt:669-699` | 「查看原始 JSON」用 `requireContext()` 建 Dialog，不在 `onDestroyView` dismiss → 旋转 `WindowLeaked` |
| BUG-038 | `TopicFragment.kt:230-232`、`:503`、`:607` | `catch (e: Exception)` 吞 `CancellationException` → 把「Job was cancelled」写进 `wcm_topics` 快照错误，重启仍在。修复：先 rethrow |
| BUG-039 | `HomeFragment.kt:794-809` | `setBusy` 在 `view == null` 时提前 return，但 `busy` 已改、按钮未更新 → 旋转/切页回来按钮文案与实际状态不一致。修复：`render()` 里统一按 `busy` 重设按钮文案 |
| BUG-040 | `HomeFragment.kt:242-254` | `autoHandled` 在判断之前置位，且先 `setLastAutoAt(now)` 再 `runAll(true)`。首次打开无账号（或 `runAll` 因 busy 早退）→ 本次进程内 6 小时不再自动签到。修复：条件成立才置位，成功启动后再落时间戳 |
| BUG-041 | `HomeFragment.kt:282-285`、`:770-773` | 日志缓冲区绑在视图上（`onDestroyView { panel = null }`），旋转窗口内的签到/抓取日志永久丢失。修复：`StringBuilder` 提到 Fragment 层，视图重建时回灌 |
| BUG-042 | `HomeFragment.kt:338`、`:307`；`TopicFragment.kt:800` | 主线程同步 IO：`render()` 每次 `AccountStore.load`、`TopicStore.ensureLoaded` 遍历 `prefs.all`、`Holder.bind` 里读 `TopicStore.detail` 并解析 JSON。修复：内存缓存 + IO 预热 |
| BUG-043 | `HomeFragment.kt:960-997` + `AppScope.kt:22-24` | `runAll` 的 `taskJob` 只有 `try/finally` 没有 `catch`，而 `AppScope` 只拼了 `SupervisorJob() + Dispatchers.IO`、没有 `CoroutineExceptionHandler`。`runAccount` 里 `:1026` 的 `AccountStore.markVerified` 与 `log()` 不在内层 try 中，一旦抛异常（写盘 IO / LogPanel 追加）→ 未捕获 → **崩进程**且日志区毫无痕迹。修复：scope 加 handler 兜底 + 给 `taskJob` 补 `catch`（与 `GalleryFragment.kt:706-742` 对齐） |
| BUG-044 | `BrowsingActivity.kt:301-303` | 翻页一次失败就 `hasMore = false`，而滚动监听首行 `if (dy <= 0 || !hasMore || listBusy) return` 再无重试入口 → 一次 429/断网后底部永久不再加载。修复：「没有更多」只留给服务端返回空列表的分支，异常分支保留 `hasMore` |
| BUG-045 | `BrowsingActivity.kt:434-438` | `setSubmitting(v)` 先 `btnDownload.isEnabled = selectedBids.isNotEmpty() && !v`，紧接着 `renderSelectBar()` 又赋 `selectedBids.isNotEmpty()` 覆盖掉 → 提交中按钮仍可点，第二次弹「正忙」。修复：`renderSelectBar` 里带上 `&& !submitting` |
| BUG-046 | `BrowsingActivity.kt:167-171` | 无 `savedInstanceState` 保护，`onCreate` 无条件 `setText(prefill); startBrowse()` → 每次旋转重跑 `resolveUid` + 最多 3 页（覆盖用户输入；uid/page/posts/筛选/多选/滚动全丢）。修复：`if (savedInstanceState == null)` + 状态保存 |
| BUG-047 | `GalleryBrowseActivity.kt:105-170`；`PostPickActivity.kt:76-120` | 完全无实例状态恢复（筛选/排序/分组/搜索词/多选/滚动/勾选全丢） |
| BUG-048 | `GalleryFragment.kt:431-456` | `refreshGalleryCount` 每次 `onViewCreated / onHiddenChanged` / 抓取收尾都全量 `GalleryFiles.listImages(...).size`（10 万张 = 数万次 SAF IPC）。注意：已在 `Dispatchers.IO`（`:442`），不卡主线程，但请求量 O(n)。修复：缓存计数 + 抓取结束时增量更新 |
| BUG-049 | `WeiboThrottle.kt:40` | `locks.getOrPut(key) { Mutex() }` 在 `ConcurrentHashMap` 上非原子 → 并发首次调用可能拿到两个 Mutex，节流被击穿（双倍请求，易吃 429）。修复：`computeIfAbsent` |
| BUG-050 | `RemoteThumb.kt` | `resp.body?.bytes()` 无响应体上限（对比 `Crawler` 有 64MB 闸门）；缓存 key 不含 `reqSize`。修复：加 `Content-Length` 检查 + 256KB 上限；key 拼上尺寸 |
| BUG-051 | `Takeover.kt` | `claimAll` 的 `hashed++` 在 `hashOf == null` 时也自增 → 接管报告数字虚高。修复：判空后再自增 |
| BUG-052 | `LoginWebViewActivity.kt:395-400` | `onDestroy` 只 `stopLoading / destroy`，未 `(webView.parent as ViewGroup).removeView(webView)`、未 `pauseTimers()`（官方要求，依赖 ROM） |
| BUG-053 | `PostPickActivity.kt:417-425`、`:431-434` | 每个 `bind` 都起协程拉缩略图、不取消（`RemoteThumb` 内有 `Semaphore(4)` 兜并发，但 Job 会堆积）；长按预览用 bind 时捕获的 `position`（列表变动后错位，应取 `bindingAdapterPosition`） |
| BUG-054 | `GuestSession.kt:103-107` | `cookie()` 在 `synchronized(gate)` **之外**写 `cached / cachedAt`，与 `handshake`（锁内写同一对字段）竞争：可能把刚被 `clear()` 判失效的旧 cookie 写回，且 `cachedAt` 刷成当前时间 → 又能活 365 天。修复：磁盘读与缓存写一并进锁做双检 |
| BUG-055 | `GuestSession.kt:123-128` | `refresh()` 不判断「刚刷新过」，并发失效会串行握手 N 次（每次 2 个请求）。修复：gate 内比较 `cachedAt`，30s 内复用 |
| BUG-056 | `WebAjax.kt:127-131` | HTTP 200 + `ok == 0` 抛的异常没有 `identitySuspect = true`，而 `Crawler.kt:639-647` 的「刷新游客身份再重试」完全以该标记为开关 → 相册墙一次「请先登录」就让整轮退化成时间线模式。修复：`ok == 0` 且 message 命中「登录/身份/风控」时置位（**需真机抓一次相册墙响应确认**） |

---

# P3：代码质量与微优化

1. **`LogPanel.kt:42-58`**：每次 `append` 都 `joinToString("\n") + setText` 全量重建 → O(n²)。改增量追加 + 限行数。
2. **`Crawler.kt:1346`** 的 `ctx` 未使用；**`GifEncoder.kt:184`** 的 `val h = frame.height` 未使用 —— 两条编译警告，清掉即可。
3. **重复代码**：`CrawlPlanner` 三段翻页循环（`:118-167`、`:216-265`、`:318-353`）几乎逐行重复且判据已漂移（置顶过滤只漏 BUG-003 那一处）。建议抽 `PageSlice.walkPage()` 模板。
4. **死代码**：`CrawlPlanner` 的 `Coverage` 类与 `overlaps()`、`if (!cursor.hasCoverage) return 1` 分支、`Budget.RETRY.maxPages = 0` 伪字段；`CrawlRunner.runPicked`（`:148-156`）全程不发 `CrawlEvent`，图库页进度条在勾选抓取时恒为 0。
5. **`CrawlPlanner.PageSlice.timed / positionHint` 是 getter**：每页多做 4-6 次 filter 分配。改构造时算一次。
6. **`TopicFragment.render()`** 每次 `removeAllViews` 重建 chip；`SimpleDateFormat` 每次 `new`（应复用或改 `java.time`）。
7. **两套 toast**：`UiExt.toastShort` 与 `TopicFragment` 各自实现；`errorOf / updatedAt` 未与 `accounts` 求交（账号删了错误提示还在）。
8. **`CrawlCursor.dateOf`**（`:161-162`）无 `try/catch`（同文件 `toPickerMillis` 有 `runCatching`），异常 epoch 会抛。
9. **`SettingsFragment`**：token 输入未 `trim`；`savePanel` 后不回填输入框；`ensureLocalNetworkPermission` 只在保存时调用。
10. **`CrawlProgress.kt:97`**：`processed * 100` 整数溢出（当前量级达不到，语义上应 `Long`）。
11. **`WeiboApi.kt:790-791 / 918-919 / 472`**：uid / bid 未 URL 编码（当前被上游校验兜住，属潜在隐患）。
12. **`GalleryBrowseActivity.kt:223-232`**：覆写 `getSpanSize / getSpanIndex` 未关 `SpanSizeLookup` 索引缓存，分组标题可能错位（需真机确认）。
13. **`BrowsingActivity.kt:165` 与 `:177`**：`renderTargets()` 在 `onCreate` 中先执行，此时 `cellGap` 仍为 0，`:359-361` 的 `lp.marginEnd = cellGap` 恒为 0，且 `renderTargets` 之后再没被调用 → 目标 chips 永远没有间距。
14. **`PostPickActivity.kt:203-209`**：`pidOf` 只剥扩展名、不剥 query（`? #`），URL 带参数时 pid 与 pic_ids 对不上 → 该图丢失 livephoto/gif 类型与大图 URL，静默回落普通缩略图。
15. **`BrowsingActivity.kt:517`、`:537`**：`fillPics` 每格起一个协程（最多 9 并发 IO），「更多」格用权重 3f 与子项 1f 混用，宽度不可控；`startBrowse` 只挡 `listBusy`，不挡 `selectMode / submitting`（提交期间点「加载」会清空列表与勾选）。

---

# 已核查、确认无问题（避免后续重复排查）

- **`MainActivity`**：`restoreFragments` 用 `as?` 安全降级（防进程重建 `TypeCastException`）、`show()` 用 `isHidden` 判断、`applySystemBarInsets` 返回 `CONSUMED`、底栏动画 `withEndAction` 二次确认 —— 全部正确。
- **`CrawlIndex` 的核心设计**：进程级共享锁 + 墓碑广播（`onFileDeleted → siblings().forEach { forgetInMemory }`）、`DONE/PARTIAL/OPEN/VOID` 状态机、`retryAt` 指数退避、旁挂 `.retry` 提示文件避免读 17MB NDJSON —— 都是刻意的正确实现，**不要当 bug**。
- **`AccountStore.setCrawlTreeUri` 换目录时 `prune(keep = {新tag, 旧tag})`**：保留新旧两个 tag 是「切回旧目录还能原地续跑」的设计，**不是 bug**。
- **`LoginWebViewActivity` 的 WebView 安全配置**：`javascriptEnabled`（必需）、`allowFileAccess / allowContentAccess = false`、`mixedContentMode = NEVER_ALLOW`、两个 `shouldOverrideUrlLoading` 都走白名单；Cookie 轮询/重试/uid 兜底均正确（唯一问题见 BUG-020）。
- **请求层**：`WeiboApi / CrawlRunner / CrawlPlanner` 的请求构造、`weiboUrl` 白名单、`WeiboThrottle` 真实发送时刻记账、取消传播、`CrawlRunner` 单闸门 `tryLock` —— 无问题（含 BUG-049 那处非原子性，属边缘竞态）。
- **`Hashing` 不关闭传入流**（调用方 `use {}` 正确）；**`GifEncoder` 的 LZW 升位条件**（`dictSize == (1 shl codeSize) + 1`，刻意晚一个表项以兼容 Pillow）—— 正确。
- **`AssetName.safeName`** 已剥离 `/` 与反斜杠，不构成路径穿越。
- **未发现**：ViewBinding 泄漏、`BackCallback` 重复注册、adapter 持有 Activity、视图协程越界（基本都用 `viewLifecycleOwner`）。

---

# 修复批次建议

| 批次 | 内容 | 风险 | 建议 |
|---|---|---|---|
| **批次 1（正确性，1-3 行/处）** | BUG-003（置顶锚点）、BUG-011（rotateCookie）、BUG-014（TopicState 覆盖）、BUG-015（断点回退）、BUG-020（verify force）、BUG-021/022（索引清理与错误上报）、BUG-028/029（Worker 异常与重试）、BUG-032（TopicStore 原子更新）、BUG-043（AppScope 兜底） | 低，多数有单测覆盖 | 立即修 |
| **批次 2（进度模型）** | BUG-001、BUG-002、BUG-004 | 中，需新增单测 | 修完补 `CrawlPlannerTest / CrawlIndexTest` 用例 |
| **批次 3（并发/UI）** | BUG-005、BUG-006、BUG-013、BUG-017、BUG-039、BUG-044、BUG-045 | 中，需真机验证 | 分批提测 |
| **批次 4（判定/IO）** | BUG-007、BUG-008、BUG-009、BUG-010、BUG-023~025、BUG-031、BUG-054~056 | 中 | BUG-008 需先抓真实响应样本 |
| **批次 5（P2 余项 + P3）** | 其余 | 低 | 随手修 |

---

# 附录：复现与验证命令

~~~bash
# 单测（必须串行执行，不可与 lint 并行：共享 build 目录会互相破坏）
./gradlew --offline :app:testDebugUnitTest --rerun-tasks

# 静态检查（lint 已把 NewApi 配成 error）
./gradlew --offline :app:lintDebug
# 报告：app/build/reports/lint-results-debug.txt
~~~

> 审查期间工作区保持干净（HEAD `15c0c94`），本报告只新增文档，未改动任何源码。
