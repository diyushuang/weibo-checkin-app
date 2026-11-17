# 超话互动任务（Engage）设计说明

> 适用版本：`0.11.0` 之后的新增功能
> 接口依据：`docs/research/weibo-write-endpoints.md`（2026-11-16 登录态实测）
> 实施阶段：S1–S7 已完成（端点实测 → 纯函数 → 存储 → 写接口 → 编排 → UI → 定时）

## 1. 一句话

在用户授权并已登录的前提下，对**指定超话**按用户设定的次数自动完成评论、转发、点赞、发帖，
用于超话经验任务；文案由用户自己粘贴导入，不联网抓取任何第三方内容。

## 2. 模块落点

| 模块 | 新增文件 | 职责 |
| --- | --- | --- |
| `:core` | `EngageModels.kt` | 动作/三态/错误分类/配置/文案/运行记录/台账（纯数据） |
| `:core` | `EngageTextPool.kt` | 规范化、去重、批量拆分、占位符渲染、**`compose()` 唯一正文出口**、轮换取用 |
| `:core` | `EngagePlanner.kt` | 配额抵扣、软约束提示、免打扰、后台发帖开关、单轮上限 |
| `:core` | `EngageGuard.kt` | 两级熔断（动作级先于整轮级） |
| `:core` | `EngageSchedule.kt` | `dayKey`、免打扰、下次执行时刻、文案禁用日期 |
| `:core` | `EngageCodec.kt` | JSON 编解码（版本号 + 缺字段落默认 + 坏条目跳过） |
| `:core` | `EngagePorts.kt` | `EngageConfigPort` / `EngageRunPort` |
| `:core` | `WeiboWriteApi.kt` | 发帖/评论/转发/点赞/删除 |
| `:core` | `WeiboFeedApi.kt` | 超话帖子流 + 目标筛选 |
| `:data` | `EngageStore.kt` | 独立 prefs `wcm_engage`；预占/提交/释放全在锁内 |
| `:domain` | `EngageService.kt` | 七阶段编排 |
| `:app` | `EngageScreen / TextScreen / PostLogScreen / Tab / Ui / ViewModel / Worker / Scheduler` | UI 与执行触发 |

`WeiboHttp` 只做**加法**：`xsrfOf` / `formRequest` / `postForm` / `safeBodyExcerpt`。
既有只读函数一行未改。

## 3. 执行流程

```
任务页手动补跑（默认）/ 打开 App 自动补跑（需开关）
 → 配置校验（授权/账号/超话 ID）
 → 账号 cookie → 登录探活
 → 关注列表校验目标超话（计分前置条件）
 → 当日计数 → 计划（**发帖→评论→转发→点赞**）
 → 文案池取用 + 渲染 + 组装
 → 超话帖子流取互动目标（排除自己/纯转发/已用，优先 0 评论帖）
 → 逐条：节流 → 预占 → 请求 → 分类 → commit/release/UNCERTAIN → 记账
 → finally：换发回写 cookie、写运行记录、写台账、淘汰
```

## 4. 关键设计

### 4.1 发帖绝不重放
请求前 `reserveSlot`（write-ahead），**并且先把台账落成 `UNCERTAIN` 占位**，
请求后三态收敛：
- `OK` → `commitSlot`（计数 +1，清 pending）
- 明确失败 → `releaseSlot`
- **`UNCERTAIN`**（超时/断网/5xx，不知道发没发出去）→ `markUncertain` 保留 pending，
  **锁死当日发帖**，直到用户在台账里点「已确认发出 / 确认未发出」。

占位台账是必需的：若进程在请求途中被杀，没有占位就只有 pending、没有台账记录，
用户在界面上根本看不到可收敛的条目，当日发帖会永久锁死。

收敛时 `已确认发出` 必须走 `commitSlot`（计数 +1 并刷新 `lastPostAt`）；
只清 pending 会让 planner 认为今天还没发过，下一轮再发一条。

这是刻意的不对称：宁可当天少发一条，也不能重复发。

### 4.2 预览与实发同源
`EngageTextPool.compose(topic, body, videoUrl)` 既是预览的数据源，也是真正提交给
`/ajax/statuses/update` 的 `content`。预览的取用顺序也走真实的 `pick()`
（同样的「今日已用」排除规则），序号取自当日真实计数，因此预览与「下一轮实际会发的那条」一致。

### 4.2.1 超话 ID 口径（易错）
`WeiboTopicApi.extractTopicId` 返回的是**去掉 `100808` 的 hash**，
而写接口的 `topic_id=1022:<containerid>` 与帖子流的 `flowId` 都要求**完整 containerid**。

统一由 `EngageSchedule.topicContainerId()` 归一：配置里存完整值、比较时两边都归一、
`WeiboWriteApi` / `WeiboFeedApi` 入口再兜一次。**任何调用点都不要自己拼前缀**。

### 4.2.2 单轮时间预算
一轮含间隔最坏可达 20 分钟以上，而 `CoroutineWorker` 超过 10 分钟会被系统直接停掉
（不写记录、不回写 cookie）。因此 `maxRunDurationMs`（**默认 12 分钟**，可配 1–20 分钟）
在每个动作前校验，连同该动作的**真实剩余等待**一起估算；不够就 `guard.trip()` 主动收尾，
剩余动作留待下一轮。

后台执行时预算再压到 9 分钟：`EngageService.run(background, budgetCapMs)` 取配置值与
调用方上限的较小者，`EngageWorker` 传 9 分钟 —— 手动执行只在 Activity 存活期间跑，
不必迁就 Worker 的系统上限。

> 0.12.6 之前预算是 8 分钟、且发帖排在最后，实测默认配置跑到发帖已耗约 300 秒，
> 随机间隔取上限时会被预算砍掉，整轮跑完一条帖子都没发出去。现在发帖已提到最前。

### 4.2.3 人工确认模式
`requireConfirmBeforePost` 打开时，执行环节对发帖**不预占、不请求**，只把最终正文
落成 `AWAITING_CONFIRM` 台账；用户在发帖记录里点「确认发送」后，由
`EngageService.publishConfirmed` 走与自动发帖完全相同的预占/三态收敛路径。
同一槽位当日只保留一条待确认记录，避免每次运行覆盖上一条。

### 4.3 错误分类按实测形态写死
- `errno=404` + 「链接…无效」→ `ENDPOINT_INVALID`（接口改版，只能升级 App）
- `ok=-100` → `NOT_LOGGED_IN`
- 纯文本「请先验证身份」→ `NEED_VERIFY`（**必须在 JSON 解析之前判**，否则会被当成网络错误）
- `errno 10022/10023` / HTTP 429·432 → `RATE_LIMITED`

`weibo.com` 匿名请求下任何路径都返回 `ok:-100`，**不能**用它判断端点是否存在。

### 4.4 熔断优先级
硬停类（需人工 / 未关注 / 被屏蔽 / 接口改版）先于普通失败计数；
普通失败先判动作级（放弃该动作），再判整轮级（累计终止）。
跳过类（目标被删、已做过）**不计失败**。

## 5. 存储

独立 prefs 文件 `wcm_engage`（绝不写进 `wcm`，守住"升级不丢账号"的不变量）：

| 键 | 内容 | 保留 |
| --- | --- | --- |
| `cfg_v1` | 配置 JSON | 永久（清除时删） |
| `texts_v1` | 文案池整池 JSON（上限 500） | 软删保留统计 |
| `counter_v1_<uid>_<day>` | 当日计数 + 预占 + 余量 | 30 天 |
| `run_v1_<uid>_<runId>` | 单轮运行记录 | 每账号 20 条 |
| `post_v1_<uid>_<day>` | 发帖台账（含正文快照） | 90 天，单日 ≤50 |

台账 id 是 `uid_day_seq`，而 `seq` 由 planner 按**当日已完成数接续**（不是每轮从 1 重排），
所以同一天的第二轮不会覆盖第一轮的记录。`counter` 里的 `pending` 是当日发帖锁，
只有 `commitSlot` / `releaseSlot` 才会清掉。

## 6. 风险口径（写进授权弹窗）

去掉逐次确认后，安全阀是四道：**首次授权弹窗 → 发帖预览 → 发帖台账 → UNCERTAIN 锁死**。

明示给用户的三件事：
1. 内容会公开发布，发出后需本人到微博手动删除，本应用不提供批量撤回；
2. 平台可能因自动化发布限流/禁言/限制超话发言；
3. 遇到身份验证会停止并要求人工完成，**不做任何绕过**（不随机化 UA、不用代理池、
   不伪造设备指纹、不破解验证码）。

## 7. 明确不做

| 项 | 原因 |
| --- | --- |
| 抓取百度输入法圈子文案 | 无公开 API；只做用户手动复制粘贴 |
| 官方开放平台实现超话互动 | 开放平台没有超话域接口 |
| 视频上传 | 用户确认链接只作正文文本 |
| 多账号批量执行 | 本期只支持当前选中账号 |
| `fp` 浏览器指纹 | 实测可选，不传也能发帖 |

## 7.5 触发方式（v0.12.0 定稿）

不设固定钟点，且**默认不自动执行**。用户打开 `autoRunOnOpen` 开关后，**打开 App 时若今天还没有一次「有成功动作」的运行，才自动排一轮**；
判定用 `ranToday + okCount > 0`，所以上午失败一次后，下午开 App 还会再试。
关着开关时只响应任务页的「立即执行一次」。免打扰时段内不触发。

仍然用 WorkManager 执行而不是挂在 `viewModelScope`：一轮含发帖间隔可能十几分钟，
Activity 销毁会把协程取消，跑一半停在中间反而留下"不知道发没发"的残局。

## 8. 验证

- 单测：`Engage*Test` 共 165 例（文案拆分/渲染/预览一致/配额/规划/序号接续/熔断/排期/
  超话 ID 口径/编解码/配置校验/写响应分类/帖子流/编排前置拒绝/确认发送拒绝路径）
- 门禁：`testDebugUnitTest` + `assembleDebug` + `assembleRelease` + `lintDebug` 全过
- 真机清单见 `docs/research/weibo-write-endpoints.md` §6（V9–V16）
