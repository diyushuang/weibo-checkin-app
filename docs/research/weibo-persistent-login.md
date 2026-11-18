# 微博账号「持久登录」可行方案研究

> 范围：**登录态本身的生命周期**——一次登录之后，登录态能不能长期留在软件里、后续怎么复用、什么时候会失效、失效了怎么办。
> 不涉及「免登录能做什么」（那是 L0/L1 游客态，见 `weibo-guest-endpoint-audit.md`），也不涉及微博开放平台 OAuth 的完整接口面。
>
> 版本锚：`v0.10.1` / versionCode 34 / HEAD `aa56cdf`　｜　研究日期：2026-11-15
>
> 证据标记：`【代码】`=可复现的**本仓库**事实（文件:函数）｜`【代码·项目名】`=外部项目源码事实｜`【文档】`=已有文档结论（标章节）｜`【实测·guest A】`=guest 文档 2026-10-30 实测记录｜`【实证·来源 日期】`=第三方做过的认证态实测（标来源与日期，本文未复现）｜`【社区口径】`=社区材料结论，本仓未验证｜`【推断】`=逻辑推导｜`【待验证】`=需要真机/真账号才能确认
>
> 版本说明：首版 2026-11-15 上午；同日 18:30 依 GitHub 生态检索补入 §4.4（ALC 重签的现成实现）、§4.5（GitHub 项目横评）、§9.3（禁止调用的续期链）与 §3.2 的域分布修正。

---

## 1. 摘要

三句话：

1. **微博没有 OAuth 意义上的 refresh token，客户端无法"延长"登录态。** 这不是推断：社区已有认证态对照实测——唯一像"续期端点"的 `updatetgt`/`crossdomain` 链**调用后会直接作废现有凭证**（5 秒后 401，恢复原 cookie 也救不回）。客户端能做的只有三件事：把服务端换发的新 Cookie 及时收下并写回、尽早发现失效、失效后能可靠重登（重登 ≠ 续期，拿到的是一套全新凭据）。
2. **"续期"其实一直在发生，但机制是 Set-Cookie 回写，不是任何主动刷新接口。** 日常请求中微博会下发新的 `SUB`/`SUBP`/`SCF`/`ALF`（社区口径称滑动续期），本仓库 `WeiboHttp.mergeSetCookies` 收下这些头、`AccountStore.patchAccount` 判定关键字段变化后重记寿命锚点——这是"长期不掉线"的真正原因，比 `CookieLife.TTL_DAYS = 7` 这个经验兜底值更关键。
3. **社区材料普遍把 `SSOLoginState` / `XSRF-TOKEN` 的"2 小时"当成登录态寿命，这是错的。** 前者是一个 Unix 时间戳（记录登录时刻），后者是 CSRF 令牌；把它俩当寿命会直接推出"必须每 2 小时刷新一次"的错误结论。

| 维度 | 结论 | 依据 | 详见 |
|---|---|---|---|
| 可行路线 | 主路径 B（WebView 走官方登录页抓 Cookie）+ 存储层 C（全局 CookieJar 承接会话）+ 兜底 D（账密 SSO 重登）；E（ALC 重签）/F（二维码）是可选升级 | 【代码】现状已落地 B+D；【代码·WeiboTask】E 有现成实现 | §4 |
| 持久性上限 | 由服务端会话决定，客户端无法承诺"永久"。本仓库现状：**只要进程还能定期发一次探活请求，登录态通常能维持到服务端策略允许的上限**；进程长期不被调度（系统杀后台）则会退化为"登一次用几天" | 【推断】+【社区口径】 | §9 |
| 现状档位 | 已完整覆盖 L2 账号登录态：抓取→初筛→探活→入库→复用→保活→失效→重登 八步全通 | 【代码】 | §5 |
| 首要风险 | **Cookie 明文双份落盘**（prefs `wcm.xml` + WebView Cookies SQLite），一次设备取证即可拿到全部账号；其余功能风险都是可降级的 | 【代码】 | §11 |
| 最被低估的缺口 | 失效判据只覆盖 `login=false` 与 `ok=-100`，没有 302 跳登录页 / HTML 登录墙识别 → 会出现"假在线"（cookie 已死但本地仍显示正常） | 【代码】+【社区口径】 | §8 |

> 全文的元结论只有一条：**这是一个"观测 + 收敛"问题，不是一个"设计一套续期协议"问题。** 任何写成"我们实现了登录态自动续期"的段落都是错的。

---

## 2. 问题定义：什么叫「持久登录」达标

「立刻可用」和「长期可用」是两件事。前者的验收是"能发请求"，后者的验收必须是可测量的分布，否则"持久"永远是个形容词。

### 2.1 四个可验收指标

| 指标 | 定义 | 计算方式 | 数据来源 | 当前能否测 |
|---|---|---|---|---|
| **MTBF** | 平均无失效间隔 | 相邻两次「判定失效」之间的时长均值 | 账号行的 `cookieLoginAt` → `cookieLastCheckAt`（失效那次） | 部分可测（缺"失效判定来源"） |
| **首登→需重登天数** | 用户第一次登录到第一次必须人工重新登录的天数 | 按账号取分布，报 P50 / P90 | 同上 + 人工重登时间 | ❌ 无埋点 |
| **误判死率** | 服务端其实还认，但本地判成失效的比例 | 误判次数 / 总判死次数 | 需要"人工重登时探活仍 login=true"这条证据 | ❌ 无埋点 |
| **后台保活成功率** | 一轮 Worker 里 `alive / 总账号数` | `CheckInWorker` 已算出 `alive` 但未上报 | 【代码】`CheckInWorker.doWork` 局部变量 | ❌ 未落盘 |

> **诚实声明：本仓库目前没有任何持久登录相关的埋点。** 上表除 MTBF 可由账号行时间戳粗略反推外，其余全部为"待测"。这是本文最重要的一句限定——没有它，后面所有目标值都是编的。

### 2.2 最小埋点方案（4 个字段即可闭环）

| 字段 | 含义 | 写在哪 | 用途 |
|---|---|---|---|
| `loginAt` | 本次登录态的真实起点 | 已有：`Account.cookieLoginAt` | 分母 |
| `deadAt` | 判定失效的时刻 | 已有：`Account.cookieLastCheckAt`（判死那次） | 分子 |
| `deadSource` | 判死依据（`config_login_false` / `ok_-100` / `dead_after_relogin_fail`） | **缺** | 区分"真失效"与"误判" |
| `reloginOk` | 该次失效后自动重登是否成功、用了几次 | **缺**（只有 `reloginLastCode`） | 自愈率 |

【推断】把 `deadSource` 补上之后，"误判死率"立刻可算：凡是 `deadSource` 属于弱判据（见 §8）却最终人工登录成功且第一次探活就 `login=true` 的，都计入误判。

### 2.3 为什么「误判死」比「漏判死」更贵

| 错误类型 | 后果 | 用户感知 | 修复成本 |
|---|---|---|---|
| 误判死（把活判死） | 触发一次多余的重登；若账密也失效，则逼用户重新扫码 | 明明刚登录过，又被要求登录 | 高（用户信任损耗） |
| 漏判死（把死判活） | 继续用死 cookie 发请求，签到全失败、抓图全空 | "点了没反应"、"签到 0/7" | 中（可自愈一次） |

【推断】所以判据设计上应当**宁漏勿误**：只在强判据（`login=false` 且无换发信号）上判死，弱判据一律降级为"不确定 + 记 `cookieLastError`"。本仓库现状正是这个取向（`markDead` 只由 `login=false` 触发，见 §8.3）。

---

## 3. 登录态的本质：cookie 里到底哪一截是「登录」

一条 `Cookie` 头里混了五种性质完全不同的东西。不把它们掰开，后面所有的"有效期"与"判活"讨论都会建在错的假设上。

### 3.1 五类语义

| 字段 | 语义类别 | 社区口径有效期 | 可作判活依据？ | 本仓库现状 |
|---|---|---|---|---|
| `SUB` | **会话凭据**（主令牌） | 30 天 | 是（存在性 + 服务端 `login`） | `looksLoggedIn` 必需项；`LOGIN_CRITICAL_KEYS` |
| `SUBP` | **会话凭据**（权限令牌） | 30 天 | 否（仅用于换发监测） | `LOGIN_CRITICAL_KEYS` |
| `SCF` | 会话标记（安全 cookie 标识） | 30 天 | 弱（本地初筛条件之一） | `looksLoggedIn` |
| `ALF` | **时间戳**（服务端名义到期） | 30 天 | 是（到期来源之一） | `CookieLife.parseAlf` |
| `SSOLoginState` | **时间戳**（登录发生时刻） | 值本身是时刻戳，"2 小时"指刷新节奏 | 否（作寿命锚点） | `CookieLife.loginTimeOf` |
| `MLOGIN` | 会话标记（移动端登录标识） | 永久 | 弱（本地初筛条件之一） | `looksLoggedIn` |
| `ALC` | **会话凭据**（PC 端 SSO 长效凭证） | 6 天【社区口径】／「以月计」【社区口径，冲突】 | 否 | **未使用**（路线 E 的关键，见 §4.4） |
| `XSRF-TOKEN` | CSRF 令牌 | 2 小时 | 否 | **未处理** |
| `WBPSESS` | 会话令牌 | 2 小时 | 否 | 原样透传 |
| `mweibo_short_token` | 短令牌 | 2 小时 | 否 | 原样透传 |
| `_T_WM` / `WEIBOCN_FROM` / `M_WEIBOCN_PARAMS` | 设备标识 | 永久 | 否 | 原样透传 |

> 表注：有效期列摘自 guest 文档 §2.4（原始来源为社区整理的微博搜索 API 文档）；本表新增「语义类别」与「可作判活依据」两列，这是本文与 guest 文档的唯一交集，其余不重复。

### 3.2 域与会话：同一账号的 SUB 分布在哪里

| 域 | 谁签发 | 谁能用 | 本仓库怎么处理 |
|---|---|---|---|
| `.weibo.cn` | m 站通道（`source=wapssowb`） | `m.weibo.cn/*`（签到、超话、时间线） | `cookieVariants()` 取 `m.weibo.cn` 的 cookie，参与「m 域优先」合并 |
| `.weibo.com` | 主站通道（`source=wapsso`）/ PC 端 | `weibo.com/ajax/*`（昵称、相册墙、单帖详情） | 同上，参与「主站优先」兜底合并 |
| `.sina.com.cn`（passport 侧） | SSO 登录中心 | 仅用于跨域分发会话 | 取 `passport.weibo.com` 的 cookie 一起合并 |

【代码】`LoginWebViewActivity.cookieVariants()` 明确返回两种合并顺序（`[passport, weibo.com, m]` 与 `[passport, m, weibo.com]`），并对两套都做服务端验证，取第一个 `login=true` 的。这不是"多此一举"，而是承认了一件事：**同名 cookie 谁覆盖谁，本仓库没有权威答案。**

> **重要修正（2026-11-15 补）：域分布取决于登录入口，不是恒定"三份 SUB"。**
> 【实证·findxyz 2026-07】一次 PC 端二维码登录稳定产出 15 个 cookie，分布是：
> `.weibo.com` = `SUB`/`SUBP`/`SSOLoginState`/`ALF`（**唯一的 SUB**）；`.weibo.cn` = 只有 `SSOLoginState`/`ALF`（**没有 SUB**）；`.passport.weibo.com` = `SRF`/`SRT`；`.krcom.cn` = `SUB`/`SUBP`/`ALF`。
>
> 也就是说：**PC 扫码只写 `.weibo.com`，`.weibo.cn` 上根本没有会话** —— 这恰好从反面印证了本仓库为什么必须在 `startLoginFlow()` 里走 m 站入口（`source=wapssowb`）。同一个账号"有几份 SUB"取决于它从哪个入口登录过，不能假设恒定三份。上文那句"三域各持一份"应读作"三域**可能**各持一份，取决于入口"。

### 3.3 三个必须掐掉的常见误读

| 误读 | 实际 | 后果 |
|---|---|---|
| "`SUB` 有效期 30 天，所以登录 30 天不用管" | `Expires` 只约束**客户端何时删除**，不约束服务端认多久【社区口径】 | 等服务端作废才发现，中间几天签到全失败 |
| "`SSOLoginState` 只有 2 小时，得每 2 小时刷新" | 它的值是登录时刻的 Unix 时间戳；本仓库用它算寿命锚点，不用它判活【代码】`CookieLife.loginTimeOf` | 把刷新频率提到 2 小时，纯属自伤（风控 + 耗电） |
| "一条 Cookie 头 = 一个会话" | 同一账号在不同域上**可能**各有一份 SUB（取决于从哪个入口登录，见上一条修正），值还不一样 | 合并串里被覆盖掉的那一份可能才是 m 站认的（见 §7.3） |

---

## 4. 可行路线对比

一共 8 条能走的路。先给全景，再逐条给结论；被否决的三条单独给硬理由，不做"各有利弊"式的和稀泥。

### 4.1 路线矩阵

| 路线 | 凭据来源 | 需人工 | 凭据暴露面 | 抗协议变更 | 失效后自愈 | 多账号成本 | 本仓库改动量 | 触碰「账密不经应用」 | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| **A** 手动粘贴 Cookie | 用户从浏览器复制 | 每次 | 明文入 prefs，用户常在公开渠道传 cookie | 高（不依赖登录协议） | ❌ 完全不能 | 低（粘一次一个） | ~2 文件 | 否 | **仅作应急导入**，不作主路径 |
| **B** WebView 官方登录 | 微博官方页面签发 | **每账号一次** | 明文入 prefs + WebView cookie 库 | 中（登录页改版即要跟） | ❌ | 中（每个号进一次登录页） | **已实现** | 否（账密不经应用） | **主路径** |
| **C** 全局 CookieJar | 承接 B/D 的 Set-Cookie | 无 | 与 B/D 同（不新增暴露面） | 高（HTTP 层通用） | —（是存储层，不产生凭据） | 低（按域自动隔离） | ~3 文件 | 否 | **必须补的存储层**（§7） |
| **D** 账密 SSO 重登 | 用户存的账号密码 → `login.sina.com.cn` | 首次存密码 | **密码经应用**（Keystore 加密）+ 登录请求过网 | 中（`savestate`/`retcode` 会变） | ✅ 全自动 | 中（每号存一份密码） | **已实现** | **是**（需显式开关 + 风险声明） | **兜底** |
| **E** ALC 重签 | 持 `ALC`（SSO 长效凭证）向 SSO 换新 `SUB` | 无 | 只需 `ALC` 一个字段，比存密码小得多 | 中低（依赖 SSO 端点稳定） | ✅ 全自动 | 低 | ~3 文件 | **否**（不碰密码） | **优先级最高的可选升级**【社区口径，待验证】 |
| **F** 二维码登录 | `passport.weibo.com/sso/v2/qrcode/*` | 每次扫码 | 同 B | 中 | ❌（仍需人工扫） | 中 | ~4 文件 | **否**（免存密码） | **D 的安全替代**，代价是换掉"零人工"【待验证】 |
| **G** OAuth 开放平台 | `open.weibo.com` | 授权一次 | `access_token` 落盘 | 高（官方接口） | ✅（有 `refresh_token`） | 低 | 大 | 否 | ❌ **否决**：能力不匹配 |
| **H** 逆向 App 端签名 | `api.weibo.cn/2/*` | — | `gsid` + 私有签名 | 低（随版本失效） | — | — | 大 | — | ❌ **否决**：投入产出比极差 |

### 4.2 路线与代码落点

| 路线 | 现有实现 | 缺口 |
|---|---|---|
| A | ❌ 无粘贴入口（全仓无相关 UI/解析） | 若要做，需一个"解析 Cookie 头 → 落库"的入口，复用 `WeiboHttp.parseCookieString` + `AccountStore.upsert` |
| B | `LoginWebViewActivity`（`startLoginFlow` / `pollLogin` / `verifyAsync` / `onVerified`） | 登录成功后不清 WebView cookie 库（G2）；uid 缺失即不入库（G5） |
| C | 仅 `WeiboPasswordLogin.MemoryJar`（局部、用完即弃） | 无全局 CookieJar（G3） |
| D | `WeiboPasswordLogin` + `AutoRelogin` + `ReloginPolicy` + `CredentialVault` | 无（策略已完整） |
| E | ❌ 无 | 全缺 |
| F | ❌ 无 | 全缺 |
| G | 已核实不可行（guest §7） | — |
| H | 已明确不接（guest §7） | — |

### 4.3 否决理由（不是"不划算"，是"走不通"）

| 路线 | 硬理由 |
|---|---|
| **G** OAuth | 微博开放平台**没有超话签到接口**，也**没有「我关注的超话列表」**；`statuses/user_timeline` 只返回本人最新 5 条。即使 `refresh_token` 能无限续，也换不来签到能力。【文档】guest §7 |
| **H** App 端签名 | `api.weibo.cn/2/*` 需 `gsid` + App 私有签名（`s`/`from`/`c`），随客户端版本失效；且拿不到"我关注的超话"语义。【文档】guest §7 |
| **C 单独使用** | CookieJar 只承接会话，不产生凭据；把它当"路线"会误以为它能解决首次登录 |

### 4.4 值得单独说明的两条：E 与 F

**路线 E（ALC 重签）——本次研究最重要的发现，且已找到现成实现。**

【社区口径】微博/新浪共用 SSO 登录中心 `login.sina.com.cn`。登录成功时除 `SUB` 外还会下发 `ALC`（PC 端长效凭证）。当 `SUB` 失效后，**携带 `ALC` 访问 SSO 端点即可换回新的 `SUB`，不需要重新输密码**。

【代码·WeiboTask `src/WeiboClient/loginByCookies.go`】这条不是传闻，`happy888888/WeiboTask`（Go，149★，2026-09 仍在更新）就是纯 ALC 方案，README 明写「本项目**不会使用任何账号密码**，仅需要新浪网一个名为 `ALC` 的 cookie」，`config.json` 缓存 cookie 以「保存和刷新登录状态，使登录状态一直有效而不必重复登录」。它的实现形态值得照抄思路：

| 环节 | WeiboTask 的做法 |
|---|---|
| 身份载体 | 只要一个 `ALC`（连 `SUB` 都不需要预先有） |
| 重签入口（主站） | `GET login.sina.com.cn/sso/login.php?gateway=1&service=miniblog&entry=miniblog&useticket=1&returntype=META&url=<weibo.com 目标页>` —— 关键是 `useticket=1`，凭票据（ALC）换会话 |
| 重签入口（m 站） | 同端点换 `service=sinawap&entry=sinawap&url=<m.weibo.cn/profile/info>` |
| 应答形态 | `returntype=META` 返回一段 HTML，正则提 `location.replace("…")` 拿跳转地址 |
| 二次交换 | 落地页返回 `setCrossDomainUrlList({"arrURL":[…]})`，再 GET `arrURL[0] + &callback=sinaSSOController.doCrossDomainCallBack&scriptId=ssoscript0&client=ssologin.js(v1.4.2)`，此时才落 `SUB` |
| 判活 | `m.weibo.cn/profile/info` 返回 `application/json` = 已登录（HTML = 未登录）；`weibo.com/aj/account/watermark` 同口径 |
| Cookie 存储 | 按 6 个域分别存取：`.login.sina.com.cn`、`.m.weibo.cn`、`huati.weibo.cn`、`.sina.com.cn`、`.weibo.com`、`.weibo.cn` |

这条路对本仓库的价值：

| 维度 | 现状（路线 D） | 换成/补上 E |
|---|---|---|
| 需存的东西 | 账号 + **密码** | 只需 `ALC` 一个 cookie 字段 |
| 用户心理成本 | 高（"把微博密码交给 App"） | 低（同 B，就是一份 cookie） |
| 密码错/验证码导致的硬停 | 存在（`HARD_STOP_CODES` 含 `bad_credentials`/`needs_manual`） | 不存在这类失败模式 |
| 失效恢复 | 全自动 | 全自动（`ALC` 自身过期后仍需人工） |
| 与现状的兼容性 | — | 可并存：`AutoRelogin` 里按"有凭证→走账密，有 ALC→走重签"分流 |

> **必须精确措辞：E 是「重签 / 重新登录」，不是「续期」。**
> 它靠的是 SSO 票据再换一次会话，每次换出来的是**一套全新的凭据**（`SSOLoginState` 会变）。把它写成"延长登录态"会误导后续维护者去猜"续期接口"，而那个接口并不存在（见下方警告）。
>
> ⚠️ **绝对不要尝试的接口**：`login.sina.com.cn/sso/updatetgt.php` + `sso/crossdomain.php` 那条"旧版 SSO Cookie 更新链"。findxyz 在 2026-07 做过认证态对照实测：调用后 5 秒，同一受保护接口返回 401，**原凭证被直接废掉**，且响应里没有新的 `.weibo.com` `SUB`/`SUBP`（只有 `SSOLoginState`/`ALF`，`.weibo.cn` 侧甚至下发 `SUB=deleted`）。根因是扫码登录产出的 `SUBP` 不含旧更新器所需的 `et` 字段，`getCookieExpireTime()` 返回 `retcode=6102`，永远不会进入更新窗口——但手写调用会绕过这些前置检查，于是"续期"变成"自杀"。

【待验证】两个关键未知：①`ALC` 的真实有效期（社区口径 6 天 vs "以月计"互相冲突）；②WeiboTask 的 `login.php` 重签链在 2026-11 是否仍返回可用 `SUB`（该项目 2026-09 有更新，可作旁证但非实测）。**在实测确认前，E 只能作为"可选升级"记录，不能替换 D。**

**路线 F（二维码登录）**：`passport.weibo.com/sso/v2/qrcode/image` 取 qrid 与二维码 → `.../qrcode/check` 轮询扫码状态 → 访问跳转 URL 拿 PC 端 cookie → 再访问 `m.weibo.cn` 拿 m 站 cookie。【待验证】

它的定位不是"更持久"，而是**用"扫一次码"换掉"存一份密码"**：在用户明确拒绝账密托管时，F 是唯一还能保住自动化的选项。但要注意 §3.2 的实证：**PC 扫码只写 `.weibo.com`，拿不到 m 站会话**，签到链路仍需补一次 m 站登录。

### 4.5 生态对照：GitHub 上别人怎么解决「登录一次长期有效」

检索日期 2026-11-15。按「对本仓库的可借鉴度」排序，不是按 star 数。结论先说：**没人在做"真正延长登录态"这件事——做得到的人都只在做"重签"，做不到的人就接受"定时掉线 + 重新登"。**

| # | 项目 | 语言/规模 | 最近更新 | 它怎么处理"长期不用重登" | 对本仓库的用处 |
|---|---|---|---|---|---|
| 1 | **happy888888/WeiboTask** | Go · 149★ | 2026-09 | **纯 ALC 方案**：不要账号密码，只存一个 `ALC`，每次任务用 `sso/login.php?useticket=1` 现换新 `SUB`，配置里缓存的 cookie 反过来又被刷新 | **路线 E 的现成实现**（§4.4），端点与参数可直接对照 |
| 2 | **findxyz/vb-weibo-plus** | HTML · 14★ | 2026-11 | 二维码登录 + 一份 `docs/research/weibo-renewal.md`：**用认证态对照实测证否了主动续期**，结论是"401 后重新扫码" | **本文最强的一手反面证据**：`updatetgt`/`crossdomain` 链会销毁凭证（§9.4） |
| 3 | 88lin/weibo-checkin | Python · 1★ | 2026-10 | 纯标准库零依赖；**Cookie 体检 + 失效告警**、时间抖动/随机间隔/指数退避、dry-run | 与本文 §8/§9 取向最接近的同类，可对照判据设计 |
| 4 | BeacherZ/dailycheckin-plus | Python · 9★ | 2026-11 | 多站点 Docker 面板；**粘贴 cookie 即自动规范化 + 在线校验是否有效** | 路线 A（手动导入）的产品化形态；"导入即校验"值得借鉴 |
| 5 | imhuimie/WeiBo | Python · 259★ | 2025-11 | 超话签到脚本，cookie 静态注入 | 反例：不处理续期，靠用户定期换 cookie |
| 6 | Hellager/weibo_supertopic_sign | Python · 98★ | 2026-09 | 云函数/青龙双跑，多账号 | 部署形态参考 |
| 7 | kuku3863/sign_weibo_chaohua | Python · 90★ | 2026-11 | 青龙面板 + 多账号 + 推送 | 同上 |
| 8 | fish2018/pansou `plugin/weibo` | Go | 活跃 | cookie 字段有效期表 + **二维码登录完整链路** | 本文 §3.1 字段表的原始出处；路线 F 参考 |
| 9 | zhouyi207/WeiBoCrawler | Rust/Tauri | — | `qrcode.rs` + `session.rs`：QR 会话用**内存 Jar 保持 client 复用**，登录后导出完整 cookie 落库 | Rust 侧的"会话对象"设计，与 §7.1 的 CookieJar 模型同构 |
| 10 | jackwener/weibo-cli | Python | — | `auth.py` 二维码登录 + 本地凭证 | 命令行场景的持久化做法 |
| 11 | dataabc/weiboSpider、weibo-crawler | Python · 4.7k/9.7k★ | 活跃 | 大爬虫，cookie 由配置静态注入 | 反例：规模大但完全不做持久化 |
| 12 | qii/weiciyuan、PaperAirplane/BlackLight、gaoxipeng/Android_16_Weibo | Java/Kotlin | — | 第三方 Android 客户端，走 **OAuth SDK** 或自建协议 | **对照结论：Android 域几乎没有"WebView 抓 cookie + 多账号持久化"的同类** |

> 三点观察：
> 1. **"续期"在生态里普遍是伪概念**：第 5/6/7/11 类项目全部默认"cookie 会掉，掉了就换"，只有第 1、2 项真正研究过机制，而它们的结论分别是"可以重签"和"不能续期"。
> 2. **工艺最接近我们的是 88lin/weibo-checkin**（Cookie 体检 + 退避 + 告警），但它是单机脚本、不涉及存储加密与多账号 UI。
> 3. **本仓库在 Android 侧是少数派**：同类客户端都走 OAuth（拿不到签到能力），走 cookie 的都是脚本/服务端。这既是差异化，也意味着 §7 的 CookieJar 改造没有现成参考实现可抄。

### 4.6 本节结论

> 定型一句话：**主路径 B + 存储层 C + 兜底 D；A 仅作应急导入；E 是唯一能同时降低"存密码"与"人工重登"的可选升级；F 是 D 的安全替代；G/H 否决。**

---

## 5. 本仓库现状：一条八步流水线

先给一条可逐行核对的链，再收敛缺口。缺口必须编号，否则后文建议会散落一地。

### 5.1 八步流水线

| 步 | 动作 | 代码位置 | 输入 → 输出 | 失败分支 | 判定 |
|---|---|---|---|---|---|
| 1 | 进入登录页 | `LoginWebViewActivity.onCreate` / `startLoginFlow` | 空会话 → WebView 加载 m 站登录入口 | 导航被拦 → 状态行显示 host+path | 已实现 |
| 2 | 抓取 Cookie | 同上 `cookieVariants` | `CookieManager` 三域 → 两种合并串 | 三域皆空 → 静默轮询 | 已实现 |
| 3 | 本地初筛 | 同上 `looksLoggedIn` | cookie → bool | 裸 SUB（访客）→ 继续轮询 | 已实现 |
| 4 | 服务端探活 | `WeiboAuthApi.verify(force=true)` | cookie → `login/st/uid/serverExpiresAt/loginAt` | 非 JSON → 抛 `identitySuspect` | 已实现 |
| 5 | 入库 | 同上 `onVerified` → `AccountStore.upsert` | 验证结果 → 账号行 | 无 uid → 重试 3 次后放弃（**cookie 悬空在 WebView**） | 有差异（G5） |
| 6 | 复用 | `WeiboHttp.request` 手工 `header("Cookie", …)` | 账号行 cookie → 出站请求 | — | 有差异（G3） |
| 7 | 保活 | `CheckInWorker.schedule`（6h±1h）→ 先 `tryAutoRelogin` 再 `verify` | 账号表 → `markAlive` / `markDead` / `markCheckError` | 网络/风控 → 只写 `cookieLastError` | 已实现 |
| 8 | 失效与重登 | `AccountStore.markDead` / `AutoRelogin.tryAutoRelogin` | 失效账号 → 重登或标死 | 硬停（密码错/验证码/凭证读不出） | 已实现 |

### 5.2 缺口清单

| ID | 缺口 | 症状 | 影响 | 加固见 |
|---|---|---|---|---|
| **G1** | cookie 明文落盘 | `AccountStore.serializeAccounts` 把整串 cookie 原样写进 prefs JSON | 设备取证/root/ADB 备份即可拿到全部账号 | §11 H1 |
| **G2** | WebView cookie 库残留 | 登录成功后从不清理；删账号也只删 prefs 行 | 第二条明文路径；换账号/共享设备时串号风险 | §11 H2 |
| **G3** | 无全局 CookieJar | Set-Cookie 只在探活路径被 `mergeSetCookies` 回收；属性（Domain/Path/Expires）在 `parseCookieString` 阶段被丢弃 | 业务请求里服务端换发的 cookie 直接丢掉；域路由靠"两种合并顺序"猜 | §11 H3 |
| **G4** | 失效判据不全 | 只认 `login=false` 与 `ok=-100`；无 302/HTML 登录墙识别 | "假在线"：cookie 已死，本地仍显示正常，签到静默失败 | §11 H4 |
| **G5** | uid 缺失即不入库 | `onVerified` 在 `uidRetries` 用尽后 `Toast + finish()` | 用户以为登录成功了，实际什么都没存；cookie 悬空在 WebView | §11 H5 |
| **G6** | 内存长期持明文 | `WeiboAuthApi.verifyCache` 是进程级静态，缓存整串 cookie 90 秒 | 内存 dump 可拿到明文；无隔离 | §11 H6 |
| **G7** | `XSRF-TOKEN` 未处理 | 全仓无 `X-XSRF-TOKEN` 头，无 token 刷新逻辑 | 一旦接 `weibo.com/ajax/*` 的写接口就是 403；读接口目前侥幸可用 | §11 H7 |

---

## 6. 持久化面：存哪、存成什么、谁能读、何时清

持久化的难点不在"写进磁盘"，而在**五份数据各自以为自己是权威**。

### 6.1 五处存储

| 存储 | 内容 | 形态 | 生命周期 | 清理时机 | 风险 |
|---|---|---|---|---|---|
| `SharedPreferences("wcm")` 的 `accounts` | 账号表（含整串 cookie） | JSON 数组，**明文** | 直到删账号 | 仅 `AccountStore.remove` | G1 |
| WebView Cookies SQLite | 三域 cookie 原样 | 平台私有 DB，**明文** | 永久 | 只在下次进登录页 `removeAllCookies` | G2 |
| `SharedPreferences("wcm_guest")` | 游客身份 cookie | 明文，TTL 365 天 | 一年 | 探活失败重握手 | 低（L1 身份，不能代表"我"） |
| `SharedPreferences("wcm_credentials")` | 账号密码 | `v1:<iv>:<ct>`，Keystore AES-256-GCM | 直到删账号 | `CredentialVault.clear` | 低（唯一加密项） |
| 进程内存 `verifyCache` | 整串 cookie + 探活结果 | 静态变量 | 90 秒或进程死亡 | 覆盖/进程结束 | G6 |

> 一句话：**这个 App 把最敏感的东西（cookie）用最不安全的方式存了两份，把次敏感的东西（密码）用最安全的方式存了一份。**

### 6.2 Cookie 属性保真度

| 属性 | WebView 是否提供 | 本仓库是否保留 | 丢失后果 | 何时必须保留 |
|---|---|---|---|---|
| `Domain` | ✅ | ❌ `parseCookieString` 只取 `name=value` | 无法判断"这份 SUB 属于哪个域"，只能靠合并顺序兜 | **做域路由时**（§7.3） |
| `Path` | ✅ | ❌ | 同一域下不同 path 的 cookie 会被错误合并（微博当前少见） | 接 Web 端 `/ajax/*` 时 |
| `Expires` / `Max-Age` | ✅ | ⚠️ 仅从响应头单独取（`serverExpiryFromHeaders`），不落到单条 cookie 上 | 单条 cookie 的真实到期无法回溯，只能靠 `ALF` 近似 | 精确到期展示 |
| `Secure` / `HttpOnly` | ✅ | ❌ | 无法在代码层阻止把 cookie 发到 http（当前靠 `weiboUrl()` 白名单硬拦） | 出站校验 |
| `SameSite` | ✅ | ❌ | 与客户端请求无关（只影响浏览器） | 不需要 |

【推断】属性丢失的直接代价是：**结构化域路由在现有存储格式下"做不到"，只能降级为"多份合并 + 逐份试"。** 这是 §3.2 那个"两种合并顺序"的根本原因，不是实现偷懒。

### 6.3 并发写

| 写入方 | 是否持锁 | 冲突表现 | 现状 |
|---|---|---|---|
| 签到收尾 / 探活 / 轮换（前台各 ViewModel） | ✅ `AccountStore.LOCK` | 无（load-modify-save 在锁内） | 已处理 |
| `CheckInWorker` 整轮 | ⚠️ 单条走 `patchAccount`（持锁），整轮结尾 `mergeSave` | 旧快照覆盖新 cookie | 已修（`mergeSave` 做合并而非覆盖，BUG-010） |
| `AutoRelogin` | ⚠️ 入口 `account.load` 重读一次 | 用旧对象判定策略 | 已处理（`tryAutoRelogin` 首行重读） |

> 本节结论：**并发面已经处理干净，不是风险点。** 真正的风险在"存的是明文"和"属性被丢掉"，不在"会不会写坏"。

---

## 7. 复用面：Cookie 怎么被带进请求

现状是「取出来、拼成串、塞进 header」。这条路能跑，但它把三件事一并丢掉了：属性、回收、路由。

### 7.1 三种注入模型

| 维度 | 手工 header（现状） | 全局 CookieJar | WebView `CookieManager` 桥接 |
|---|---|---|---|
| 属性保真 | ❌ 只剩 `name=value` | ✅ `Cookie` 对象全字段 | ✅ |
| Set-Cookie 回收 | ⚠️ 仅在使用 `mergeSetCookies` 的路径上 | ✅ 每个响应自动合并 | ✅ 但需主动 flush |
| 域匹配 | ❌ 靠调用方自觉 | ✅ `Cookie.matches(url)` | ✅ |
| 并发安全 | ⚠️ 调用方拼串，可能拼错 | ✅ 单点 | ⚠️ 需主线程 |
| 主线程约束 | 无 | 无 | **`CookieManager` 必须在主线程调用** |
| 改动面 | 0（现状） | 3 文件 + 所有 `request()` 调用点改为传账号 | 4 文件 + 主线程调度 |
| 适合的场景 | 单账号、少量端点 | **多账号、多域** | 与 WebView 登录页共用会话 |

### 7.2 无 CookieJar 的五个后果

| 后果 | 触发条件 | 是否已出现 | 代码证据 |
|---|---|---|---|
| 业务请求里换发的 cookie 被丢掉 | 签到/详情响应带 `Set-Cookie` | 已存在但不可见（看不出丢了什么） | 只有 `WeiboAuthApi.verify` 调 `mergeSetCookies` |
| 同名 cookie 覆盖顺序靠猜 | 三域都有 `SUB` | ✅ 已出现 | `cookieVariants()` 返回两种顺序 |
| 无法判断 cookie 是否适用于该 URL | 向 `weibo.com/ajax/*` 发账号 cookie | ✅ 已出现 | `WeiboAuthApi.webProfileName` 注释：「账号 cookie 的域是 `.weibo.cn`，Web 端不一定认」 |
| 到期时间只能靠 `ALF` 近似 | 服务端未回 `ALF` | ✅ 已出现 | `CookieLife` 用 `TTL_DAYS=7` 兜底 |
| 无法逐条淘汰过期 cookie | 长期运行 | 未处理 | 无任何按条过期的逻辑 |

### 7.3 域路由矩阵

| 出站目标 | 应带哪份 cookie | 需要的附加头 | 现状 | 风险 |
|---|---|---|---|---|
| `m.weibo.cn/*`（签到、超话、时间线） | `.weibo.cn` 的 SUB 优先 | `UA`(iPhone)、`Referer: m.weibo.cn`、`MWeibo-Pwa: 1`、`X-Requested-With` | ✅ 已按此构造 | 低 |
| `weibo.com/ajax/*`（昵称、相册墙、单帖） | `.weibo.com` 的 SUB（或游客 cookie） | `WEB_UA`、`Referer: weibo.com/u/<uid>` | ⚠️ 用整串合并 cookie，未做域裁剪 | 中（m 站 SUB 可能污染） |
| `passport.weibo.com` / `login.sina.com.cn` | 无需预置（由响应下发） | 桌面 UA | ✅ | 低 |
| `*.sinaimg.cn`（CDN） | 不需要 | `Referer` | ✅ | 低 |
| Web 端写接口（当前未接） | `.weibo.com` | **`X-XSRF-TOKEN`** | ❌ 无 | G7 |

### 7.4 本节结论

> CookieJar 的收益不是"少写一行 header"，而是**属性可寻址 + Set-Cookie 自动回收**。但必须同步承认它的代价：`CookieManager` 需主线程、`flush()` 是异步的，直接照搬 WebView 方案会把登录页的主线程约束带进整个网络层。**推荐做法是 OkHttp 侧自建 CookieJar（按 `(name, domain)` 存），只在登录页那一步从 `CookieManager` 单向导入。**

---

## 8. 失效判据矩阵

这是全文的核心。判断"登录态没了"从来不是看一个字段，而是看一组信号的**强弱与方向**。

### 8.1 十三个信号

| # | 信号源 | 观测点 | 判据 | 强度 | 误判方向 | 建议动作 | 现状覆盖 |
|---|---|---|---|---|---|---|---|
| S1 | 本地结构 | 账号行 cookie | 空串 | 强 | 无 | 判失效 | ✅ |
| S2 | 本地结构 | cookie 字段集合 | 有 `SUB` 但无 `SCF`/`SSOLoginState`/`ALF`/`MLOGIN` | 中 | 把活判死（若字段集变更） | 视为"疑似访客"，不判死，走一次探活 | ✅ `looksLoggedIn`（仅登录页） |
| S3 | 本地时间 | `CookieLife.describe` | `EXPIRED` | 弱 | **把活判死**（TTL 是经验值） | 触发重登，不直接判死 | ✅ |
| S4 | 本地时间 | 同上 | `EXPIRING`（≤2 天） | 弱 | 把活判死 | **主动换票时机**，见 §9 | ✅ |
| S5 | 探活 | `m.weibo.cn/api/config` | `data.login == false` | **强** | 把死判活（若解析漏） | 判死（当前唯一强判据） | ✅ |
| S6 | 探活 | 同上 | 响应非 JSON | 中 | 把活判死（把风控页当未登录） | 抛 `identitySuspect`，**不判死** | ✅ |
| S7 | 业务 | 超话列表/详情 | `ok == -100` | 中 | 把活判死（偶发） | 记 `lastError`，连续 2 次再判死 | ✅ 单次即抛 |
| S8 | 业务 | `weibo.com/ajax/*` | `ok == 0` 且文案含"请先登录/未登录/visitor/风控" | 中 | 把活判死 | 同上 | ✅ |
| S9 | HTTP | 任意响应 | `401` / `403` | 中 | 把活判死（403 常是风控不是掉登录） | 记原因，不判死 | ⚠️ 当前归入"风控提示" |
| S10 | HTTP | 任意响应 | `429` / `432` | **弱** | **把活判死** | 退避，绝不判死 | ✅ |
| S11 | 重定向 | `Location` 含 `signin`/`login`，或 body 是 `Sina Visitor System` HTML | 登录墙 | 强（需补） | 把死判活（当前漏） | 判死 | ❌ **G4** |
| S12 | 换发 | 响应 `Set-Cookie` | 关键字段值变化 | **正向信号**（不是失效） | — | 重记 `cookieRotatedAt` | ✅ `patchAccount` |
| S13 | 身份漂移 | 探活响应 | 返回 `uid` ≠ 账号 uid | 强 | 无 | 判死并告警（串号） | ❌ 未校验 |

### 8.2 禁止判死清单

| 现象 | 为什么不能判死 | 现状怎么做 |
|---|---|---|
| 连接超时 / IO 异常 | 断网不是掉登录 | `markCheckError`（§8.3） |
| DNS 失败 / 无网络 | 同上 | 同上 |
| `5xx` | 服务端故障 | `WeiboHttp.httpFailure` → 上层记原因 |
| 非 JSON 响应（风控页/HTML） | 可能是风控页而非登录墙 | 抛 `identitySuspect`，不写 `dead` |
| `429` / `432` | 频率/风控 | 只记原因 |
| `403` | 多为风控或权限，不是掉登录 | 只记原因 |
| `errno = 100015` | 签到 `st` 过期，**不是 cookie 死** | 重探活换 `st` 再试一次（`WeiboTopicApi`） |
| 单次 `ok = -100` | 偶发 | 建议连续 2 次才判死（现状单次即抛，属 G4 子项） |

### 8.3 三态决策规则

| 状态 | 判据组合 | 允许的动作 |
|---|---|---|
| **确定有效** | S5 为真，且无 S11 | 更新 `seenAt`；收下 S12 换发；**不推进寿命锚点**（除非 S12） |
| **确定失效** | S5 为假 **或** S11 命中 **或** S13 命中 | 写 `dead=true` + 原因；触发重登；**不清 cookie**（保留证据） |
| **不确定** | S1/S6/S9/S10 命中，或网络类异常 | 只写 `cookieLastError` + `cookieLastCheckAt`；不写 `dead`；**业务层仍允许尝试一次** |

### 8.4 判据强度排序与本节结论

> 强度排序：**S5（`login=false`）> S11（登录墙）> S13（uid 漂移）> S7/S8（业务 `-100`/未登录文案）> S2（结构异常）> S3（推算到期）> S4（临期）> S9/S10（HTTP 码）**。
>
> 三个缺口必须点明：①**没有 S11**，所以"假在线"是当前最现实的故障模式——cookie 已死但所有接口都返回 200 + 一个空列表，本地仍显示"有效 3 天"；②**没有 S13**，串号（多账号 cookie 写错行）不会被发现；③**`dead` 之后不清 cookie、也不拦业务请求**，一个被判死的账号仍可能被调用方拿去发请求。

---

## 9. 保活与续期策略

### 9.1 保活参数

| 参数 | 值 | 出处 | 说明 |
|---|---|---|---|
| 周期 | 6 小时，±1 小时抖动 | `CheckInWorker.schedule` | `PeriodicWorkRequestBuilder(6, HOURS, 1, HOURS)` |
| 约束 | 网络连通 + 电量不低 | 同上 | 条件不满足自动等待，不空跑 |
| 唯一任务 | `wcm-keepalive` + `KEEP` | 同上 | 不会重复排队 |
| 单轮动作 | 每账号：可能重登 → 探活 → 补昵称 → 落盘 → 面板同步 | `doWork` | 一个账号最多 4 次网络往返 |
| 重登上限 | 24 小时 3 次（可配置） | `ReloginPolicy.shouldAttempt` | 超限后把 `nextAt` 排到窗口最早一次之后 |
| 退避 | 6 小时起，×2 递增，封顶 24 小时 | `ReloginPolicy.onFailure` | 基础值可配置 |
| 硬停 | `bad_credentials` / `needs_manual` / `credential_unreadable` | `HARD_STOP_CODES` | 再试无意义，等人工 |
| 手动间隔 | 风控类暂停后 ≥10 分钟 | `MANUAL_MIN_GAP_MS` | 防止用户连点撞风控 |
| 停止条件 | 全部账号明确失效（`dead == 总数`）→ `Result.success()` | `doWork` 末尾 | 避免无意义重试刷风控 |

**请求预算**（N 个账号、当天重登 R 次）：

```
日探活请求  ≈ 4N            （24 / 6）
日重登请求  ≈ 9R            （prelogin 1 + login 1 + 回跳 ≤6 + 复验 1）
日总请求    ≈ 4N + 9R
```

| 账号数 N | 无重登 | R=1 | R=3N（上限） |
|---|---|---|---|
| 1 | 4 | 13 | 31 |
| 3 | 12 | 21 | 93 |
| 5 | 20 | 29 | 155 |

【推断】R 取上限时请求量约为基线的 8 倍，这是"24 小时 3 次"这个上限真正的意义——它不是为了省流量，而是为了**不让一个失效账号把整个 IP 撞进风控**。

### 9.2 「观察」与「续期」必须分开

| 动作 | 是否推进寿命锚点 | 代码依据 | 为什么 |
|---|---|---|---|
| 同步到面板 | ❌ | `AccountStore.markSynced` 只写 `syncedAt` | 同步是本地动作，微博不知道 |
| 探活成功 | ❌ | `markVerified` 只写 `seenAt` | 探活只证明"还活着"，不证明"被续了" |
| 关键字段换发（S12） | ✅ | `patchAccount` 判 `LOGIN_CRITICAL_KEYS` 变化才写 `rotatedAt` | 只有微博真的换了一套凭据才算续期 |
| 签到成功 | ❌ | 不写锚点 | 与探活同理 |
| 账密重登成功 | ✅ | `markReloginSuccess` + `loginAt` | 这是一次真实登录 |
| 普通字段变化（`_T_WM` 等） | ❌ | `criticalChangedKeys` 只比关键字段 | 设备标识变化不构成续期 |

> 这套口径是本仓库最值得保留的设计：**它把"我觉得还活着"和"服务端确实续了"分开了**。绝大多数同类项目会把"每天请求成功"当成"续期成功"，于是 7 天后突然集体失效且无法解释。

### 9.3 禁止尝试：那条"续期链"会销毁现有登录态

这是本次检索里最值得写进代码注释的一条警告。【实证·findxyz 2026-07-13，认证态对照】

| 项 | 事实 |
|---|---|
| 被禁对象 | `login.sina.com.cn/sso/updatetgt.php` → `sso/crossdomain.php` 这条"旧版 SSO Cookie 更新链" |
| 实测操作 | 用新扫码拿到的有效凭证，先打受保护接口确认 200 → 依次调用该链四步（自身均返回 HTTP 200 + 业务成功）→ 5 秒后同一接口 |
| 结果 | **401**；把续期前的原四字段 cookie 恢复并重启，**仍是 401**——原凭证已被服务端作废 |
| 响应里给了什么 | 只有 `.weibo.com` 的 `SSOLoginState`/`ALF`；`.weibo.cn` 侧甚至连 `SUB=deleted`、`SUBP=deleted`。**没有新的 `.weibo.com` `SUB`/`SUBP`** |
| 根因 | 扫码登录产出的 `SUBP` 解码后只有 `evid/flag/lt/nick/status/uid`，**缺旧更新器要求的 `et` 字段**；`getCookieExpireTime()` 因此返回 `retcode=6102`，正规流程永远不会进入更新窗口。手写调用绕过了这道前置检查，于是"续期"变成"自杀" |
| 附带结论 | `passport.weibo.com/wbsso/login` 是**登录票据交换**（需服务端签发的 `ticket`），不是刷新端点；`ssologin.js` 的 `autoLogin()` 也只是"有 `SUBP` 就直接回调、没 `SUBP` 才整页跳转"，**不是后台续期器** |

> 对维护者的具体约束：**任何"让登录态更持久"的改动，都不允许调用 `updatetgt` / `crossdomain`。** 想让登录态恢复，只有两条合法路径——路线 D（账密重登）或路线 E（ALC 重签），二者都是"重新登录一次"，都会拿到全新凭据。
>
> 唯一还没被证否的"恢复"线索是访客恢复链（`genvisitor2` 返回非空 `alt` → `sso/v2/login?source=visitor_restore`），但它属于"本地 cookie 丢失后的恢复"，**不能称为续期**，且它的触发条件至今未知（附录 A F8）。不要在没有对照实验的情况下拿它当方案。

### 9.4 本节结论

> **保活保证"发现及时"，不保证"续期"。** 是否真的被续期，取决于服务端是否在响应的 `Set-Cookie` 里重发 `SUB` 并延长 `Expires`——这一点本仓库**没有实测数据**（附录 A 的 F2）。
>
> 这带来一个反直觉的推论：如果实测证明"服务端是绝对到期、不因请求而延长"，那么提保活频率的最优解是**够密以尽早发现**（当前 6 小时），而不是"越密越久"。把周期压到分钟级不但不能延长登录态，还会直接撞 429/432。

---

## 10. 自动重登的定位与边界

### 10.1 触发-拒绝矩阵

| 条件 | 结果 | 代码位置 |
|---|---|---|
| 全局开关关闭 | 拒绝（`disabled`） | `ReloginPolicy.shouldAttempt` |
| 该账号未开启 | 拒绝（`disabled`） | 同上 |
| 未保存凭证 | 拒绝（`no_credentials`） | 同上 |
| cookie 仍可用（`State.OK`） | 拒绝（`not_needed`） | 同上 |
| 退避中 | 拒绝（`backoff`） | 同上 |
| 24h 内已达上限 | 拒绝（`daily_cap`） | 同上 |
| 硬停码 | 拒绝（固定码） | `HARD_STOP_CODES` |
| 风控/网络类暂停 + 用户强制 | 允许（需 ≥10 分钟间隔） | `MANUAL_MIN_GAP_MS` |
| 失效或临期 | **允许** | `AutoRelogin.cookieUsable` 要求 `State.OK` |

> 注意最后一行：`cookieUsable` 要求状态严格等于 `OK`，所以**临期（`EXPIRING`）也会触发重登**。这是有意为之——临期是"主动换票"的最佳时机，比等到失效再换更安全（失效后 SSO 可能要求验证码）。

### 10.2 失败码分类

| 码 | 含义 | 硬停 | 退避 | 用户可见文案 |
|---|---|---|---|---|
| `bad_credentials` | 账号或密码错误 | ✅ | — | 账号或密码错误 |
| `needs_manual` | 验证码 / 二次验证 / 扫码 | ✅ | — | 需要验证码或二次验证（需人工处理）：账密自动重登过不去，请用「网页登录」完成一次验证，或填写 ALC 票据 |
| `credential_unreadable` | 本地凭证无法解密 | ✅ | — | 本地凭证无法解密，请重新保存账号密码 |
| `risk_control` | 被微博风控拦截（含 `1117`） | ❌ | ✅ | 被微博风控拦截（retcode 1117：多为该账号需短信/私信二次验证或登录过于频繁，请用「网页登录」完成一次验证） |
| `network` | 网络异常 | ❌ | ✅ | 网络异常 |
| `verify_failed` | 登录返回成功但新 cookie 未通过探活 | ❌ | ✅ | 登录返回成功，但新 Cookie 未通过校验 |
| `unknown` | 其他 | ❌ | ✅ | 微博登录返回未知错误 |

> 2026-11-18 实测补充（v0.13.1）：
> - `sso/prelogin.php` 的 `pubkey` 已从「裸 16 进制 modulus」换成 **PEM（X.509
>   SubjectPublicKeyInfo，1024 位）**。老解析 `BigInteger(pubkey, 16)` 必抛异常、
>   被归成 `unknown` —— 表现就是「重登失败：微博登录返回未知错误」，密码再对也过不去。
>   现在 `parsePublicKey` 同时认 PEM/X.509、PEM/PKCS#1、裸 16 进制。
> - 账密登录被拒时，服务端常见回应只有一个通用码 `1117`「抱歉！登录失败，请稍候再试」，
>   **与账号是否存在无关**（不存在的用户名 / 手机号 / 邮箱三种形态实测同码），
>   或干脆回一整页 HTML。因此分类改为「文案里的验证特征词优先于码表」，
>   且 `prelogin` 回 `showpin=1` 时直接判 `needs_manual`，不再提交一次注定失败的密码。

### 10.3 本节结论

> **账密托管把"可用性问题"转换成了"账号安全问题"，而且这个转换是隐式的**——用户点开"自动重登"开关时，看到的是"少登录几次"，不是"我的微博密码存在这台设备上"。这是本仓库当前最需要显式声明的取舍：文档层面要写清，UI 层面要在开关旁给出等价表述。
>
> 另外要承认它的能力边界：D 解决"cookie 过期"，不解决"账号被风控"，也不解决"`savestate` 语义变化"。`WeiboPasswordLogin` 固定提交 `savestate=30`——如果微博改变这个参数的含义，重登成功率会整体下降，而本地只能表现为 `verify_failed` 升高。

---

## 11. 风险清单与加固方向

按「影响 × 概率」排序。每条给现有缓解、残余风险、加固方向（文件 + 手段名）、验证方法。**不写实现代码**，只写到"改哪个文件、用什么机制"。

### 11.1 风险登记表

**P0 · 会一次性摧毁全部账号**

| ID | 风险 | 现有缓解 | 残余风险 | 加固方向 | 验证方法 |
|---|---|---|---|---|---|
| R1 | cookie 明文双份落盘（G1） | `allowBackup=false` + `dataExtractionRules` 全量排除 | root / ADB 备份 / 取证镜像仍可读；卸载前无法自毁 | `AccountStore` 的 cookie 字段改为密文：复用 `CredentialVault` 的 Keystore AES-256-GCM 手法，序列化层加 `v1:` 前缀并保留明文兼容读（老数据平滑迁移） | 拉取 `wcm.xml` 确认 `cookie` 字段为 `v1:` 开头密文；老版本升级后账号表仍可读 |
| R2 | WebView cookie 库残留（G2） | 仅下次进登录页 `removeAllCookies` | 登录成功后到下次登录之间，第二份明文长期存在；删账号不清 | `LoginWebViewActivity.onVerified` 末尾 `removeAllCookies` + `flush`（会话已复制到 prefs，WebView 那份不再需要）；`AccountStore.remove` 时同样清理 | 登录成功后直接看 `app_webview/Default/Cookies` 应查不到该账号 SUB |
| R3 | 内存长期持明文（G6） | 90 秒 TTL | 内存 dump / 崩溃日志 | `WeiboAuthApi.verifyCache` 改为只缓存"是否已登录 + uid + 到期"，不缓存整串 cookie；需要 cookie 时从 store 现取 | 缓存条目反序列化后不含 `SUB=` |

**P1 · 单账号或传输面**

| ID | 风险 | 现有缓解 | 残余风险 | 加固方向 | 验证方法 |
|---|---|---|---|---|---|
| R4 | 面板同步明文过网 | 公网强制 https，仅内网允许 http | 内网 http 时整串 cookie 明文过网 | `PanelApi` 的 cookie 字段改为"由面板侧现取"或加应用层加密；至少限制内网 http 时不下发 cookie | 抓包确认内网 http 请求体不含 `SUB=` |
| R5 | 无证书固定 + 全局允许明文 | 应用层域名白名单（`weiboUrl` / `PanelUrl`） | 中间人可在系统层信任链上做手脚 | 对 `m.weibo.cn` / `weibo.com` / `login.sina.com.cn` 做 pin（注意**备选证书必须一并 pin**，否则证书轮换会全站不可用） | 用自签中间人代理验证连接被拒 |
| R6 | 无全局 CookieJar（G3） | `mergeSetCookies` 在探活路径回收 | 业务请求里的换发被丢弃；属性丢失导致域路由靠猜 | `WeiboHttp` 内建 OkHttp `CookieJar`，按 `(name, domain)` 存；`request()` 从账号 cookie 初始化、响应后回写；**先做 R1 再做本项**（否则明文暴露面从 1 处扩到 2 处） | 连续两次签到后，账号行 cookie 应包含第二次响应新下发的字段 |
| R7 | `XSRF-TOKEN` 未处理（G7） | 当前只读 `ajax/*`，侥幸不需要 | 一旦接写接口就是 403；token 2 小时过期也无刷新 | `WeiboHttp.request` 在目标为 `weibo.com` 时自动带 `X-XSRF-TOKEN`（值取 cookie 里的 `XSRF-TOKEN`）；`WebAjax` 增加 403 + `ok=0` 时的一次 token 刷新重试 | 带 token 打一个写接口应不再 403 |

**P2 · 可用性与判据**

| ID | 风险 | 现有缓解 | 残余风险 | 加固方向 | 验证方法 |
|---|---|---|---|---|---|
| R8 | 假在线：无登录墙识别（G4） | 非 JSON 抛 `identitySuspect` | 302 到登录页 / 返回 200 HTML 时被判成"正常" | `WeiboHttp` 增加响应分类：`Location` 含 `signin`/`login` 或 body 含 `Sina Visitor System` → 抛专用异常；上层据此判死 | 用一个失效 cookie 打 `/api/config`，应命中新异常而非返回空数据 |
| R9 | uid 缺失即不入库（G5） | 重试 3 次 | 用户以为成功，实际未存；cookie 悬空 | `onVerified` 在 uid 缺失时仍入库（uid 用占位/后续补齐），或明确提示"未保存"并保持页面 | 断网情况下完成登录，账号表应新增一行（哪怕昵称/uid 待补） |
| R10 | `dead` 后不清 cookie、不拦请求 | 无 | 判死账号仍可能被业务层拿去发请求，产生无效流量 | `AccountPort` 增加"可用账号"过滤；判死后业务层直接跳过 | 手动把一个账号标 dead，签到应直接跳过而非发出请求 |
| R11 | 账密托管的合规与信任风险 | 默认开启 + 全局开关 + 风险声明 | 用户未必理解"密码存在设备上" | UI 文案等价化（"自动重登需要在这台设备保存你的微博密码，使用 Keystore 加密"）；提供"改用扫码"路径 | 交互走查：不看文档的用户能否说清代价 |

### 11.2 加固与验收对照

| 加固项 | 对应缺口 | 改动文件 | 验收方式 | 回归风险 |
|---|---|---|---|---|
| H1 cookie 加密落盘 | G1 | `data/AccountStore.kt`、`data/CredentialVault.kt`（复用其加密原语） | 老账号表可读、新写入为密文 | 序列化格式变更 → 必须保留明文兼容读，否则老用户账号全丢 |
| H2 清理 WebView cookie 库 | G2 | `app/LoginWebViewActivity.kt`、`data/AccountStore.kt` | 登录后 cookie DB 无该账号 | 清理时机若早于入库会丢登录态 → 必须在 `upsert` 之后 |
| H3 全局 CookieJar | G3 | `core/WeiboHttp.kt` + 各 `*Api` 调用点 | 换发能被回收、按域裁剪 | 调用点从"传 cookie 字符串"改为"传账号"，改动面最广 |
| H4 登录墙识别 | G4 | `core/WeiboHttp.kt`、`core/WeiboAuthApi.kt` | 失效 cookie 命中专用异常 | 误把正常页判成登录墙 → 需要保守匹配（只在 body 很短或 `Location` 明确时命中） |
| H5 uid 缺失仍入库 | G5 | `app/LoginWebViewActivity.kt` | 断网登录后账号表有行 | 占位 uid 会污染"按 uid upsert"逻辑 → 需用独立 key 或后续回填 |
| H6 缓存不存明文 | G6 | `core/WeiboAuthApi.kt` | 缓存条目无 `SUB=` | 每次取 cookie 需回库，IO 略增 |
| H7 XSRF 头 | G7 | `core/WeiboHttp.kt`、`core/WebAjax.kt` | 写接口不再 403 | token 过期时需重试一次，否则会把 403 当失效 |

### 11.3 本节结论

> **P0 必须先于 P1。** 理由很具体：引入全局 CookieJar（H3）会让 cookie 从"两个存储点"变成"三个存储点 + 一个内存态"，如果此时 cookie 还是明文，暴露面是扩大而不是缩小。
>
> P0 三项（H1/H2/H6）都可以在**不动业务逻辑**的前提下完成——它们只改"存什么形态"和"什么时候清"，不改任何调用签名。

---

## 12. 结论与分阶段路线图

### 12.1 路线图

| 阶段 | 目标 | 关键改动 | 验收标准 | 依赖 |
|---|---|---|---|---|
| **P0** | 静态凭据面收敛 | H1 cookie 加密落盘、H2 清 WebView cookie 库、H6 缓存不存明文 | 设备上找不到任何明文 `SUB=` | 无（可立即做） |
| **P1** | 传输面统一 | H3 全局 CookieJar、H7 XSRF、域路由矩阵落到 `WeiboHttp` | 换发可回收、按域裁剪、写接口可用 | P0 完成 |
| **P2** | 判据补全 | H4 登录墙识别、H5 uid 缺失仍入库、R10 dead 后拦请求、S13 uid 漂移校验 | 失效 cookie 能被判死；断网登录不丢账号 | P1 可选并行 |
| **P3**（可选） | 降低对账密的依赖 | 路线 E（ALC 重签）先做实验、再做实现——端点顺序照 WeiboTask 的 `login.php → location.replace → setCrossDomainUrlList → arrURL`；路线 F（二维码）作为"不想存密码"的替代入口 | E 的换签成功率可测量；F 能完成一次完整登录 | 附录 A 的 F3 实测结论 |

### 12.2 不建议做的四件事

| 事项 | 为什么不做 |
|---|---|
| 逆向 App 端私有签名（`api.weibo.cn/2/*`） | 签名随客户端版本失效，维护成本无上限；且它换不来"我关注的超话"语义（guest §7） |
| 用 OAuth 替代核心链路 | 开放平台没有超话签到接口，`refresh_token` 再长也没用（guest §7） |
| 把保活频率提到分钟级 | 不能延长登录态（§9.4），只会撞 429/432；真正的收益点是"尽早发现"而非"更频繁" |
| **调用 `updatetgt.php` / `crossdomain.php` 续期链** | **会直接销毁现有登录态**（实测 5 秒后 401，恢复原 cookie 亦无效）；根因是扫码产出的 `SUBP` 缺 `et`（§9.3）。这条比"不划算"严重一个量级——它是"做了就更糟" |

### 12.3 全文收束

> 微博的持久登录不是一个"设计出来的协议"，而是一套"围绕服务端会话的观测与收敛"：
>
> **抓一次（WebView 官方登录）→ 加密存一份（Keystore）→ 每次请求带对域的那份（CookieJar）→ 每 6 小时看一眼（探活）→ 换发了就收下（Set-Cookie 回写）→ 真的死了才判死（强判据）→ 判死了自动换票（重登/ALC）→ 换不出来就老实让用户登一次。**
>
> 这套链条里，**没有任何一步能"延长"登录态**。能延长的只有微博的服务器。

### 12.4 实施记录（v0.11.0，2026-11-15）

本文的诊断已被落成代码。**落地范围与未做范围都写在这里**，避免文档与实现再次漂移。

| 项 | 状态 | 落点 |
|---|---|---|
| H1 cookie 加密落盘 | ✅ 已实施 | `data/SecretBox.kt`（Keystore AES-256-GCM，独立 alias）+ `AccountStore.serializeAccounts(sealCookie)`；写失败降级明文并告警 |
| H1 存量迁移 | ✅ 已实施（一次性全量） | `AccountStore.migrateToSealed`，`WcmApp.onCreate` 的 IO 协程触发；写完回读校验，不过则回滚 |
| H2 清 WebView cookie 库 | ✅ 已实施 | `LoginWebViewActivity.onVerified` 在 `upsert` 之后 `removeAllCookies + flush` |
| H6 缓存不存明文 | ✅ 已实施 | `WeiboAuthApi.verifyCache` 改为 SHA-256 摘要命中，命中时用入参 cookie 回填 |
| H4 登录墙识别 | ✅ 已实施 | `WeiboHttp.looksLikeLoginWall` / `isLoginLanding`；`WeiboException.loginWall`；签到与 `CheckInWorker` 据此判死 |
| H3 全局 CookieJar | ⚠️ 部分实施 | `WeiboHttp.WeiboSession` 已在超话列表/详情/签到/验签重试上生效（**换发回收**）；**未做域过滤**——m 站实测接受跨域 SUB，按域裁剪会引入回归；抓图链路（`WeiboPostApi` / `WebAjax`）尚未接入 |
| H5 uid 缺失仍入库 | 🔶 改为保守解 | 不再静默 `finish`，停在页面给出可点重试的说明。**没有**造占位 uid：那会让 `AccountStore.upsert` 的 uid 键失效，反而产生无法回收的重复行 |
| H7 XSRF | ⬜ 未做 | 当前不接 Web 端写接口，无触发面 |
| R4 面板同步明文 | ⬜ 未做 | 内网 http 场景仍以 JSON 明文 POST cookie |
| 路线 E（ALC 重签） | ✅ 已实施 | `core/WeiboAlcLogin.kt` + `AutoRelogin.attemptLogin` 分流（有密码优先账密）；`data/AlcVault.kt` 加密存 ALC；重登弹窗新增 ALC 输入 |
| 路线 F（二维码） | ⬜ 未做 | 仍待 F4 实测 |
| 禁止续期链 | ✅ 已写入注释 | `WeiboAlcLogin` 类注释、`CHANGELOG` 已知边界 |

---

## 附录 A · 待实测清单

| # | 待查事实 | 为什么重要 | 现有可确证程度 | 实验设计 | 判读标准 | 失败时如何降级 |
|---|---|---|---|---|---|---|
| F1 | `SUB` 等服务端寿命是否真为 30 天 | 决定 `CookieLife.TTL_DAYS=7` 是否过于保守 | 【社区口径】 | 取同一账号，间隔 7/14/21/30 天各做一次探活，记录 `login` 与响应 `Set-Cookie` 的 `Expires` | 第 n 天仍 `login=true` 且 `Expires` 未推移 → 绝对到期 | 若 7 天内即失效 → 说明 TTL 合理甚至偏乐观 |
| **F2** | **是否为滑动续期**（日常请求的 `Set-Cookie` 是否重发 `SUB` 并延长 `Expires`） | 决定"保活 = 续期"还是"保活 = 早发现"（§9.4 全部结论依赖它） | 【社区口径】称是；本仓无数据；findxyz 已证否**主动续期端点**存在 | 同日两次探活（间隔 ≥1h），比对两次响应里 `SUB` 值与 `Expires` | `Expires` 后移 → 滑动续期成立 | 若不变 → 保活只用于发现，`rotatedAt` 应只在真实登录时推进 |
| F3 | `ALC` 的真实有效期，以及"凭 `ALC` 换新 `SUB`"是否仍可用 | 决定路线 E 能不能成为 D 的替代（§4.4） | 【社区口径：6 天 vs 数月，互相冲突】；【旁证·代码】WeiboTask 2026-09 仍在用 `sso/login.php?useticket=1` 走这条路 | 登录后导出完整 cookie，隔离只保留 `ALC`，按 §4.4 的端点与参数顺序发请求（`login.php→location.replace→setCrossDomainUrlList→arrURL`） | 能拿到新 `.weibo.com` `SUB` 且 `m.weibo.cn/profile/info` 回 JSON → 成立 | 不成立则 E 只能记为"历史方案"，D 不可被替换 |
| F4 | 二维码登录链路（`sso/v2/qrcode/image` + `/check`）当前可用性 | 决定路线 F 是否是"免存密码"的现实选项 | 【待验证】；pansou 有完整实现可对照 | 拉二维码 → 手机扫码 → 取 `alt` URL → 逐个域收 cookie → 探活 | 能拿到 m 站域 cookie 且 `login=true` → 可用 | 不可用则 F 记为"仅 PC 端登录态" |
| F5 | 三域同名 `SUB` 的匹配优先级 | 现状靠"两种合并顺序"猜（§3.2），是持久化正确性的根 | 【代码】注释自认是经验；【实证】PC 扫码只写 `.weibo.com`，故优先级可能取决于入口 | 拆成三份单域 cookie，分别打 `m.weibo.cn/api/config`，做 3×3 判定表 | 只有 m 域可用 → 合并顺序应固定为 m 优先 | 若都可/都不可 → 说明 SUB 与域无关，可简化合并逻辑 |
| F6 | 多端并发（App 保活 + 面板定时任务）是否互踢 | 决定保活频率上限、以及面板同步是否在自伤 | 【未验证】 | 双端同时保活 72 小时，记录是否出现 `login=false` / 432 / `SUB` 换发 | 出现互踢 → 需要协调两端节流 | 无影响 → 可放宽频率 |
| F7 | `savestate=30` 的语义是否稳定 | 决定账密重登的成功率基线（§10.3） | 【代码】固定提交，无验证；【实证】官方 `login.php` 参数里同样有 `savestate=30` | 改 `savestate` 为不同值（如 0/7/30）各重登一次，对比 cookie 寿命 | 与寿命正相关 → 参数有效 | 无关 → 该参数可删，减少协议面 |
| F8 | 访客恢复链（`genvisitor2` 返回非空 `alt` → `sso/v2/login?source=visitor_restore`）在什么条件下对**已登录账号**生效 | 这是目前唯一还没被证否的"登录态恢复"线索（§9.3） | 【推测·findxyz】有结构证据，触发条件未知；一次 300 秒实验未复现 | 克隆一次性浏览器 profile：保留 `.passport.weibo.com` 状态，只删 `.weibo.com` 的登录 cookie，再访问 `weibo.com`，观察是否得到非空 `alt` 并进入 `visitor_restore` | 恢复后受保护接口返回**预期账号数据**（不只看 HTTP 200，要看没有 `error_code=21301`），且重启后仍有效 | 不复现 → 明确记为"不可依赖"，方案里删除该线索 |

---

## 附录 B · 实测记录模板

| 时间 | 请求 | 关键响应字段 | Set-Cookie 摘要（脱敏） | 判定 |
|---|---|---|---|---|
| 2026-11-15 10:00 | `GET m.weibo.cn/api/config` | `login=true`, `uid=…` | `SUB=…(len=96)`, `Expires=+30d` | 有效，未换发 |
| 2026-11-15 14:00 | 同上 | `login=true` | `SUB=…(len=96)`, `Expires=+30d`（后移 4h？） | 换发成立 / 不成立 |

脱敏规则：cookie 值一律写 `SUB=…(len=96)`；`uid` 可保留（非机密）；面板 token 一律不出现。

---

## 附录 C · 术语表

| 术语 | 定义 |
|---|---|
| 会话凭据 | 能代表"我是谁"的字段（`SUB`/`SUBP`/`ALC`），丢了就等于没登录 |
| 时间戳 | 记录某个时刻的字段（`SSOLoginState`/`ALF`），本身不是权限 |
| 观察 | 只证明"当前还能用"，不改变寿命的行为（探活、同步、签到） |
| 换发 | 服务端通过 `Set-Cookie` 下发新凭据，替换旧值 |
| 续期 | 服务端因换发而延长会话有效期（客户端只能观测，不能请求） |
| 硬停 | 再试无意义的失败（密码错/验证码/凭证读不出），必须人工介入 |
| 误判死 | 服务端仍认，本地判成失效 |
| 假在线 | 服务端已不认，本地仍显示正常 |
| 三态 | 确定有效 / 确定失效 / 不确定（见 §8.3） |

---

## 附录 D · 参考

### 代码溯源（版本锚 `v0.10.1`）

- `app/src/main/java/com/wcm/checkin/LoginWebViewActivity.kt` — `startLoginFlow` / `resyncSso` / `cookieVariants` / `looksLoggedIn` / `verifyAsync` / `onVerified` / `onVerifyFailed`
- `core/src/main/java/com/wcm/checkin/WeiboAuthApi.kt` — `verify` / `parseLoginFlag` / `VERIFY_TTL_MS` / `verifyCache`
- `core/src/main/java/com/wcm/checkin/WeiboHttp.kt` — `client` / `request` / `parseCookieString` / `mergeSetCookies` / `weiboUrl`
- `core/src/main/java/com/wcm/checkin/CookieLife.kt` — `TTL_DAYS` / `WARN_DAYS` / `describe` / `parseAlf` / `loginTimeOf`
- `core/src/main/java/com/wcm/checkin/ReloginPolicy.kt` — `shouldAttempt` / `onFailure` / `HARD_STOP_CODES`
- `core/src/main/java/com/wcm/checkin/WeiboPasswordLogin.kt` — `login` / `MemoryJar` / `classifyRetcode` / `savestate`
- `data/src/main/java/com/wcm/checkin/AccountStore.kt` — `serializeAccounts` / `patchAccount` / `markDead` / `markCheckError` / `LOGIN_CRITICAL_KEYS`
- `data/src/main/java/com/wcm/checkin/CredentialVault.kt` — Keystore AES-256-GCM 原语
- `domain/src/main/java/com/wcm/checkin/AutoRelogin.kt` — `cookieUsable` / `tryAutoRelogin`
- `app/src/main/java/com/wcm/checkin/CheckInWorker.kt` — `doWork` / `schedule`

### 文档

- `docs/research/weibo-guest-endpoint-audit.md` §2.4（cookie 字段与有效期）、§7（明确不接的理由）、附录 A/B（实测与错误码）
- `docs/architecture.md`（模块依赖与不变量）
- `README.md`（账密自动重登的风险与开关）

### 外部项目（GitHub，检索日期 2026-11-15）

| 项目 | 用它来支撑本文哪一条 |
|---|---|
| [happy888888/WeiboTask](https://github.com/happy888888/WeiboTask) · `src/WeiboClient/loginByCookies.go` | 路线 E 的现成实现：ALC + `sso/login.php?useticket=1` + `setCrossDomainUrlList` 跨域回跳，6 域 cookie 存取（§4.4） |
| [findxyz/vb-weibo-plus](https://github.com/findxyz/vb-weibo-plus) · `docs/research/weibo-renewal.md`（2026-07-13） | **最强反面证据**：`updatetgt`/`crossdomain` 续期链会销毁凭证（5 秒后 401）；扫码 `SUBP` 缺 `et`；`wbsso/login` 非刷新端点；`autoLogin()` 非续期器；PC 扫码 15 个 cookie 的域分布（§3.2、§9.3、附录 A F2/F8） |
| [88lin/weibo-checkin](https://github.com/88lin/weibo-checkin) | 同类工艺对照：Cookie 体检 + 失效告警 + 指数退避（§8、§9） |
| [BeacherZ/dailycheckin-plus](https://github.com/BeacherZ/dailycheckin-plus) | 路线 A 的产品化形态：粘贴即规范化 + 在线校验有效性（§4.1） |
| [fish2018/pansou](https://github.com/fish2018/pansou) · `plugin/weibo` | cookie 字段有效期表原始出处；二维码登录完整链路（§3.1、路线 F） |
| [zhouyi207/WeiBoCrawler](https://github.com/zhouyi207/WeiBoCrawler) · `src-tauri/src/weibo/session.rs` | 会话对象设计：内存 Jar 复用 client、登录后导出完整 cookie（§7.1） |
| [jackwener/weibo-cli](https://github.com/jackwener/weibo-cli) · `weibo_cli/auth.py` | 命令行场景的 QR 登录 + 本地凭证（路线 F） |

### 社区口径（未在本仓验证）

- PanSou 微博搜索插件文档（cookie 字段有效期表）
- 《微博超话自动签到系统：Cookie 7 天失效问题的分析与解决》（Set-Cookie 回写、ALC 重签两层机制）
- WeiboTask（纯 ALC 方案）
