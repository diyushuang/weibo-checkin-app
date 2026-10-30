# 微博各端「免登录端口」审计

> 范围：**不需要登录微博账号**就能访问的接口，即各客户端端（Web / H5 / 超话 / 卡片 / 护照 / CDN）的游客态接口。
> 不含微博开放平台（`open.weibo.com` / `api.weibo.com`）的 OAuth 接口 —— 那套是另一条线，与本项目核心功能（超话签到、原图抓取）没有交集。
>
> 审计日期：2026-10-30　｜　对应代码：本仓库 `app/src/main`（v0.9.0+）

---

## 1. 摘要

三句话：

1. **微博 Web / H5 端没有请求签名**，不像抖音/小红书要走 JSVMP 虚拟机器；门槛只有「cookie」和「IP 信誉」两道。（App 端 `api.weibo.cn` 另有私有签名，见 §7「明确不接」。）
2. **免登录分三档**：L0 零凭据、L1 游客 cookie（访客系统自动签发，不需要任何账号）、L2 必须账号登录。「不需要登录」对应的是 L0 + L1。
3. **但「游客 cookie」不是万能的**：它是 `.weibo.com` 域的访客身份，**在 m.weibo.cn 上不被接受**（实测 `ok:-100`）。能免登录读的只有 `weibo.com/ajax` 那一侧的一部分接口。

> ⚠️ **本文 §1 初稿曾判断「抓图链路可完全脱钩账号」，2026-10-30 补测后已被推翻**。实测结论见 §6：
> 微博列表（抓图的入口）与单帖详情都必须登录，抓图**无法**与账号解耦。
> 仍然成立的收益是：探活结果可缓存（请求减半）、节流可取消、昵称与相册可免登录。

---

## 2. 端的分层与游客身份机制

### 2.1 六个端

| 端 | 主域名 | 接口风格 | 免登录可得性 | 本项目是否涉及 |
|---|---|---|---|---|
| **Web 端** | `weibo.com/ajax/*` | JSON，要 `X-Requested-With`，部分要 `X-XSRF-TOKEN` | L1 多数可用（个别存疑，见 §4.1） | 否（可新增） |
| **H5 / 移动网页端** | `m.weibo.cn/*` | JSON，卡片结构（`cards[]` / `card_group[]`） | L1 可用 | **是（核心）** |
| **超话端** | `m.weibo.cn/p/100808*`、`huati.weibo.com` | 卡片 / HTML | 超话详情 L1；**签到与「我关注的超话」L2** | 是 |
| **卡片 / 文章端** | `card.weibo.com/article/m/aj/*` | JSON | L1~L2（长文正文常需登录态） | 否 |
| **护照端** | `passport.weibo.com`、`visitor.passport.weibo.cn` | JSONP / 302 | **游客身份的签发入口本身免登录** | 是（登录用） |
| **CDN** | `*.sinaimg.cn`、`video.weibo.com`、`f.video.weibocdn.com` | 二进制 | L0 | 是 |

### 2.2 三档模型

| 档 | 名称 | 需要的凭据 | 怎么拿到 | 典型端口 |
|---|---|---|---|---|
| **L0** | 零凭据 | 无 | 直接 GET（仍需合理 UA） | `ajax/side/hotSearch`、`sinaimg` 原图、`video.weibo.com/media/play`、`m.weibo.cn/n/<域名>` |
| **L1** | 游客身份 | 访客 cookie（`_T_WM`；Web 端为游客 `SUB`/`SUBP`） | 访客系统自动签发，**不需要任何微博账号** | `m.weibo.cn/api/container/getIndex`、`m.weibo.cn/statuses/show`、`m.weibo.cn/comments/hotflow`、`weibo.com/ajax/statuses/*`、`weibo.com/ajax/profile/*` |
| **L2** | 账号登录 | 真实 `SUB` + `SCF`/`SSOLoginState` | 本项目用 WebView 走微博官方登录页 | 签到 `st`、`100803_-_followsuper`、发布、私信 |

> 判定口诀：**L1 能读公开内容，L2 才能代表「我」做事**。签到之所以必须 L2，是因为 `st` 是绑定账号的签名，不是内容读取权限。

### 2.3 两条游客身份链路（**只有②真正可用**）

**① H5 端 —— 单跳入口（实测已失效，勿用）**

无 cookie 访问 `m.weibo.cn` 任一内容页时，会 302 到：

```
https://visitor.passport.weibo.cn/visitor/visitor
    ?entry=sinawap
    &a=enter
    &url=<原始 URL>
    &domain=.weibo.cn
    &sudaref=
    &ua=php-sso_sdk_client-0.6.36
    &_rand=<随机数>
```

**实测（2026-10-30）**：该地址返回 **200 HTML（`Sina Visitor System`），Set-Cookie 里只有 `WEIBOCN_FROM`，没有 `_T_WM`**。
它是 JS 驱动页，游客凭据由页面脚本再发起请求拿 —— 纯 HTTP 客户端走不通。
`_T_WM` 因此在本项目里拿不到，**m 站也就没有可用的免登录身份**。

**② Web 端 —— 两步握手（唯一可用路径，已在 `GuestSession.kt` 实现）**

> v0.9.9 修正：`GuestSession` 早期误用了 ① 的单跳入口，导致游客身份恒为 null、
> 相册模式每轮静默回退时间线。现已改为下面这条两步握手，探活也改到 `weibo.com` 侧
> （`ajax/statuses/config`）—— 游客 cookie 的域是 `.weibo.com`，拿 m 站 `/api/config`
> 判活只会把可用身份判死。

```text
第 1 步  POST https://passport.weibo.com/visitor/genvisitor2
         body: cb=visitor_gray_callback&ver=20250916&tid=&from=weibo&webdriver=false&return_url=https://weibo.com/
         响应是 JSONP：visitor_gray_callback({...});
         从中取 data.tid / data.sub / data.subp

第 2 步  GET  https://passport.weibo.com/visitor/visitor
         ?a=crossdomain&t=<tid>&s=<sub>&sp=<subp>&from=weibo&_rand=<随机数>&url=<原始 URL>
         从 Set-Cookie 取 SUB / SUBP（domain=.weibo.com，有效期 1 年）
```

注意 cookie 的**域是 `.weibo.com`**：它只对 `weibo.com/ajax/*` 有效，拿到 m 站会被判 `-100`。

缓存：`gallery-dl` 按 **365 天** 缓存这份游客 cookie，失效后重走一次。本项目可照此实现。

### 2.4 cookie 字段与有效期

| 字段 | 有效期 | 作用 | 本项目是否用到 |
|---|---|---|---|
| `SUB` | 30 天 | 用户/游客认证令牌 | 已检查（登录判定） |
| `SUBP` | 30 天 | 权限令牌 | 原样透传 |
| `SCF` | 30 天 | 安全 Cookie 标识 | 已检查 |
| `ALF` | 30 天 | 认证过期时间 | 原样透传 |
| `ALC` | 6 天 | PC 端登录 Cookie | 原样透传 |
| `XSRF-TOKEN` | 2 小时 | CSRF 防护（Web 端 `ajax/*` 要） | **未处理** |
| `WBPSESS` | 2 小时 | PC 端会话 | 原样透传 |
| `mweibo_short_token` | 2 小时 | 移动端短令牌 | 原样透传 |
| `SSOLoginState` | 2 小时 | 登录状态时间戳 | 已检查 |
| `_T_WM` | 长期 | 移动端设备标识（游客态核心） | **未处理** |

> 来源：`fish2018/pansou` 的《微博用户搜索 API 文档》。表中「未处理」两项是接入 L1/Web 端时必须补的。

---

## 3. GitHub 项目盘点

按对本项目的可用性排序，均已逐个核实内容。

| # | 项目 / 文件 | 语言 | 价值 | 能回答什么 | 注意 |
|---|---|---|---|---|---|
| 1 | [`mikf/gallery-dl`](https://github.com/mikf/gallery-dl) `gallery_dl/extractor/weibo.py` | Python | ★★★★★ | Web 端 `ajax/*` 完整清单、**visitor 两步握手**、游客 vs 登录的可用性判定、原图拼接 | 活跃维护，可直接照抄逻辑 |
| 2 | [`cssxsh/weibo-helper`](https://github.com/cssxsh/weibo-helper) `src/main/kotlin/xyz/cssxsh/weibo/api/Api.kt` | **Kotlin** | ★★★★★ | 40+ 个 `weibo.com/ajax/*` 常量集中定义，含 `PASSPORT_GEN_VISITOR`、老超话列表接口 | 与本项目同语言，命名可直接借用 |
| 3 | [`DIYgod/RSSHub`](https://github.com/DIYgod/RSSHub) `lib/routes/weibo/*` | TypeScript | ★★★★☆ | 用户/超话/评论/搜索/热搜的路由实现，逐个标注「游客 cookie 即可」还是「需 `ok===1`」 | 注释里有大量实测结论 |
| 4 | [`dataabc/weibo-crawler`](https://github.com/dataabc/weibo-crawler) | Python | ★★★★☆ | `containerid` 规则最全：`100505{uid}` 用户卡、`230283{uid}_-_INFO` 资料卡、`230413{uid}` 列表、`100103type=401&q=` 站内搜索 | 设计上强制带账号 cookie，不代表接口必须登录 |
| 5 | [`ShilongLee/Crawler`](https://github.com/ShilongLee/Crawler) `docs/.../platform-signature-audit.md` | Python | ★★★★☆ | **2026-10-19 实测**：微博无签名、靠 cookie；`hotSearch` 零 cookie 返回 `ok:1`；机房 IP 会被拦 | 目前能找到的最新实测证据 |
| 6 | [`fish2018/pansou`](https://github.com/fish2018/pansou) `plugin/weibo/微博用户搜索API文档.md` | Go | ★★★☆☆ | cookie 字段有效期表、保活机制、评论接口参数与错误码 | 部分结论面向登录态 |
| 7 | [`tuian/weibo-api`](https://github.com/tuian/weibo-api) | Python | ★★☆☆☆ | 「免登录封装 m 站」的最早形态（2017） | **接口多已变更，仅作历史对照，勿照抄** |

补充参照（仅特定场景）：`yt-dlp` 的 `weibo.py`（视频直链解析）、`Lodour/Weibo-Album-Crawler` 与 `biuld/weibo-album-dl`（相册 `getImageWall` 用法）。

**共性结论**：没有任何一个项目是「纯接口文档」，都是「爬虫/下载器 + 内嵌接口知识」。所以本文件的价值是把这些散落知识按**端 + 档位**重新组织，并标注每个结论的来源与新鲜度。

---

## 4. 免登录端口清单

> 来源标记：`【实测】`=2026-10-30 本机探测；`【gd】`=gallery-dl；`【wh】`=weibo-helper；`【rh】`=RSSHub；`【wc】`=weibo-crawler；`【ps】`=pansou；`【sl】`=ShilongLee 审计。
> 「存疑」= 社区来源互相冲突或未实测，需按 §6 验证。

### 4.1 Web 端 `weibo.com/ajax/*`（统一要 `X-Requested-With: XMLHttpRequest`，部分要 `Referer`）

| # | 路径 | 用途 | 档位 | 关键参数 | 来源 |
|---|---|---|---|---|---|
| 1 | `/ajax/side/hotSearch` | 热搜榜 | **L0**（零 cookie） | — | 【sl】实测；【实测】本机返回 `Forbidden`（IP 差异，见 §6） |
| 2 | `/ajax/statuses/show` | 单条微博详情（含 `pic_infos`/`pic_ids`） | L1（存疑） | `id`、`isGetLongText=1` | 【gd】游客可用；【rh】称需 `ok===1` |
| 3 | `/ajax/statuses/longtext` | 长文正文 | L1（存疑） | `id` | 【wh】 |
| 4 | `/ajax/statuses/mymblog` | 用户微博列表 | L1 | `uid`、`feature`（0 全部 / 1 原创 / 10 文章）；登录态用 `page`，游客用 `since_id` | 【gd】 |
| 5 | `/ajax/statuses/repostTimeline` | 某条微博的转发列表 | L1 | `id`、`page` | 【wh】 |
| 6 | `/ajax/statuses/config` | 站点配置（字数上限等） | L1 | — | 【wh】 |
| 7 | `/ajax/profile/info` | 用户信息（uid / 昵称 / 头像 / 粉丝数） | L1 | `uid` 或 `screen_name`/`custom` | 【gd】【wh】 |
| 8 | `/ajax/profile/detail` | 用户详细资料 | L1 | `uid` | 【wh】 |
| 9 | `/ajax/profile/myhot` | 用户「热门」微博 | L1 | `uid`、`page`、`feature=2` | 【gd】 |
| 10 | `/ajax/profile/getImageWall` | **相册图片墙**（含子相册列表） | L1 | `uid`、`sinceid=0`、`has_album=true` | 【gd】【wh】 |
| 11 | `/ajax/profile/getAlbumDetail` | 单个子相册 | L1 | `uid`、`containerid`、`since_id` | 【gd】 |
| 12 | `/ajax/common/download` | **相册原图直链** | L1（需 cookie） | `pid` | 【gd】 |
| 13 | `/ajax/profile/getprofilevideolist` | 用户视频列表 | L1 | `uid` + 游标 | 【gd】【wh】 |
| 14 | `/ajax/comments/buildComments` | 微博评论列表 | L1 / L2（存疑） | `id`、`flow`、`is_reload` | 【rh】间接 |
| 15 | `/ajax/search/all` | 全站搜索 | **L2** | `q` | 【wh】 |
| 16 | `/ajax/feed/hottimeline` | 推荐流 | L1 | `page`、`count` | 【wh】 |
| 17 | `/ajax/friendships/friends` | 关注列表 | L1（存疑） | `uid`、`page` | 【wh】 |

### 4.2 H5 端 `m.weibo.cn/*`

| # | 路径 | 用途 | 档位 | 关键参数 | 来源 |
|---|---|---|---|---|---|
| 18 | `/api/config` | 探活 + `st` + uid（游客返回 `login:false`） | L1 | — | 【实测】项目在用 |
| 19 | `/api/container/getIndex` | 用户卡 `100505<uid>` | L1 | `containerid` | 【wc】 |
| 20 | `/api/container/getIndex` | 用户微博列表 `107603<uid>` | **L1**（无 cookie 返回 **432**） | `containerid`、`page` | 【实测】+【wc】 |
| 21 | `/api/container/getIndex` | 另一版列表 `230413<uid>` | L1 | `containerid`、`page`、`count` | 【wc】 |
| 22 | `/api/container/getIndex` | 用户资料卡 `230283<uid>_-_INFO` | L1 | `containerid` | 【wc】 |
| 23 | `/api/container/getIndex` | 超话详情 `100808<hash>` | **L1** | `containerid` | 项目在用 |
| 24 | `/api/container/getIndex` | 我关注的超话 `100803_-_followsuper` | **L2** | `containerid`、`since_id` | 项目在用 |
| 25 | `/statuses/show` | 单条微博（`pic_ids`/`live_photo`） | **L1**（无 cookie → 302 访客系统） | `id`（bid 或 mid） | 【实测】+【rh】 |
| 26 | `/comments/hotflow` | 热门评论 | L1 | `id`、`mid`、`max_id`、`max_id_type=0` | 【ps】【rh】 |
| 27 | `/comments/hotflowChild` | 子评论 | L1 | `cid`、`max_id` | 【rh】 |
| 28 | `/api/statuses/repostTimeline` | 转发列表 | L1 | `id`、`page` | 【wc】 |
| 29 | `/n/<个性域名>` | 302 解析出 uid | **L0**（无 cookie） | — | 项目在用 |
| 30 | `/detail/<bid>` | 详情 HTML（长文兜底，正文内嵌 `"status":` JSON） | L1 | — | 【wc】 |
| 31 | `/p/100808<hash>/super_index` | 超话主页 HTML | L1 | — | 项目 scheme 兼容此形态 |

### 4.3 卡片 / 文章端

| # | 路径 | 用途 | 档位 | 来源 |
|---|---|---|---|---|
| 32 | `card.weibo.com/article/m/aj/detail?id=` | 头条文章正文 | L1~L2（长文常需登录态） | 【rh】 |

### 4.4 护照端（签发游客身份，本身免登录）

| # | 路径 | 用途 | 档位 |
|---|---|---|---|
| 33 | `visitor.passport.weibo.cn/visitor/visitor?entry=sinawap&a=enter` | H5 端游客 cookie 签发 | **L0**，但 ② 实测已失效（只回 `WEIBOCN_FROM`，见 §2.3） |
| 34 | `passport.weibo.com/visitor/genvisitor2` → `passport.weibo.com/visitor/visitor?a=crossdomain` | Web 端游客 cookie 两步签发 | **L0** |

### 4.5 CDN

| # | 路径 | 用途 | 档位 | 备注 |
|---|---|---|---|---|
| 35 | `wx{1..4}.sinaimg.cn/large/<pid>.jpg` | 原图 | **L0** | 项目在用，host 由 `CRC32(pid)%4` 轮转 |
| 36 | `video.weibo.com/media/play?livephoto=<串>` | Live Photo mov 包装地址 | **L0** | 内层 mov 直链 403，必须走包装 |
| 37 | `tvax*.sinaimg.cn` / `*.sinaimg.cn/crop.*` | 头像 | **L0** | — |

**合计 37 个端口：L0 = 7 个、L1 = 28 个、L2 = 2 个**（另有 2 项档位存疑，暂计入 L1）。

---

## 5. 项目现状核查

### 5.1 12 个已接入端点 × 真实身份要求

| # | 端点 | 用途 | 代码位置 | 实测档位（2026-10-30） | 当前传的身份 |
|---|---|---|---|---|---|
| 1 | `m.weibo.cn/api/config` | 探活 + `st` + uid | `WeiboApi.kt:273` | **L2**（要 `st`） | 账号 cookie ✓ |
| 2 | `getIndex?containerid=100803_-_followsuper` | 关注超话列表 | `WeiboApi.kt:517` | **L2** | 账号 cookie ✓ |
| 3 | `getIndex?containerid=100808<hash>` | 超话详情 | `WeiboApi.kt:564` | **L2**（游客 cookie 返回 `ok:-100`） | 账号 cookie ✓（必须） |
| 4 | 签到 scheme + `st` | 超话签到 | `WeiboApi.kt:584` | **L2** | 账号 cookie ✓ |
| 5 | `getIndex?containerid=107603<uid>` | 用户微博列表 | `WeiboApi.kt:662` | **L2**（游客 `ok:-100`） | 账号 cookie ✓（必须） |
| 6 | `statuses/show?id=` | 取 `pic_ids` / Live | `WeiboApi.kt:733` | **L2**（m 站口径；Web 版游客可用但丢 Live 信息） | 账号 cookie ✓（必须） |
| 7 | `getIndex?type=uid&value=` | 兜底取昵称 | `WeiboApi.kt:763` | **L2** | 账号 cookie，**已改为游客走 Web 端优先** |
| 8 | `m.weibo.cn/n/<domain>` | 域名 → uid | `WeiboApi.kt:810` | **L0** | 无 cookie ✓ |
| 9 | `wx*.sinaimg.cn/large/<pid>` | 原图 | `Crawler.kt:640` | **L0** | 匿名 ✓ |
| 10 | `video.weibo.com/media/play?livephoto=` | Live 动图 | `Crawler.kt:104` | **L0** | 匿名 ✓ |
| 11 | `passport.weibo.cn/signin/login` | WebView 登录 | `LoginWebViewActivity.kt:199` | — | — |
| 12 | `POST {面板}/api/mobile/sync` | 非微博，Cookie 同步 | `PanelApi.kt:28` | — | — |

**修正后的事实**：8 个微博端点里，**只有 #8 / #9 / #10 三个真正免登录**。#3/#5/#6/#7 当初判断为「L1 游客可读」是错的 —— 游客 cookie 是 `.weibo.com` 域身份，m 站一律 `-100`。

**能免登录的是 Web 端那几个**（见 §6 实测表）：`ajax/profile/info`、`ajax/statuses/show`、`ajax/profile/getImageWall`。其中 `ajax/statuses/show` 虽然游客可读，但返回的是扁平 status 对象、没有帖级 `live_photo` 与 `pics` 配对信息，切过去会让 Live 动图退化成静图 —— 所以详情仍用 m 站。

### 5.2 频控参数一览（改造前散落在 9 处）

| 场景 | 参数 | 位置 |
|---|---|---|
| 超话列表翻页 | ~~`Thread.sleep(500)`/页~~ → `WeiboThrottle.await`，最多 20 页 | `WeiboApi.kt:517` |
| 超话详情 | ~~进程级最小间隔 1200ms~~ → `WeiboThrottle.await` + 30 分钟缓存 | `WeiboApi.kt:564` |
| 签到 | 每条随机 3000–8000ms（**保留**：这是打散指纹的抖动，不是单纯限速） | `HomeFragment.kt:814`、`:994` |
| 多账号轮转 | 签到 2000–5000ms / 只刷状态 800ms | `HomeFragment.kt:746`、`:640` |
| 图片下载 | 成功/失败后 600–900ms | `Crawler.kt:788`、`818`、`827`、`838` |
| 下载重试 | 3 次，`1200ms×(n-1)` 线性 | `Crawler.kt:103`、`:850` |
| 后台保活 | WorkManager 6 小时 ±1 小时 | `CheckInWorker.kt:59` |

### 5.3 已定位的三个问题（均已在 v0.9.7 修复）

1. **`verify()` 调用密度过高**：9 处调用，每个业务动作前都探活一次 → 实际请求量≈业务量的 2 倍。
   → 已加 90 秒缓存（只缓存 `login=true`），`signDetailed` 验签重试仍 `force=true`。
2. **`Thread.sleep` 做节流**：超话列表 500ms/页、详情 1200ms 闸都是阻塞式，在 `Dispatchers.IO` 上
   协程取消打断不了。 → 两个函数改 `suspend`，统一走 `WeiboThrottle`（`Mutex` + `delay`）。
3. **注释与实现不符**：类头注释声称「详情间 ≥620ms、翻页间 ≥700ms」，实际两处都是零延时。
   → 注释已改为「只有下载间隔是固定延时，翻页/详情靠 `CrawlCursor.Budget` 约束」。

---

## 6. 实测结论（2026-10-30 补测，已定论）

原本这里是一份「三组对照」的验证计划。**计划已执行完毕，结论如下**——它推翻了 §1 初稿的核心判断。

### 6.1 游客身份到底能拿到什么

按 §2.3 的 Web 端两步握手，**确实能拿到游客 cookie**：

```text
POST https://passport.weibo.com/visitor/genvisitor2
  → {"data":{"tid":"01AWAFwU-…","sub":"_2AkMduBD…","subp":"0033WrSX…"}}
GET  https://passport.weibo.com/visitor/visitor?a=crossdomain&t=<tid>&s=<sub>&sp=<subp>&from=weibo&_rand=…
  → Set-Cookie: SUB=…; domain=.weibo.com; expires=1 年后
  → Set-Cookie: SUBP=…; domain=.weibo.com
```

拿到的两份 cookie 属于 **`.weibo.com` 域**，有效期 1 年。

### 6.2 用这份 cookie 打各端点（决定性表）

| 端点 | 结果 | 判定 |
|---|---|---|
| `weibo.com/ajax/statuses/mymblog`（Web 版用户时间线） | **403** `{"ok":0,"message":"前方有点拥堵，请登录后使用"}` | ❌ 必须登录 |
| `m.weibo.cn/api/container/getIndex?containerid=107603<uid>` | **`ok:-100`** → 跳登录 | ❌ 必须登录 |
| `m.weibo.cn/api/container/getIndex?type=uid&value=` | **`ok:-100`** | ❌ 必须登录 |
| `m.weibo.cn/api/container/getIndex?containerid=100808<hash>`（超话详情） | **`ok:-100`** | ❌ 必须登录 |
| `weibo.com/ajax/profile/info` | **200 `ok:1`**，返回完整 user 对象 | ✅ 免登录可用 |
| `weibo.com/ajax/statuses/show` | **200 `ok:1`**，返回 status（含 `pic_ids`） | ✅ 免登录可用，但**无帖级 `live_photo`** |
| `weibo.com/ajax/profile/getImageWall` | **200**，`data.list` 每页 20 条，含 `mid`/`object_id`/`timeline_month` | ✅ 免登录可用 |
| `m.weibo.cn` 无 cookie 的 `getIndex` | **HTTP 432**（风控） | ❌ |

### 6.3 结论（三条，按重要性）

1. **抓图无法与账号解耦**。抓图的入口是「用户微博列表」，而它在 m 站与 Web 端**都要求登录**。这条链路上没有免登录替代，`pickCrawlCookie` 必须保留账号探活。
2. **游客身份只能覆盖 Web 端的一部分读取**：`profile/info`（昵称）、`statuses/show`（单条）、`getImageWall`（相册）。其中 `statuses/show` 虽然能读，但字段结构不同于 m 站（没有 `pics` 配对与 `live_photo`），切过去会让 Live 动图退化为静图，所以详情仍走 m 站。
3. **相册墙是唯一有价值的免登录抓图入口**：`getImageWall` 每页 20 条、只含有图微博、且返回 `mid`（正是抓取索引用的业务身份）。它不是「请求量降一个数量级」，但能跳过大量纯文字帖，且**零账号消耗**——可作为独立模式，而不是替换现有链路。

### 6.4 仍然成立的优化（不依赖游客身份）

| 优化 | 依据 |
|---|---|
| 探活结果缓存 90 秒 | `verify()` 被 9 处调用，实际请求量≈业务量的 2 倍；只缓存 `login=true` 不影响登录页轮询 |
| `Thread.sleep` → `delay` + 统一闸门 | 阻塞式 sleep 在协程取消时打断不了 |
| 昵称改走游客 `profile/info` | 实测可用，且**没有账号时也能显示博主名** |

### 6.5 IP 变量（仍成立）

`ajax/side/hotSearch` 从本机返回 `{"error":"Forbidden"}`，而第三方 2026-10-19 在家宽网络实测返回 `ok:1`。同一接口两种结果 → **IP 信誉是独立变量**，机房/代理 IP 的结论不可用。

---

## 7. 端口可用性复核与优先级

| 端口 | 实测 | 用途 | 优先级 | 收益 | 风险 |
|---|---|---|---|---|---|
| `ajax/profile/info` | ✅ 200 | 用户昵称/头像 | **已接入** | 无账号也能显示博主名；替掉 m 站 `type=uid` | 无 |
| `ajax/profile/getImageWall` | ✅ 200 | 相册墙（20+ 条/页，含 `mid`/`object_id`） | **已接入（v0.9.8）** | 只枚举有图微博，跳过纯文字帖；零账号消耗 | 游标是复合串，翻页正常但只有第 1 页带 `timeline_year/month` |
| `ajax/statuses/show` | ✅ 200 | 单条微博（`pic_ids` 全量） | **已接入（v0.9.8）** | 18 图帖实测 `pic_num=18 / pic_ids=18` 全量返回 | 无帖级 `live_photo`，Live 靠 `pic_infos[pid].video` |
| `ajax/statuses/mymblog` | ❌ 403 | 用户时间线 | 不接 | — | 需登录，无价值 |
| `ajax/statuses/longtext` | 未测 | 长文正文 | P2 | 补全长文 | 待实测 |
| `m.weibo.cn/comments/hotflow` | 未测（预期 `-100`） | 评论 | P2 | 只读评论面板 | m 站侧大概率要登录 |
| `ajax/side/hotSearch` | ⚠️ IP 相关 | 热搜榜 | P3 | 零凭据看板 | 机房 IP 被 Forbidden |
| `profile/getprofilevideolist` | 未测 | 视频列表 | P3 | 视频博主封面 | 待实测 |

### 明确不接

| 端口 | 理由 |
|---|---|
| `api.weibo.cn/2/*`（App 端） | 需 `gsid` + App 私有签名（`s`/`from`/`c`），非公开且随客户端版本失效；纯逆向投入产出比极差 |
| `login.sina.com.cn/sso/*`（登录逆向） | 本项目已用 WebView 走官方登录页（README:4「账密不经本应用」）。逆向 SSO 会直接毁掉这条安全承诺 |
| 微博开放平台 OAuth 接口 | 已核实：**官方没有超话签到、也没有「我关注的超话列表」**；且 `statuses/user_timeline` 只返回最新 5 条且仅限本人，无法替代抓图 |

---

## 8. 改造路线图

### 已实施（v0.9.7）

| 项 | 文件 | 内容 |
|---|---|---|
| 1 | **新建 `GuestSession.kt`** | Web 访客身份：`genvisitor2` → `visitor?a=crossdomain` 两步握手，取 `SUB`/`SUBP`；365 天落盘缓存；`/api/config` 探活后才采用；全程不抛异常，拿不到就返回 null |
| 2 | `WeiboApi.fetchUserName` | 游客走 `ajax/profile/info` 优先，拿不到再回落 m 站 `type=uid`；**没有账号也能取到博主名** |
| 3 | `WeiboApi.verify` | 加 90 秒缓存，**只缓存 `login=true`**（登录页轮询依赖实时 `false`，不能缓存）；`signDetailed` 的验签重试强制 `force=true` |
| 4 | **新建 `WeiboThrottle.kt`** | 按 key 的请求间隔闸门，`Mutex` + `delay` 实现，协程取消即中断 |
| 5 | `WeiboApi.listTopics` | `suspend` 化，`Thread.sleep(500)` → `WeiboThrottle.await` |
| 6 | `WeiboApi.fetchTopicDetail` | `suspend` 化，去掉 `synchronized + Thread.sleep` 的手写闸门，改用同一闸门 |
| 7 | `Crawler` | 删掉 `savePost` 里从未使用的 `cookie` 参数；注释与实现对齐（翻页/详情本就没有固定延时，靠预算约束） |
| 8 | `GalleryFragment` | 添加抓图目标时不再要求「先有账号」才能显示博主名 |
| 9 | `WcmApp` | `GuestSession.init(this)` |

**未实施（实测否掉了）**：~~抓图与签到解耦~~ → 微博列表与详情都必须登录，见 §6.3。

### P1 · 相册抓图模式（已在 v0.9.8 落地）

| 步骤 | 落地情况 |
|---|---|
| 1 | `WebAjax.imageWall`：`uid` + `sinceid` 翻页，取 `data.list[].mid`，按 mid 去重 |
| 2 | 以 `mid` 为业务身份接进 `CrawlIndex` —— 与时间线模式同一个 postId 口径，**两种模式共享去重** |
| 3 | 逐帖取详情：游客 `ajax/statuses/show` 优先，失败/缺图回落账号 + m 站详情 |
| 4 | 落盘仍走 `wx*.sinaimg.cn/large/<pid>.jpg`，命名沿用 `AssetName` |
| 5 | 与时间线并联：全局开关（图片页可切），游客拿不到时自动回落时间线 |

**18 图专项**（实测 `5354995067127335` 该帖 web 详情 `pic_num=18 / pic_ids=18 / pic_infos=18`）：
图清单永远以 `pic_ids` 为准；`pic_ids` 少于自述 `pic_num` 时补拉账号详情取更全的一份；
仍不足则告警不中断。列表侧的粗筛改用 `max(pic_num, pics.length, pic_ids.length)`，
避免 0/18 形态被当成纯文字帖跳过。

### P2/P3 · 只读扩展

长文、评论、热搜看板。均为可选，失败不影响签到与抓图。

### 面板侧

`微博签到/app/weibo_client.py` 需同步 `verify` 缓存与节流口径 —— 本仓库不包含该目录，仅在此标注。

---

## 附录 A · 本次实测记录（2026-10-30，本机网络）

### A.1 无 cookie 时的行为

| 时间 | 请求 | 结果 |
|---|---|---|
| 20:52 | `GET m.weibo.cn/statuses/show?id=QcU3RlgvR` | 302 → `visitor.passport.weibo.cn/visitor/visitor?entry=sinawap&a=enter&…` |
| 20:52 | `GET m.weibo.cn/api/container/getIndex?containerid=107603…` | **HTTP 432**，空 body |
| 20:53 | `GET weibo.com/ajax/side/hotSearch` | `{"error":"Forbidden"}` |
| 20:54 | `GET` 上述 visitor URL | 200 HTML（`Sina Visitor System`），**Set-Cookie 只有 `WEIBOCN_FROM`，无 `_T_WM`** |

### A.2 游客身份获取（Web 两步握手，已成功）

| 步骤 | 结果 |
|---|---|
| `POST passport.weibo.com/visitor/genvisitor2` | `{"retcode":20000000,"data":{"tid":"01AWAFwU-…","sub":"_2AkMduBD…","subp":"0033WrSX…"}}` |
| `GET passport.weibo.com/visitor/visitor?a=crossdomain&…` | `Set-Cookie: SUB=…; domain=.weibo.com; expires=2027-10-30`、`SUBP=…`、`SVB=…` |

### A.3 用该游客 cookie 打各端点（决定性）

| 端点 | HTTP | 响应 | 判定 |
|---|---|---|---|
| `weibo.com/ajax/statuses/mymblog?uid=…` | 403 | `{"ok":0,"message":"前方有点拥堵，请登录后使用"}` | ❌ 必须登录 |
| `m.weibo.cn/api/container/getIndex?containerid=107603…` | 200 | `{"ok":-100,"url":"…/sso/signin…"}` | ❌ 必须登录 |
| `m.weibo.cn/api/container/getIndex?type=uid&value=…` | 200 | `{"ok":-100,…}` | ❌ 必须登录 |
| `m.weibo.cn/api/container/getIndex?containerid=100808…` | 200 | `{"ok":-100,…}` | ❌ 必须登录 |
| `weibo.com/ajax/profile/info?uid=…` | 200 | `{"ok":1,"data":{"user":{"screen_name":"何炅",…}}}` | ✅ 可用 |
| `weibo.com/ajax/statuses/show?id=…` | 200 | 扁平 status（`pic_ids`、`pic_infos`，**无 `live_photo`**） | ✅ 可用（不适合替代 m 站详情） |
| `weibo.com/ajax/profile/getImageWall?uid=…&sinceid=0&has_album=true` | 200 | `data.album_list[]` + `data.list[]`（20 条，含 `mid`/`object_id`/`timeline_month`） | ✅ 可用 |

> 结论：**「免登录」在微博身上是一半的**——Web 端部分读取可免登录，但抓图必需的两条链路（用户微博列表、单帖详情）都必须登录。

---

## 附录 B · 风控码与错误码

| 码 | 含义 | 处理 |
|---|---|---|
| HTTP **432** | 新浪风控拦截（常见于无 cookie / 频率异常） | 先取游客 cookie 再试；仍 432 则退避 |
| HTTP **429** | 请求过多 | 指数退避 + 熔断 |
| `ok = -100` | cookie 失效（账号态） | 中止本轮，提示重新登录 |
| `errno = 100015` | 签到验签失败（`st` 过期） | 重新 `verify()` 换 `st` 再试一次（项目已实现于 `WeiboApi.kt:509`） |
| `ok = 0` + `msg=未登录` | 需要 L2 却用了 L1 | 检查档位判定，不要盲目重试 |
| 页面标题 `Sina Visitor System` | 缺少游客 cookie | 走 §2.3 取游客身份 |
| `{"error":"Forbidden"}` | 出口 IP 被拦 | 换网络环境；机房 IP 不可用 |

---

## 附录 C · 主要参考

- gallery-dl · weibo extractor：<https://github.com/mikf/gallery-dl/blob/master/gallery_dl/extractor/weibo.py>
- weibo-helper · Api.kt：<https://github.com/cssxsh/weibo-helper/blob/master/src/main/kotlin/xyz/cssxsh/weibo/api/Api.kt>
- RSSHub · weibo 路由：<https://github.com/DIYgod/RSSHub/tree/master/lib/routes/weibo>
- weibo-crawler：<https://github.com/dataabc/weibo-crawler>
- 平台签名审计（2026-10-19）：<https://github.com/ShilongLee/Crawler/blob/main/docs/superpowers/specs/2026-10-19-platform-signature-audit.md>
- 微博用户搜索 API 文档（pansou）：<https://github.com/fish2018/pansou/blob/main/plugin/weibo/微博用户搜索API文档.md>
