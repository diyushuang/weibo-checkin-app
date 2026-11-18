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
| `:core` | `EngagePacing.kt` | 执行节奏预设、间隔平移、一轮耗时估算（纯函数） |
| `:core` | `EngageProgress.kt` | 过程态：步骤清单 + 流水 + 进度 + ETA（纯数据，可裁剪） |
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
任务页手动补跑（默认）/ 单项手动执行 / 打开 App 自动补跑（需开关）
 → 配置校验（授权/账号/超话 ID）
 → 账号 cookie → 登录探活
 → 关注列表校验目标超话（计分前置条件）
 → 当日计数 → 计划（**发帖→评论→转发→点赞**）
 → 文案池取用 + 渲染 + 组装
 → 超话帖子流取互动目标（排除自己/纯转发/已用，优先 0 评论帖）
 → 逐条：节流 → 预占 → 请求 → 分类 → commit/release/UNCERTAIN → 记账
 → finally：换发回写 cookie、写运行记录、写台账、淘汰

每一阶段与每一条动作都会推一帧 EngageProgress（步骤 + 流水 + 进度），
并随运行记录一起落盘（见 4.6）。
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
### 4.4 熔断优先级
硬停类（需人工 / 未关注 / 被屏蔽 / 接口改版）先于普通失败计数；
普通失败先判动作级（放弃该动作），再判整轮级（累计终止）。
跳过类（目标被删、已做过）**不计失败**。

### 4.5 执行节奏与单项动作（v0.13.0）

间隔原先只存在于 `EngageConfig` 的默认值里，**没有任何 UI 入口**：用户抱怨「默认间隔太长」
时只能改代码重装，而一轮耗时几乎全由间隔构成（发帖 5–8 分钟一项就占掉大半）。

现在分两层：

- **预设**：`EngagePacing.gapsOf()` 给出 稳健 / 均衡 / 快速 三档。
  `GENTLE` 就是 0.12.x 的老默认值（发帖 5–8 分钟、评论 25–60 秒…），保留为退路；
  `BALANCED` 是新默认（发帖 1–2 分钟、评论 10–20 秒、转发 20–45 秒、点赞 8–15 秒、
  动作间 3–8 秒），同样次数的一轮从约 6 分钟压到约 2 分钟。
- **微调**：`shift()` 整体平移区间并保留宽度。只调单端很容易把区间压成 0 宽，
  变成固定间隔 —— 失去随机性反而更像机器。上限 30 分钟：
  间隔超过单轮预算时每个动作都会被判「来不及」而静默跳过，属于「设了却不执行」，
  在校验阶段直接拒绝而不是留着让人猜。

**动作三态**由 `*Enabled`（做不做）× `*Auto`（能不能自己动）组合而成，
对外只暴露 `modeOf/withMode`：`OFF` / `MANUAL` / `AUTO`。

- 自动轮次（`plan(background = true)`）扣掉 `MANUAL` 的动作，并记一条 note；
  若扣完一个都不剩，`blocked` 要说「都已设为仅手动」而不是「今日已无待执行动作」——
  后者会让用户以为额度用完了。
- 单项执行（`plan(only = ...)`）：只做指定动作，`remaining(only)` 也只统计它，
  所以点一次发帖不会顺带扣掉评论额度；此时**不受 `*Auto` 约束**，
  用户主动点了按钮就该按他点的做。

### 4.6 过程可观测性（v0.13.0）

一轮动辄几分钟，此前只有一行「正在执行…」，跑完的运行记录也只有动作结论，
没有任何上下文（在等间隔？被风控？文案没取到？）。

`EngageProgress` 把过程拆成三层，全部是纯数据，随每次变更 `report()` 给界面：

1. **步骤**：`prepare → auth → topic → plan → text → feed → run → finish`，
   每步有 `PENDING/RUNNING/OK/WARN/FAILED/SKIPPED` 与起止时间（耗时现场算）。
   步骤 id 用固定字符串而不是枚举序号 —— 运行记录要落盘回读，改名会让历史记录显示不出来。
2. **流水**：带 `HH:mm:ss`、级别、动作序号的一条条记录（等待倒计时、提交中、
   成功/失败原因、单次耗时）。内存上限 140 条、落盘 120 条，都是丢最旧的。
3. **进度**：`done/total` + `waitUntilMs`（等间隔时的倒计时锚点）+ `etaMs`。
   ETA 用「已用时间 / 已完成数 × 剩余条数」，比计划估算更贴近实际。

展示参考 GitHub Actions 的 job 页：步骤清单（状态符号 + 每步耗时）在上，
实时流水在下（固定高度内滚动并自动跟随最新一条），失败步骤与错误流水用红色。

**过程要落盘**：`EngageRun` 增加 `trigger / only / steps / logs`，
跑完在「运行记录 → 详情」里可以按时间顺序回看这一轮 —— 这是把「只能靠 logcat 排查」
变成「用户自己就能看出卡在哪」的关键。


- `errno=404` + 「链接…无效」→ `ENDPOINT_INVALID`（接口改版，只能升级 App）
- `ok=-100` → `NOT_LOGGED_IN`
- 纯文本「请先验证身份」→ `NEED_VERIFY`（**必须在 JSON 解析之前判**，否则会被当成网络错误）
- `errno 10022/10023` / HTTP 429·432 → `RATE_LIMITED`

`weibo.com` 匿名请求下任何路径都返回 `ok:-100`，**不能**用它判断端点是否存在。


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

- 单测：`Engage*Test` 共 217 例（文案拆分/渲染/预览一致/配额/规划/序号接续/熔断/排期/
  超话 ID 口径/编解码/配置校验/写响应分类/帖子流/编排前置拒绝/确认发送拒绝路径/
  v0.13.0 新增：节奏预设与估算、三态与单项过滤、进度模型与流水裁剪、过程落盘往返）
- 门禁：`testDebugUnitTest` + `assembleDebug` + `assembleRelease` + `lintDebug` 全过
- 真机清单见 `docs/research/weibo-write-endpoints.md` §6（V9–V16）
