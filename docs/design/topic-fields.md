# 超话字段清单（v0.9.0）

App 通过三个微博接口读取超话相关信息。本文件把**能读到的字段**、来源、解析方式与降级策略
一次列全 —— 页面上「⋮ → 字段说明」展示的是同一份内容的精简版。

> 新浪没有公开这三个接口的文档，字段以实测与社区脚本交叉验证为准。
> 所有解析都做了容错：字段缺失只会让某一行信息不显示，**不会**影响列表与签到。

## 一、关注超话列表

```
GET https://m.weibo.cn/api/container/getIndex?containerid=100803_-_followsuper[&since_id=<上页游标>]
Cookie: <登录态>   UA: iPhone   Referer: https://m.weibo.cn/   X-Requested-With: XMLHttpRequest
```

| 字段 | 含义 | 本 App 的使用 |
| --- | --- | --- |
| `ok` | `1` 成功；`-100` Cookie 失效 | 错误分支：`-100` 立刻中止本轮并提示重新登录 |
| `msg` | 错误说明 | 显示在副标题与错误块 |
| `data.cards[]` | 卡片数组 | 遍历 |
| `data.cards[].card_group[]` | 卡片内的条目数组（部分版本没有它，此时 card 自身即条目） | 两种形态都兼容 |
| `card_type` | 条目类型，**`"8"` 才是超话** | 其余类型（156 运营位、11 等）跳过 |
| `title_sub` | 超话名称 | 列表主标题 |
| `desc1` | 等级描述，形如 `Lv.7` | **等级徽章**：正则取第一个数字 → `level` |
| `desc2` | 次要描述 | 列表副行（为空时显示「点按查看详情」） |
| `scheme` | 跳转串，内含 `containerid=100808<hash>` | **提取超话 ID**（详情接口的钥匙） |
| `buttons[0].name` | `签到` / `已签`（繁简皆有可能） | 已签/待签判定 |
| `buttons[0].scheme` | 签到 URL | 执行签到（自动追加 `st`） |
| `buttons[0].params.action` | 含 `request_url=` 的签名串 | 未使用（只进原始 JSON，便于排查） |
| `itemid` | `follow_super_follow_<页>_<序>` | 未使用（排序以 `topicId`/名称为准） |
| `title_flag_pic` | 可选标识图 URL | 未使用 |
| `data.cardlistInfo.since_id` | 下一页游标 | 翻页；空串或重复即到底 |

**解析规则**

- 等级：`Regex("\\d+")` 取第一个数字，夹在 0..999；取不到记 0（不显示徽章）。
- 超话 ID：先 `URLDecoder.decode`（scheme 里可能是 `%3D`），再匹配 `100808([0-9A-Za-z]+)`。
  也兼容 `https://m.weibo.cn/p/100808<hash>/super_index` 这种路径写法。
- 已签判定：按钮名 ∈ `{已签, 已簽, 已签到, 已簽到}` **或** 按钮 scheme 为空。
- 身份键：优先 `topicId`，缺失时退化为 `n:<名称>`，用于跨页去重（后页覆盖前页）。
- 翻页：每页 10 条，页间经 `WeiboThrottle.await(KEY_TOPIC_PAGE, 500)` 限速
  （`kotlinx.coroutines.delay`，切页/退出能立刻取消；早期实现是 `Thread.sleep`），
  最多 20 页（约 200 个超话）。

## 二、超话主页详情（点开某条才请求）

```
GET https://m.weibo.cn/api/container/getIndex?containerid=100808<hash>
```

| 字段 | 含义 | 展示位置 |
| --- | --- | --- |
| `data.pageInfo.title_top` | 超话正式标题 | 展开区（一般与 `title_sub` 相同） |
| `data.pageInfo.desc` | 超话简介 | 展开区正文（最多 4 行） |
| `data.pageInfo.desc_more[]` | 关注数 / 帖子数 / 阅读数等文本项（字符串，少数版本是 `{name,value}`） | 展开区指标行（取前 3 项，`·` 连接） |
| `data.pageInfo.portrait_sub_text` | 头部副文案 | 展开区备注尾部 |
| `data.pageInfo.oid` | `100808:<hash>` | 原始 JSON / 便于对账 |
| `data.cards[]` | 超话内帖子流 | 未使用（本 App 只做信息展示） |

**节流**：进程级最小间隔 1200ms，且只在用户点开时触发；结果按超话 ID 缓存 30 分钟
（存 `wcm_topics` 的 `detail_v1_<topicId>`）。缓存命中时点开是即时的，不再发请求。

## 三、签到响应

```
GET <buttons[0].scheme>[&st=<data.st>]
```

| 字段 | 含义 | 展示方式 |
| --- | --- | --- |
| `ok` / `result` / `code` | 三种成功口径：`ok=1`、`result=1`、`code="100000"` | 成功判定 |
| `data.alert_title` | 形如「签到第 12 名」 | Toast：「签到成功 · 签到第 12 名 · 经验 +8」 |
| `data.alert_subtitle` | 形如「经验 +8」 | 同上 |
| `msg` | 失败原因（如「今天已经签到过了」） | Toast：「签到失败：<msg>」 |

## 四、账号级（`GET /api/config`）

| 字段 | 含义 | 用途 |
| --- | --- | --- |
| `data.login` | 是否已登录 | 探活 |
| `data.st` | 签到签名 | 拼到签到 URL 上 |
| `data.uid` / `data.user_id` | 账号 UID | 账号主键 |
| `data.screen_name` | 昵称 | **v0.9.10 实测：/api/config 通常不回这个字段**（data 只有 login/st/uid/loginUrl）。昵称改走 `weibo.com/ajax/profile/info?uid=`（游客 cookie 即可，`data.user.screen_name`），m 站 `getIndex?type=uid` 的 `userInfo`/`userinfo`（键名随版本变，递归解析）只作兜底 |
| `Set-Cookie` 响应头 | 换发的新 `SUB` 等 | 合并回本地账号（登录态轮换） |

## 五、本地缓存（`SharedPreferences: wcm_topics`）

| 键 | 内容 |
| --- | --- |
| `snap_v1_<uid>` | `{"v":1,"at":毫秒,"error":"","topics":[{"id","name","lv","d2","dn"}]}` |
| `detail_v1_<topicId>` | `{"v":1,"at","oid","t","intro","st":[...],"pt"}` |

- **签到链接不落盘**：`scheme` 带 `st`、短期有效，落盘后误用只会失败。缓存项读出时
  `scheme = null`，用户点「签到」前会先刷一次列表取新链接。
- 与 `AccountStore` 的 `wcm` 文件完全隔离，账号 / 抓取游标 / 目录授权零迁移。
- 坏 JSON 一律返回 `null`（宁缺不崩），与 `AccountStore` 的口径一致。

## 六、降级矩阵

| 情况 | 表现 |
| --- | --- |
| `desc1` 无数字 | 等级徽章隐藏，筛选 `Lv7+` 自然过滤掉它 |
| `scheme` 里没有 `100808` | 超话 ID 为空：详情入口不发请求，行菜单的「在微博打开」退化为签到链接 |
| `pageInfo` 结构变化 | 展开区显示「接口未返回详情」，列表与签到不受影响 |
| Cookie 失效（`ok=-100`） | 立刻中止本轮，副标题显示「Cookie 已失效」，不重试风暴 |
| 详情请求失败 | 展开区显示「详情加载失败，点按重试」，不影响其它行 |
| 断网 | 先渲染本地快照（副标题「更新于 HH:mm」），后台刷新失败只改副标题 |
