# 微博超话写接口实测记录

> 实测时间：2026-11-16 21:10–21:20（CST）
> 实测方式：Chrome 154 + CDP（Chrome DevTools Protocol）接管用户已登录会话，钩住 `Network.requestWillBeSent` 记录真实请求
> 账号：uid `<uid>`（用户本人授权）
> 测试超话：棋手战鹰（`1008088cf9880c8ec5f07f84c2f30e74d43574`，LV.5）
> 结论等级：**四个写接口 + 删除 + 帖子流 = 已实测，请求体逐字段确认**

---

## 1. 结论速览

| 动作 | 端点 | 关键参数 | 实测 |
| --- | --- | --- | --- |
| 超话发帖 | `POST https://weibo.com/ajax/statuses/update` | `content` + `topic_id=1022:<tid>` + `sync_mblog` | ✅ 成功 |
| 评论 | `POST https://weibo.com/ajax/comments/create` | `id`(mid) + `comment` | ✅ 成功 |
| 转发 | `POST https://weibo.com/ajax/statuses/normal_repost` | `id`(mid) + `comment` | ✅ 成功 |
| 点赞 | `POST https://weibo.com/ajax/statuses/setLike` | `id`(mid) | ✅ 成功 |
| 删除 | `POST https://weibo.com/ajax/statuses/destroy` | `id`(mid) | ✅ 成功 |
| 超话帖子流 | `GET https://weibo.com/ajax_proxy/chaohua/page?flowId=<tid>` | 需 `X-Requested-With: XMLHttpRequest` | ✅ 成功 |

**五个关键结论（都推翻了此前基于公开资料的猜测）**

1. **超话归属靠 `topic_id=1022:<超话tid>`**，不是 `containerid`，也不是裸 `topic_id=<tid>`。前缀 `1022:` 是硬要求。
2. **发帖正文参数名是 `content`**，不是 `text`（`text` 是官方开放平台 OAuth 接口的写法）。
3. **`fp`（浏览器指纹）是可选的**——实测不传 `fp` 发帖成功。这条决定 Android 端可行性：App 不需要伪造浏览器指纹。
4. **`X-XSRF-TOKEN` 是必需的**，值取自 cookie 里的 `XSRF-TOKEN`（需 URL-decode）。
5. **超话真实后端是 `i.chaohua.weibo.com`**（不是 `i.huati.weibo.com`，后者已下线），超话页数据走 `weibo.com/ajax_proxy/chaohua/*` 代理。

---

## 2. 接口详情（实测请求体）

### 2.1 超话发帖

```http
POST https://weibo.com/ajax/statuses/update
Content-Type: application/x-www-form-urlencoded
X-XSRF-TOKEN: <cookie 中 XSRF-TOKEN 的 url-decode 值>
Referer: https://weibo.com/page/<tid>?containerid=<tid>
```

```
content=接口联调测试，稍后删除。棋手战鹰超话
&pic_id=
&visible=0
&share_id=
&media=
&vote=
&topic_id=1022%3A1008088cf9880c8ec5f07f84c2f30e74d43574
&sync_mblog=1
&fp=<可选，浏览器指纹；不传同样成功>
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `content` | 是 | 正文纯文本（非 HTML 转义） |
| `topic_id` | 是 | **`1022:<超话tid>`**，`<tid>` 形如 `1008088cf9880c8ec5f07f84c2f30e74d43574` |
| `sync_mblog` | 否 | `1` = 同步到个人主页；`0` = 仅超话内可见（对应界面「同步到微博」勾选） |
| `pic_id` / `media` / `vote` | 否 | 图片 / 视频 / 投票，无则留空 |
| `visible` | 否 | `0` 公开 |
| `fp` | **否（可选）** | 浏览器指纹；**实测省略可用** |

成功响应：

```json
{"data":{"visible":{...},"created_at":"Mon Nov 16 21:18:03 +0800 2026",
 "id":5000000000000001,"idstr":"5000000000000001","mid":"5000000000000001",
 "mblogid":"<mblogid>","user":{"id":<uid>,...}}}
```

→ **`remoteId` 取 `data.mid`**（与 `data.id` 同值；`mblogid` 是 base62 短 id）。

### 2.2 评论

```http
POST https://weibo.com/ajax/comments/create
Referer: https://weibo.com/<uid>/<mblogid>
```

```
id=5000000000000002
&comment=围观一下
&pic_id=
&is_repost=0
&comment_ori=0
&is_comment=0
&fp=<可选>
```

`id` 是**数字 mid**（不是 base62 的 mblogid）。`is_repost=1` 可同时转发。`is_comment` / `comment_ori` 实测传 0。

### 2.3 转发

```http
POST https://weibo.com/ajax/statuses/normal_repost
```

```
id=5000000000000002
&comment=转发测试
&pic_id=
&is_repost=0
&comment_ori=0
&is_comment=0
&visible=0
&share_id=
&fp=<可选>
```

注意端点是 **`normal_repost`**，不是 `repost`。

### 2.4 点赞

```http
POST https://weibo.com/ajax/statuses/setLike
```

```
id=5000000000000002
&fp=<可选>
```

注意端点是 **`setLike`**，不是 `like`（按公开资料猜测的 `/ajax/statuses/like` 实测不可用）。

### 2.5 删除（用于清理与「发帖台账」的撤回能力）

```http
POST https://weibo.com/ajax/statuses/destroy
```

```
id=5000000000000001
```

只传 `id` 即可（不需要 `fp`）。成功返回**被删除那条博文的对象**，可据此判定成功。

### 2.6 超话帖子流（取互动目标）

```http
GET https://weibo.com/ajax_proxy/chaohua/page?flowId=<tid>
X-Requested-With: XMLHttpRequest   ← 缺这个头返回业务错误而非数据
```

响应 `items[]`，其中 `category == "feed"` 的项是帖子，`data` 字段含：

```
data.mid                    // 数字 mid，评论/转发/点赞都用它
data.user.id                // 作者 uid（用于排除自己的帖子）
data.text                   // 含 HTML 标签，需去标签
data.comments_count / reposts_count / attitudes_count   // 用于「首评优先」筛选
data.created_at
```

实测 8 条 feed 中有 5 条 `comments_count == 0` —— 正是「0 互动帖首评 +2 分」的目标，说明该策略有充足供给。

配套接口：`GET https://weibo.com/ajax_proxy/chaohua/page/extend?page_id=<tid>&show_recommend=1`（超话扩展信息，含版块/规则）。

### 2.7 超话签到（现有实现参考）

超话列表卡片里的签到按钮 scheme 为：

```
/api/container/button?sign=<st>&request_url=http%3A%2F%2Fi.chaohua.weibo.com%2Fmobile%2Fshproxy%2Factive_fcheckin%3Fpageid%3D<tid>...
```

即真实签到后端是 `i.chaohua.weibo.com/mobile/shproxy/active_fcheckin`。与现有 `WeiboTopicApi.signDetailed` 走 `scheme + st` 的做法一致，无需改动。

---

## 3. 鉴权要点

| 项 | 要求 |
| --- | --- |
| `X-XSRF-TOKEN` | **必需**。取自 cookie `XSRF-TOKEN`，需 `decodeURIComponent`。缺失直接 403 |
| Cookie | 必须含 `.weibo.com` 域 `SUB`。**完全无 Cookie 时 POST 一律 403**（22 字节 HTML），不是业务错误 |
| `Referer` | 发帖用超话页 URL，评论/转发用帖子详情页 URL。缺失是否被拒未单独验证 |
| `X-Requested-With` | `weibo.com/ajax_proxy/*` 必需；`/ajax/*` 写接口实测未带也成功 |
| `fp` | **非必需**（已实测） |
| UA | 实测用浏览器 UA；App 端沿用现有 `WEB_UA` 即可，**不做 UA 随机化** |

---

## 4. 方法论修正（避免再错）

**`{"ok":-100,"url":"..."}` 不能用来判断 `weibo.com` 端点是否存在。**

匿名或假 cookie 请求 `weibo.com/ajax/*` 下**任何**路径都会返回 `ok:-100` + `newlogin`。上一版据此把 `/ajax/statuses/like`、`/ajax/statuses/repost`、`/ajax/statuses/update` 全判为"存在"，但实测真实端点是 `setLike` / `normal_repost` / `update`——`like` 与 `repost` 两个判断是错的。

**可靠的路径存在性判据只有一种**：m 站 `{"ok":0,"errno":404,"msg":"出错: 链接…无效"}`。`weibo.com` 侧只能靠真实抓包确认。

---

## 5. 对实施方案的影响

| 原方案判断 | 修正 |
| --- | --- |
| 首选 m 站（`/api/statuses/update` 等），不需要 XSRF | **错**。m 站虽有同名接口，但**没有超话归属语义**。超话发帖必须走 `weibo.com/ajax/statuses/update` → **必须实现 XSRF** |
| 需要补 XSRF 才能发写请求 | **对，且是硬要求** |
| `fp` 指纹无法生成是可行性风险 | **风险解除**，`fp` 可选 |
| 旧版 `weibo.com/p/aj/proxy` → `i.huati.weibo.com` 作兜底 | **删除**，主机已下线 |
| 帖子流接口待实测 | **已实测**：`ajax_proxy/chaohua/page`，字段路径明确 |
| 发帖参数字段未知 | **已确认**：`content` + `topic_id=1022:<tid>` + `sync_mblog` |

**结论：功能可行，但实现路线要从「m 站为主」改为「weibo.com ajax 为主」，`WeiboHttp` 的 XSRF 改造从"可选项"升级为"必做项"。**

---

## 6. 测试残留与清理

为验证接口实际产生了 3 条内容，均已删除并核验：

| 内容 | mid | 状态 |
| --- | --- | --- |
| 超话测试帖「接口联调测试，稍后删除。棋手战鹰超话」 | 5000000000000002 | ✅ 已删除 |
| 转发「转发测试」 | 5000000000000003 | ✅ 已删除 |
| 无 fp 参数测试帖 | 5000000000000001 | ✅ 已删除 |

核验：`GET https://weibo.com/ajax/statuses/mymblog?uid=<uid>&page=1&feature=0` 返回 `list: []`（主页时间线为空）。评论与点赞都作用在已删除的测试帖上，随帖子一并消失。

---

## 7. 复现方式（供后续回归）

Chrome 136+ 出于安全考虑**禁止在默认用户目录开启远程调试端口**。复现步骤：

1. 关闭 Chrome；
2. 复制 `%LOCALAPPDATA%\Google\Chrome\User Data\Local State` 与 `Default\`（可排除各类 Cache）到独立目录；
3. 用 `--user-data-dir=<独立目录> --remote-debugging-port=9222` 启动；
4. CDP `Network.enable` + `Network.requestWillBeSent` 记录请求（`maxPostDataSize` 调大）；
5. 通过 `Runtime.evaluate` 驱动页面点击/填表。

> 临时 profile 副本含登录凭据，用完必须删除。本次已删除。

---

## 8. 仍未验证

| # | 项 | 说明 |
| --- | --- | --- |
| N1 | 限流码是否仍是 `errno 10022/10023`、触发后 HTTP 码 | 需真实触发，本次未故意撞限流 |
| N2 | 内容进审核时的响应形态（`ok=1` 无 mid？还是 `ok=0`） | 未构造违规内容 |
| N3 | 超话发帖字数上限（前端 2000？服务端是否另限） | 未测长文 |
| N4 | `sync_mblog=0` 时帖子是否仍计入超话经验 | 未验证计分，只验证了接口 |
| N5 | 发帖后被超话主持人屏蔽的判定信号 | 需真实被屏蔽场景 |
| N6 | 版块发帖（水帖专区等）是否需要额外 `tag_id` | 本次发在主版块 |
| N7 | `Referer` 缺失/错误时是否被拒 | **已由真机实测回答，见 §9.1**：缺 `Origin`/`Referer`/`X-XSRF-TOKEN` 直接 403 |
| N8 | `ajax_proxy` 类只读接口是否也查 XSRF | **已验证**：查，且必须有 `Origin`/`Referer`（§9.1） |
| N9 | 超话评论每日 4 条、转发 2 条的计分上限是否仍生效 | 未逐条计分核对，只验证接口层面可发 |

---

## 9. Android 真机实测（2026-11-17）

环境：`com.wcm.checkin` 0.12.x，模拟器 Android 16（x86_64），账号 uid `<uid>`，
目标超话「中国乒乓球队」（`100808149d3493a2469310fd109001088cbb5c`，Lv.4→Lv.5）。

### 9.1 帖子流 403 —— 浏览器抓包结论不能直接搬到 OkHttp

**现象**：`GET https://weibo.com/ajax_proxy/chaohua/page?flowId=<tid>` 在 App 内稳定返回
**HTTP 403（请求被拒绝）**，而同一时刻、同一 cookie 的写接口（发帖）完全正常。

**根因**：`WeiboHttp.desktopRequest` 只带了 `X-Requested-With: XMLHttpRequest`。
浏览器里这个请求是**同域 ajax**，`Origin`、`Referer`、`X-XSRF-TOKEN` 由浏览器自动附加，
抓包时容易只记下「需要 X-Requested-With」；从 OkHttp 发就必须自己补齐这三个头。

**修复**（0.12.2）：新增 `WeiboHttp.ajaxRequest(url, cookie, referer, xsrf)`，
`Referer` 先用超话页 `https://weibo.com/p/<containerid>`，403 时回退 `https://weibo.com/`
再试一次。修复后评论/点赞一次通过。

> 教训：凡是 `weibo.com` 侧 `/ajax*`、`/ajax_proxy*` 接口，**默认按「浏览器全套同域头」发**，
> 不要按抓包记录里显式出现的那几个头去裁剪。

### 9.2 实测耗时（默认配置，2 条评论 + 2 次点赞）

| 时刻 | 事件 | 距上一步 |
| --- | --- | --- |
| 13:15:52 | 评论#1 成功 | —（节流器首调用不等待） |
| 13:16:46 | 评论#2 成功 | **54 秒**（配置 25–60 秒） |
| 13:16:47 | 点赞#1 成功 | **1 秒**（点赞 key 首调用） |
| 13:17:21 | 点赞#2 成功 | **34 秒**（配置 15–40 秒） |
| 13:17:21 | 本轮结束 | 合计 **89 秒** |

- 准备阶段（探活 + 超话列表 1 页 + 帖子流 1 次）约 2–4 秒。
- **发帖动作本身 0.5 秒内返回**（13:10:03.060→13:10:03.564，含落台账）。
- 结论：默认 4 评论 + 2 转发 + 3 点赞 + 1 帖的间隔累计约 165–440 秒，
  **发帖排最后就要等 3–7 分钟**，慢的不是请求而是排队。

> **0.12.6 已调整**：执行顺序改为「发帖 → 评论 → 转发 → 点赞」，发帖成为第一个动作，
> 点执行后 1–2 秒就发出；单轮预算也从 8 分钟放宽到 12 分钟（后台执行仍压到 9 分钟以内）。
> 上表保留是为记录当时的实测口径。

### 9.3 各动作的真实响应

| 动作 | 结果 | 备注 |
| --- | --- | --- |
| 超话发帖 | `OK`，返回 `remoteId`（mid） | 例 `5000000000000007`；正文自动追加 `#中国乒乓球队#` |
| 评论 | `OK`，返回**评论自身的 mid** | 目标帖 mid 与评论 mid 都要记（例目标 `5000000000000008` → 评论 `5000000000000009`） |
| 点赞 | `OK`，**不返回** `remoteId` | 拿不到任何可回查的 id，只能以 `ok` 判定 |
| 帖子流 | 8–10 条 `category=="feed"` | 4 类动作**共用同一个队列**逐个弹出，不会重复作用在同一条帖子上 |

### 9.4 签到与超话等级

- 手动签到 4 个超话全部成功（间隔 3–8 秒），超话等级随即从 **Lv.4 → Lv.5**（无限暖暖同样 +1）。
- 签到成功但**首页统计仍显示 0/4** —— 根因是 `CheckinService` 只在**签到前**保存超话快照，
  成功后没把 `done` 回写。已在 0.12.2 修复（成功后 `copy(done = true)` 再存一次并回调界面）。

### 9.5 幂等与计数

- 当日计数按 `uid + day` 落盘，`done` 记录成功次数；改配置次数不会重算已做过的量，
  第二轮 planner 只排「剩余额度」（实测：评论 count=3、已做 2 → 只排 1 条）。
- 发帖成功后再次运行不再排发帖（`done[POST] == postCount`），**不会重复发**。
- 预占 `pending` 在成功后清空；本次三轮运行后 `pending: []`，无残留。

### 9.6 WorkManager 的 `NetworkType.CONNECTED` 在国内等于「永不执行」

**现象**：打开「打开 App 时自动执行」后冷启动 App，logcat 显示
`已排入后台执行 id=...`，紧接着就是

```
WM-GreedyScheduler: Constraints not met: Cancelling work ID ...
WM-ConstraintTracker: NetworkState(isConnected=true, isValidated=false, isMetered=true, ...)
```

Worker **一次都没执行**，界面上也看不出任何异常。

**根因**：WorkManager 判 `NetworkType.CONNECTED` 时会看 `NetworkState.isValidated`。
这个标志来自系统对 `connectivitycheck.gstatic.com/generate_204` 的探测；
国内网络访问不到，`isValidated` 恒为 `false`（模拟器里同样是 `isValidated=false`）。
结果就是「排期成功 → 立刻被判约束不满足 → 取消」，而且不报错。

**修复**（0.12.3）：去掉 `setRequiredNetworkType(NetworkType.CONNECTED)`。
网络确实不通时，`EngageService` 的失败分类（`NETWORK`）与熔断会兜住，
最多烧掉一轮 8 分钟预算，比「永远不执行」好。

> 凡是依赖 WorkManager 网络约束的定时任务，都要考虑这条：它检查的是
> 「网络是否被系统验证通过」，不是「能不能上网」。

### 9.7 其余路径的实测结论（同一轮）

| 路径 | 结果 |
| --- | --- |
| 转发 | ✅ `OK`，目标 mid `5000000000000004`，**不返回** remoteId |
| 发帖前确认模式 | ✅ 生成 `AWAITING_CONFIRM` 台账 → 点「确认发送」→ 真实发出（mid `5000000000000005`） |
| UNCERTAIN 人工收敛 | ✅ 点「确认未发出」→ 台账转 `FAILED`、`pending` 清空、当日解锁 |
| 免打扰时段 | ✅ 拦截并提示「当前处于免打扰时段（13–14 点）」，不产生运行记录 |
| 签到幂等 | ✅ 已签状态再点签到 → 「4 个超话，4 已签，待签 0」，不发请求 |
| 文案新增 / 批量导入 / 软删 | ✅ 计数从 3→4→5，「接受 1 · 重复 0」，删除后「可用 4 / 共 5」 |
| 关闭任务 / 清除数据 | ✅ 清除后配置/文案/记录/台账全空，**登录账号保留**（账号不属于任务数据） |

**设备时区坑**：模拟器是 UTC，设备时间比东八区慢 8 小时。测免打扰时段时必须按
`adb shell date` 的结果去设，否则会得出「拦截失效」的错误结论。
