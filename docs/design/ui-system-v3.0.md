# UI 系统 v3.0 —— 收紧与分型

> 工程：`com.wcm.checkin`（微博签到助手，v0.14.0 / minSdk 23 / targetSdk 36）
> 上一版：[`ui-system-v2.0.md`](ui-system-v2.0.md)（色板与四档适配仍然有效，本文只做**减法与分型**）
> 落地范围：**只动呈现层**。抓取内核、签到逻辑、面板同步、Cookie 存储、Engage 调度一律不动。
> 可视化对照：[`preview-v3.0.html`](preview-v3.0.html)（浏览器打开，浅色主题）

---

## 0. 一页结论

v2.0 定下了「冷灰底 + 白卡 + 玫红只占小面积」的语言，配色方向是对的，不需要推翻。
问题出在**刻度没有落地，而且每页各写一套**：

| 结论 | 事实 |
|---|---|
| 字号比规范放大约 1.4 倍 | `Theme.kt` 没有传 `typography`，全站跑 M3 默认 28/24/22/16；`type.xml` 里 10 档样式已被注释掉 |
| 卡片圆角有 4 档、内边距有 5 档 | 18/16/14/12 与 18/16/14/12/10 混用，同屏相邻卡片的文字左边线对不齐 |
| 页面骨架有 5 套 | 全工程 0 处 `Scaffold` / `TopAppBar`，6 个页面 6 套手搓顶栏（28sp / 22sp / 无标题） |
| 一屏红色出现 2 次 | 顶栏「＋」实底方块 + 主按钮同屏，违背 v2.0 §1.1「一屏红色面积要小」 |
| 12 处用文字字符当图标 | `⋮ ⟳ ✕ ⌃ ⌄ ✓`，字形随系统字体变，放大字体后错位 |
| 间距只有一级 | 四个 Tab 页全部 `spacedBy(10.dp)` 一铺到底，区块之间与卡片之间距离相同 |

v3.0 的动词只有两个：**收（统一刻度与骨架）** 和 **分（四类页面各有签名特征）**。

改完之后一屏能多放一行账号；顶栏高度不变但标题小了，卡片之间出现「疏—密」节奏；
用户在设置页一眼看出「这是在填东西」，在详情页一眼看出「这是在读东西」——**不靠加颜色、不靠加装饰**。

---

## 1. 现状体检（12 项，可按行号核验）

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 1 | `MaterialTheme(colorScheme = scheme)` 未传 `typography` | `Theme.kt:96` | 全站使用 M3 默认刻度：`headlineMedium` 28sp、`bodyLarge` 16sp |
| 2 | 10 档字体样式被注释掉，仅剩 `Xs`/`Base` | `res/values/type.xml:42-50` | XML 侧也拿不到刻度；Compose 页根本用不到 |
| 3 | 页面标题 28sp（规范要求 20） | `HomeTab.kt:200`、`TopicScreen.kt:131`、`GalleryScreen.kt:111`、`SettingsScreen.kt:68` | 中文 28sp 在小屏上「喊」，把内容压下一屏约 1.2 行 |
| 4 | 关键数字与标题同级 28sp | `HomeHero.kt:67`、`HomeHero.kt:87` | 「0/0」与「待签超话 —」主次不分 |
| 5 | 三列指标 24sp | `TopicScreen.kt:210` | 三列各 109dp，24sp 数字挤满，长数字（1234）会截断 |
| 6 | 卡片圆角 4 档 | 18 `HomeHero.kt:47`；16 `HomeTab.kt:244`、`HomeCookieCard.kt:61`、`HomeLogCard.kt:58`、`SettingsScreen.kt:173`；14 `TopicScreen.kt:462`、`GalleryScreen.kt:489`；12 = M3 默认（`EngageScreen` 8 处裸 `Card`） | 卡片不像同一个家族；任务页缺 1dp 描边 |
| 7 | 卡内边距 5 档 | 18/16/14/12/10 分布见 §7 清单 | 相邻卡片文字左边线错位 2–8dp |
| 8 | 硬编码间距，不走令牌 | `HomeTab.kt:93`、`TopicScreen.kt:80`、`GalleryScreen.kt:82` 全为 `spacedBy(10.dp)` | 标题、Hero、卡片、日志距离相同 → 读成一串等价块 |
| 9 | 页面骨架 5 套 | 无 `Scaffold`；`HomeTab.kt:188`、`TopicScreen.kt:120`、`GalleryScreen.kt:106`、`SettingsScreen.kt:64`、`EngageScreen.kt:106`（**无标题**）、`BrowsingScreen.kt:110`、`GalleryBrowseScreen.kt:134` | 切 Tab 时标题跳变 28→22→无；左右留白 16/8/16+12 三种 |
| 10 | 文字字符当图标，12 处 | `⋮`×4、`⟳`×3、`✕`×2、`⌃⌄`×2、`✓`×1；`GalleryScreen.kt:299` 的 `⋮` 无 `contentDescription` | 大字体下错位、基线不居中、读屏静默 |
| 11 | `colorScheme.error` 用了 19 处 | `EngageScreen.kt` 12 处、`EngageTaskDetailScreen.kt:362`、`EngagePostLogScreen.kt:86` 等；`Theme.kt:41` 注释明确禁止 | 拿到 M3 默认红 `#B3261E`，与品牌语义红 `#B42318` 并存两个红 |
| 12 | 同屏两个品牌实底 | 首页 `HomeTab.kt:222`（＋方块）+ `HomeTab.kt:108`（立即签到）；图片页 `GalleryScreen.kt:121` + 开始抓取 | 违背 v2.0 §1.1，主行动不再是唯一焦点 |

> 附带文档口径偏差：v2.0 §2 写「底部导航 4 项」，实际 `MainActivity.kt:580-644` 是 5 项（含「任务」）。
> v3.0 以代码为准，改文档。

---

## 2. 四条设计原则

1. **少一档，不是多一档。** 圆角 9 档 → 3 档；字号 11 档 → 8 档；间距 5 档 → 3 档。
   削减本身就是「高级感」的主要来源——选择越少，越像一个体系。
2. **层级靠留白和字号，不靠颜色和装饰。** 一屏之内只允许出现一块品牌实底（主按钮）。
   状态色只允许出现在 pill 尺寸以内。
3. **每类页面有一个签名动作。** 首页有 Hero（大内边距 + 进度环）、列表页有固定概览条、
   详情页有键值右对齐、表单页有卡外分组标题。用户不用读字就知道自己在哪类页面。
4. **骨架统一，内容自由。** 顶栏、页面留白、卡片材质全站一致；
   卡片内部怎么排由页面任务决定。

---

## 3. 令牌

### 3.1 间距与栅格（4dp 基准，3 档语义）

| 令牌 | 值 | 用途 |
|---|---|---|
| `WcmSpace.section` | **20dp** | 区块之间（标题组 ↔ 内容组 ↔ 下一区块） |
| `WcmSpace.cardGap` | **12dp** | 同级卡片之间 |
| `WcmSpace.cardPad` | **16dp** | 卡片内边距（唯一值，Hero 用 `heroPad` 20dp） |
| `WcmSpace.rowV` | **12dp** | 列表行垂直内边距（列表页行更紧：10dp） |
| `WcmSpace.pageBottom` | **24dp** | 滚动末端留白（替代贴边结束） |
| `page_padding_horizontal` | 16 / 24 / 32（沿用 `dimens.xml` 四档） | 页面左右留白 |

**节奏对比**

```
改前（全 10dp）：  标题 ─10─ Hero ─10─ 按钮 ─10─ Cookie卡 ─10─ 账号卡 ─10─ 日志
改后（两级）：    标题 ─20─ Hero ─12─ 按钮 ─20─ Cookie卡 ─12─ 账号卡 ─20─ 日志
                            ↑ 动作紧贴结论          ↑ 同组收紧   ↑ 换组拉开
```

主行动与 Hero 用 12dp（同组），换到下一个信息组时用 20dp。**这是本次改版最省力也最有效的动作。**

### 3.2 字体（Compose `Typography`，一次覆盖全站）

不给每个 `Text` 改字号，而是把 M3 角色重新定义成产品刻度——现有调用点不动就自动缩小。

| M3 角色 | 原值 | **新值** | 行高 | 字重 | 字距 | 主要用途 |
|---|---|---|---|---|---|---|
| `headlineMedium` | 28 | **22** | 28 | 500 | -0.4sp | 页面标题（唯一一档，Tab 页与二级页共用） |
| `headlineSmall` | 24 | **20** | 26 | 500 | -0.2sp | 指标数字（`tnum`） |
| `titleLarge` | 22 | **20** | 26 | 500 | -0.2sp | 弹窗标题、详情页主实体名 |
| `titleMedium` | 16 | **15** | 22 | 500 | 0 | 卡片标题 |
| `titleSmall` | 14 | **13** | 19 | 500 | 0 | 小节标题 |
| `bodyLarge` | 16 | **15** | 22 | 400 | 0 | 行标题（账号名、超话名、开关名） |
| `bodyMedium` | 14 | **14** | 21 | 400 | 0 | 正文 |
| `bodySmall` | 12 | **12** | 18 | 400 | 0 | 辅助说明、UID、时间 |
| `labelLarge` | 14 | **14** | 20 | 500 | 0 | 按钮文案 |
| `labelMedium` | 12 | **12** | 16 | 500 | 0 | chip 文字 |
| `labelSmall` | 11 | **11** | 16 | 400 | 0.1sp | pill、角标、键名 |

**中文三条约束不变**：只用 400 / 500 两档字重、字距只收紧不放开、数字开 `tnum`。

**22sp 而不是 v2.0 的 20sp**：20sp 与卡片标题 15sp 只差 1.33 倍，5 个 Tab 的顶栏会显得「没有标题」。
22sp 是 1.47 倍，层级仍然明确，且比 28sp 省下 6sp×2 行。若想更保守，回落 20sp 只改 `WcmTokens.kt` 一处。

### 3.3 圆角（9 档 → 3 档 + pill）

| 令牌 | 值 | 收编原值 | 用途 |
|---|---|---|---|
| `WcmShape.card` | **16dp** | 18 / 16 / 14 / 12 | 所有卡片（**含 Hero**，取消 18dp 变体） |
| `WcmShape.inner` | **10dp** | 10 / 8 / 6 | 卡内嵌块：图标托、终端块、缩略图、输入框 |
| `WcmShape.chip` | **8dp** | 8 / 6 / 4 | 徽章（等级 Lv.N） |
| `WcmShape.button` | **12dp** | M3 默认 20 | 按钮 |
| `WcmShape.pill` | 999dp | 999 | 状态胶囊 |

同时把 `MaterialTheme.shapes` 的 `medium/large` 设为 16dp、`small` 设 10dp——
这样 8 处裸 `Card()` 不改代码也会跟着变成 16dp（安全网）。

**为什么 Hero 也用 16**：18 与 16 肉眼不可辨，却要维护两套变体。统一后「卡片家族」成立。

### 3.4 颜色（只加 2 个，收缩用法）

色板保持 v2.0，**不改任何既有色值**。新增：

| 令牌 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `line_soft` | `#F2F4F7` | `#1F242C` | 卡内分隔线（比 `line` 更轻，不与卡片描边抢） |

> 原方案还规划了 `brand_soft_2`（`brand_soft` 上的按压态）。落地时**没有加**：
> 当前没有任何组件覆写按压态，加了就是一个必然被 lint 判为 unused 的色值。
> 等真正做出按压/选中加深的交互层时再补，一次只加一个真在用的令牌。

**三条新纪律**

1. **一屏一个品牌实底**：顶栏「＋」由 `brand` 实底方块改为 `brand_soft` 底 + `brand_strong` 图标（44dp / 12dp 圆角）。主按钮独占实底。
2. **状态色不出 pill**：`ok/warn/danger` 只用于 pill 底与 pill 字；错误文案用 `danger_text`，不用 `colorScheme.error`。
3. **不新造灰**：`ink / ink_2 / ink_3` 三档够用，`line / line_2 / line_soft` 三档线够用。

---

## 4. 骨架：一个页面，一条顶栏

全站改用 `WcmPage`（新建 `WcmScaffold.kt`），不再手搓。

```
┌─────────────────────────────────────────────┐
│  WcmTopBar  48dp 内容 / 68dp 视觉带          │  标题 22sp Medium + 副标题 12sp
│  [← ]  标题                       [ ][ ]    │  右侧最多 2 个 40dp 图标钮（可点 48）
├─────────────────────────────────────────────┤
│                                             │
│  内容区：page_padding_horizontal            │
│  首块距顶栏 20dp（section，不加分隔线）      │
│                                             │
├─────────────────────────────────────────────┤
│  底部导航 80dp（仅 5 个 Tab 页）             │
└─────────────────────────────────────────────┘
```

**为什么不用 `TopAppBar`**：M3 的 `TopAppBar` 强制 64dp 高、标题 22sp + 强制滚动行为，
与本产品的「48dp 内容 + 12sp 副标题」叠排不同。自建 40 行，换来六页一致。

**顶栏规则**

| 项 | 规格 |
|---|---|
| 容器 | 高 48dp；上下各 10dp 呼吸 → 视觉带 68dp；左右 16dp（与内容对齐） |
| 标题 | `headlineMedium` 22sp / 500 / -0.4sp，单行省略 |
| 副标题 | `bodySmall` 12sp / `ink_3`，单行省略；无副标题时标题垂直居中 |
| 图标钮 | 视觉 40dp 圆角 12dp，可点区 48dp；`ink_2` 图标 22dp |
| 返回 | 二级页左侧 `ic_arrow_back`，与图标钮同规格；无 hover 底色 |
| 分隔线 | **不加**。靠 20dp 留白分组，加线会切碎留白节奏 |

---

## 5. 四类页面的差异化（本方案的核心）

区分靠**结构**，不靠换色。下面是每类页面的「签名特征」。

### 5.1 首页（仪表盘）— 签名：Hero 块

| 层级 | 内容 | 规格 |
|---|---|---|
| 1 结论 | Hero 卡：88dp 进度环（左）+ 待签大数字（右）+ 2 枚 pill + 一句话 | 卡内 20dp；环内数字 22sp，待签数字 20sp（**不再同级**） |
| 2 行动 | 立即签到（品牌实底 48dp）+ Cookie 同步（描边 48dp） | 紧贴 Hero，间距 12dp（同一组） |
| 3 状态 | 登录状态卡（Cookie 行） | 组间 20dp |
| 4 明细 | 账号卡（3 行，行长 64dp） | 组间 12dp |
| 5 追溯 | 日志卡（折叠，展开是深色终端 200dp） | 组间 20dp |

**关键改动**：Hero 内的两个数字改成 22sp（进度）与 20sp（待签），并用 12sp 小标签分隔；
现状 28sp + 28sp 让「0/0」和「—」互相打架。

### 5.2 列表页（超话 / 图库浏览 / 任务日志）— 签名：固定概览条 + 高密度行

| 层级 | 内容 | 规格 |
|---|---|---|
| 1 概览 | 三列指标条（关注 26 / 已签 21 / 待签 5），列间 1dp 竖线，**无卡片标题** | 数字 20sp tnum，标签 11sp；条高 72dp |
| 2 切换 | 账号 pill 横向滚动（44dp 行高）+ 筛选 chip 行（44dp） | chip 11dp 圆角，选中 `brand_soft` + `brand_strong` |
| 3 行 | 行卡：徽章 + 名称 15sp + 状态 pill + ⋮；第二行 meta 12sp | 卡内 12dp 垂直（**比首页 12 更紧**）；行卡之间 **8dp**（不是 12） |

**签名逻辑**：列表页的行间距（8dp）比首页卡片间距（12dp）更紧，一屏能多读 2 行；
概览条常驻不滚动（超话页现状已对，保持）。

### 5.3 详情页（任务详情 / 帖子日志 / 帖子精挑）— 签名：键值右对齐 + 小标题

| 层级 | 内容 | 规格 |
|---|---|---|
| 1 顶栏 | ← + 主实体名 22sp + 状态副标题 | `WcmTopBar` |
| 2 分组 | `sectionLabel`：13sp / 500 / +0.4sp，`ink_3`，**在卡片外** | 组间 20dp |
| 3 键值 | 行：键 14sp `ink_2`（左）/ 值 14sp 500 `ink`（右对齐） | 行高 44dp，行间 `line_soft` 1dp |
| 4 追溯 | 日志流：11sp 等宽，行内左侧 3dp 状态色竖条 | 卡内 `surface_3` 底 |

**签名逻辑**：详情页只用来「读」，所以数字全部右对齐成列、分组标题放在卡外——
和表单页的「标题在卡内」形成对比。

### 5.4 表单页（设置 / 任务配置 / 文案库编辑）— 签名：卡外分组 + 字段竖排

| 层级 | 内容 | 规格 |
|---|---|---|
| 1 顶栏 | 标题 + 一句「这一页能做什么」 | `WcmTopBar` |
| 2 分组标题 | 卡**外**：`titleSmall` 13sp / 500 / `ink_2`，距卡 8dp | 组间 16dp |
| 3 字段 | 卡内竖排：outlined 输入框 48dp + 字段间 12dp；开关行垂直 12dp + `line_soft` 分隔 | 卡内 16dp |
| 4 提交 | 保存按钮**独占一行** 48dp，紧跟字段组（不贴底、不浮动） | 距上一字段 12dp |

**为什么分组标题要放卡外**：卡内既有「卡片标题」又有「字段」，视觉上字段和标题平级，
用户扫视时找不到「从哪开始填」。标题搬到卡外后，卡片本身变成纯粹的「一块可填区域」。

### 5.5 全屏页（图片预览 / 远程预览）— 不适用上表

恒黑背景、系统栏恒浅色、底部 44dp 图标条。**本次不动**，只在图标层把文字字符换成矢量图。

---

## 6. 组件清单

| 新组件 | 文件 | 替代的散落实现 |
|---|---|---|
| `WcmSpace` / `WcmShape` / `WcmType` / `WcmTypography` / `WcmShapes` | 新建 `WcmTokens.kt` | 各文件里的裸 `dp` 与 `RoundedCornerShape` |
| `WcmPage` / `WcmTopBar` | 新建 `WcmScaffold.kt` | 6 套手搓顶栏（`HomeTab`/`TopicScreen`/`GalleryScreen`/`SettingsScreen`/`BrowsingScreen`/`GalleryBrowseScreen`） |
| `WcmCard` / `WcmSectionLabel` / `WcmDivider` | 新建 `WcmCard.kt` | `WcmTopicCard`（`TopicScreen.kt:454`）、`WcmCard`（`GalleryScreen.kt:483`）、`WcmCard`（`SettingsScreen.kt:170`）、8 处裸 `Card()` |
| `WcmListRow` / `WcmKeyValueRow` / `WcmIconTile` | 新建 `WcmRow.kt` | 账号行、Cookie 行、抓取目标行、图库入口卡、存储位置卡 |
| `WcmEmptyState` / `WcmStatusPill` / `WcmMetricStrip` | 新建 `WcmState.kt` | 三套空态（`HomeAccounts.kt:73`、`TopicScreen.kt:438`、`GalleryBrowseScreen.kt:469`）、7 处 pill |
| 矢量图标 | 新增 `res/drawable/ic_more_vert.xml`、`ic_refresh.xml`（已有）、`ic_close.xml`、`ic_chevron_up.xml`、`ic_chevron_down.xml`、`ic_arrow_back.xml` | 12 处文字字符 |

**删除**：`HomeAccounts.kt` 的 `SmallPill`、`HomeCookieCard.kt` 的 `Pill`、`TopicScreen.kt` 的 `StatePill`/`Chip`/`LevelBadge` 保留但内部改用统一令牌（等级徽章有专属配色，保留独立实现是对的）。

---

## 7. 调整清单

### P0 —— 观感收益最高，改动局部，风险低（建议一次提交）

| # | 文件 | 位置 | 改什么 |
|---|---|---|---|
| 1 | 新建 `WcmTokens.kt` | — | 见 §8.1：`WcmSpace` / `WcmShape` / `WcmTypography` / `WcmShapes` / `WcmType` |
| 2 | `Theme.kt` | `:96` | `MaterialTheme(colorScheme = scheme, typography = WcmTypography, shapes = WcmShapes, content = content)` —— **一行生效全站字号** |
| 3 | `res/values/colors.xml` + `values-night/colors.xml` | — | 加 `line_soft`（卡内分隔线） |
| 4 | `HomeTab.kt` | `:92-93` | `contentPadding` → `start/end=pagePad, top=8dp, bottom=24dp`；`spacedBy(10.dp)` → 分组间距（见 §8.4） |
| 5 | `HomeTab.kt` | `:222-230` | 品牌实底「＋」→ `brand_soft` 底 + `brand_strong` 图标，40dp |
| 6 | `HomeHero.kt` | `:47,53,67-92` | 圆角 18→`WcmShape.card`；内边距 18→20；环内数字加 `tnum` 并用 `WcmType.metricLarge`；待签数字降为 `WcmType.metric`（20sp） |
| 7 | `EngageScreen.kt` | 8 处裸 `Card`、`:107-109` | 裸 `Card()` → `WcmCard`；padding `16/12` → `pagePad` + `WcmSpace.cardPad`；`spacedBy(12)` → section/cardGap 两级 |
| 8 | `EngageScreen.kt`、`EngageTaskDetailScreen.kt`、`EngagePostLogScreen.kt` | 19 处 | `colorScheme.error` → `colorResource(R.color.danger_text)`；`errorContainer` → `danger_soft` |

### P1 —— 骨架统一（改动面广，建议拆 2–3 个提交）

| # | 文件 | 改什么 |
|---|---|---|
| 9 | 新建 `WcmScaffold.kt` | `WcmPage` + `WcmTopBar`（含返回槽） |
| 10 | `HomeTab.kt` / `TopicScreen.kt` / `GalleryScreen.kt` / `SettingsScreen.kt` | 删除各自的 `Header()`，改用 `WcmPage(title, subtitle, actions)` |
| 11 | `EngageScreen.kt` | **新增顶栏**（现状无标题），`engage_title` 从卡内移到顶栏 |
| 12 | `BrowsingScreen.kt:110-134` / `GalleryBrowseScreen.kt:134-180` | `titleLarge` 顶栏 + 文字 `✕`/`⟳`/`⋮` → `WcmTopBar` + 矢量图标 |
| 13 | 新建 `WcmCard.kt`，替换 3 个重复 `WcmCard`/`WcmTopicCard` | 统一材质：`surface` + 1dp `line` + 0 投影 + 16dp 圆角 |
| 14 | 12 处文字字符图标 | 换 `ic_more_vert` / `ic_close` / `ic_chevron_up` / `ic_chevron_down` / `ic_arrow_back`；补 `contentDescription` |

### P2 —— 打磨（可跟随功能迭代）

| # | 文件 | 改什么 |
|---|---|---|
| 15 | 新建 `WcmState.kt` | 三套空态 → `WcmEmptyState`（圆托图标 + 标题 + 说明 + 主行动）；首页空态补上行动按钮 |
| 16 | `TopicScreen.kt:173-215` | 三列指标 → `WcmMetricStrip`：数字 20sp tnum、列间 1dp 竖线、去掉卡内标题 |
| 17 | 列表行间距 | 超话行卡、图库浏览网格外的行间距 → 8dp（列表页签名） |
| 18 | `MainActivity.kt:579-645` | `NavigationBarItem` 显式指定 `indicatorColor = brand_soft`、`selectedIconColor/selectedTextColor = brand`，不依赖 M3 默认 |
| 19 | `SettingsScreen.kt:59` | `MaterialTheme.colorScheme.background` → `R.color.bg`（与其他页取值来源一致） |
| 20 | 深色模式复查 | 8 处裸 Card 改完材质后，深色下卡片必须比 `bg` 亮 |
| 21 | 聚焦态 | 桌面模式新增 `Modifier.wcmFocusRing()`：2dp `brand` 描边 + 2dp offset（现状无聚焦态） |
| 22 | `docs/design/ui-system-v2.0.md:98` | 「4 项」→「5 项」，与实际一致 |

---

## 8. 代码

### 8.1 `WcmTokens.kt`（新建）

```kotlin
package com.wcm.checkin

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 三档语义间距。不要再用裸 dp 拼页面。 */
object WcmSpace {
    val section = 20.dp      // 区块之间
    val cardGap = 12.dp      // 同级卡片之间
    val cardPad = 16.dp      // 卡片内边距
    val heroPad = 20.dp      // Hero 卡内边距
    val rowV = 12.dp         // 列表行垂直内边距
    val rowVTight = 10.dp    // 列表页行（更紧）
    val pageTop = 8.dp
    val pageBottom = 24.dp
}

/** 3 档圆角 + pill。不要再出现 14/18/8/6。 */
object WcmShape {
    val card = RoundedCornerShape(16.dp)
    val inner = RoundedCornerShape(10.dp)
    val chip = RoundedCornerShape(8.dp)
    val button = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(999.dp)
}

/** 覆盖 M3 默认 shape，让裸 Card() 也是 16dp（安全网）。 */
val WcmShapes = Shapes(
    extraSmall = WcmShape.chip,
    small = WcmShape.inner,
    medium = WcmShape.card,
    large = WcmShape.card,
    extraLarge = WcmShape.card,
)

/** 产品刻度：22 / 20 / 15 / 14 / 12 / 11。中文只用 400 与 500 两档字重。 */
val WcmTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 19.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp,
    ),
)

/** M3 角色覆盖不到的三档，按需直接引用。 */
object WcmType {
    /** 进度环中心、概览条主数字 */
    val metricLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 30.sp, fontFeatureSettings = "tnum",
    )

    /** 卡内次级数字（待签数、剩余天数） */
    val metric = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 26.sp, fontFeatureSettings = "tnum",
    )

    /** 卡外分组标题（表单页签名） */
    val sectionLabel = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.4.sp,
    )

    /** 终端日志 */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp,
    )
}
```

### 8.2 `WcmScaffold.kt`（新建）

```kotlin
package com.wcm.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 页面骨架：顶栏 + 内容。五个 Tab 页与所有二级页共用同一条顶栏。
 *
 * 首块距顶栏 20dp（section），不加分隔线 —— 靠留白分组。
 */
@Composable
fun WcmPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg)),
    ) {
        WcmTopBar(title, subtitle, onBack, actions)
        content()
    }
}

@Composable
fun WcmTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val pad = dimensionResource(R.dimen.page_padding_horizontal)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pad, vertical = 10.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = colorResource(R.color.ink_2),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = colorResource(R.color.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** 顶栏图标钮：视觉 40dp，可点 48dp。用完不要再用文字字符当图标。 */
@Composable
fun RowScope.WcmTopBarAction(
    icon: Int,
    description: String?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = colorResource(R.color.ink_2),
            modifier = Modifier.size(22.dp),
        )
    }
}
```

### 8.3 `WcmCard.kt`（新建）

```kotlin
package com.wcm.checkin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp

/** 全站唯一卡片材质：白面 + 1dp 发丝边 + 0 投影 + 16dp 圆角。 */
@Composable
fun WcmCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = WcmSpace.cardPad,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface))
    val border = BorderStroke(1.dp, colorResource(R.color.line))
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = WcmShape.card, colors = colors, border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) { Column(Modifier.padding(padding), content = content) }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = WcmShape.card, colors = colors, border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) { Column(Modifier.padding(padding), content = content) }
    }
}

/** 卡外分组标题（表单页 / 详情页签名）。距下一张卡 8dp。 */
@Composable
fun WcmSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
        style = WcmType.sectionLabel,
        color = colorResource(R.color.ink_2),
    )
}

/** 卡内分隔线：比卡片描边更轻，避免一条卡里出现两级相同的线。 */
@Composable
fun WcmDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colorResource(R.color.line_soft)),
    )
}
```

### 8.4 首页间距改法（`HomeTab.kt:87-146`）

```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize().background(colorResource(R.color.bg)),
    contentPadding = PaddingValues(
        start = pagePad, end = pagePad,
        top = WcmSpace.pageTop, bottom = WcmSpace.pageBottom,
    ),
) {
    item(key = "header") { WcmTopBar(greeting, today) { /* 刷新 + ＋ */ } }
    item(key = "heroGroup") {
        // Hero 与主行动是同一组：组内 12dp
        Column(Modifier.padding(top = WcmSpace.section)) {
            HomeHeroCard(state = hero, onClick = { viewModel.onHeroClick() })
            Spacer(Modifier.height(WcmSpace.cardGap))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.runAll(auto = false) }, Modifier.weight(1f)) { /* … */ }
                OutlinedButton(onClick = { viewModel.syncOnly() }, Modifier.weight(1f)) { /* … */ }
            }
        }
    }
    item(key = "cookie") {
        Box(Modifier.padding(top = WcmSpace.section)) { CookieCard(cookie, { viewModel.openSettings() }) }
    }
    item(key = "accounts") {
        Box(Modifier.padding(top = WcmSpace.cardGap)) { AccountsCard(/* … */) }
    }
    item(key = "log") {
        Box(Modifier.padding(top = WcmSpace.section)) { LogCard(log, { viewModel.setLogExpanded(!log.expanded) }) }
    }
}
```

> 注意：`LazyColumn` 的 `verticalArrangement` **去掉** `spacedBy`，间距改由每个 item 的
> `padding(top = …)` 表达——只有这样「12 还是 20」才能按组区分。若保留 `spacedBy`，
> 会得到「12 + 20 = 32」的叠加。

### 8.5 `Theme.kt` 改法

```kotlin
MaterialTheme(
    colorScheme = scheme,
    typography = WcmTypography,   // ← 新增，一行把全站字号拉回刻度
    shapes = WcmShapes,           // ← 新增，裸 Card() 也变 16dp
    content = content,
)
```

---

## 9. 验收清单

- [ ] 360×640 首屏：顶栏 + Hero + 主按钮 + **第一个账号行完整可见**（现状只能看到半行）
- [ ] 全站页面标题同为 22sp；二级页与 Tab 页切换无标题跳变
- [ ] 一屏内品牌实底只出现 1 处（主按钮）
- [ ] 卡片圆角只有 16dp 一档；卡内边距只有 16dp（Hero 20dp）一档
- [ ] 区块间 20dp、同组卡片 12dp、列表行 8dp —— 三个值肉眼可分
- [ ] 无任何 `Text("⋮")` 之类的字符图标（`grep 'Text("⋮"'` 为空）
- [ ] 无任何 `MaterialTheme.colorScheme.error`（`grep` 为空）
- [ ] 字体 200%：按钮不截断、顶栏标题单行省略不换行、pill 不撑破
- [ ] 深色：卡片比 `bg` 亮，pill 不发荧光，终端块仍可读
- [ ] TalkBack：顶栏图标有描述，列表行播报「名称 + 状态」
- [ ] 桌面模式：Tab 键能看见聚焦环

---

## 10. 风险与回滚

| 风险 | 处理 |
|---|---|
| 字号整体变小，个别页面显得「空」 | 已用 24dp 页底留白 + 两级间距补偿；若仍空，把 `cardPad` 提到 18dp（单点调整） |
| `LazyColumn` 去掉 `spacedBy` 后漏改某个 item | 逐页 diff 首屏快照；漏改表现为「某块贴住上一块」，肉眼可见 |
| 裸 `Card` 改 `WcmCard` 后 padding 叠加 | `WcmCard` 已把 padding 内建，调用点若自带 `Modifier.padding` 需删掉 |
| 新增 drawable 命名冲突 | 全部以 `ic_` 前缀，先 `grep -r "ic_more_vert"` 确认 |
| 回滚 | 本次只动 Compose 呈现层与 2 个色值，`git revert` 单个提交即可；不涉及数据与业务 |

---

## 11. 落地状态（已实施，待发版）

本节记录**实际改成了什么**，与上面的方案逐条对应；方案里没做的也写在这里，不做无声省略。

### 11.1 新增文件

| 文件 | 内容 |
|---|---|
| `WcmTokens.kt` | `WcmSpace` / `WcmShape` / `WcmTypography` / `WcmShapes` / `WcmType` |
| `WcmScaffold.kt` | `WcmPage` / `WcmTopBar` / `WcmTopBarAction` / `WcmTopBarAccentAction` |
| `WcmCard.kt` | `WcmCard` / `WcmSectionLabel` / `WcmDivider` |
| `WcmState.kt` | `WcmStatusPill` / `WcmEmptyState` / `WcmMetricStrip` / `WcmMetric` |
| `res/drawable/ic_more_vert.xml` 等 6 个 | 替换全部文字字符图标（另补 `ic_arrow_back` / `ic_close` / `ic_check` / `ic_chevron_up` / `ic_chevron_down`） |

### 11.2 改动的 16 个页面文件

`HomeTab` / `HomeHero` / `HomeAccounts` / `HomeCookieCard` / `HomeLogCard` /
`TopicScreen` / `GalleryScreen` / `GalleryBrowseScreen` / `BrowsingScreen` / `PostPickScreen` /
`RemotePreviewScreen` / `SettingsScreen` / `EngageScreen` / `EngageTaskDetailScreen` /
`EngageTextScreen` / `EngagePostLogScreen`，外加 `Theme.kt` 与 `MainActivity.kt`。

合计 22 个文件、+905 / −1015 行 —— **净减少 110 行**，主要是三套重复的 `WcmCard` 实现与
六套手搓顶栏被合并。

### 11.3 与方案的三处偏差（都是落地时发现的）

1. **`WcmTopBarAction` 不做成 `RowScope` 扩展**。方案里写成 `RowScope.` 扩展，
   但超话页 / 图库页的动作钮要包在 `Box` 里做 `DropdownMenu` 的锚点，
   `Box` 内没有 `RowScope` 接收者，编译不过。改成普通函数后两处都能用。
2. **`BrowsingScreen` 的帖子卡没有换成 `WcmCard`**。它需要 `combinedClickable`
   （点击进详情 + 长按进多选），而 `WcmCard` 的 `onClick` 走 `Card(onClick=)`，拿不到长按。
   处理方式：保留手写 `Card`，只把圆角与内边距换成 `WcmShape.card` / `WcmSpace.cardPad`，
   材质与其余卡片一致。
3. **`EngageScreen` 的换组间距用 `Spacer(20 − 12)` 表达**。这页是 `Column(verticalScroll)`，
   逐个 item 加 `padding(top)` 会打断现有顺序；用「统一 12dp + 换组处补 8dp」得到同样的 20dp。

### 11.4 未做（明确留下）

| 项 | 原因 |
|---|---|
| 桌面模式聚焦环（原 P2-21） | 需要给每个可点组件挂 `interactionSource` + `focusable`，改动面比其余 P2 项大一个量级，且当前无桌面设备可验，留待单独一轮 |
| 真机像素级验收（360×640 / 412×915 / 平板 / 深色） | 本机无设备与模拟器；当前验证为「资源编译 + Kotlin 编译 + 单元测试 + lint」 |
| `type.xml` / `dimens.xml` 清理 | 仍被登录 WebView（XML 页面）与 `shape.xml` 引用，删了会连带登录页失效；待登录页也迁到 Compose 时一并处理 |

### 11.5 验证

```
env -i JAVA_HOME=<jdk17> PATH=... TEMP=... ./gradlew \
    :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
    --console=plain -Pkotlin.compiler.execution.strategy=in-process
```

本次实测：**573 个单测全绿（0 失败）**、`app-debug.apk` 正常产出（16.8 MB）、
**lint 0 error**（14 条 warning 全是既有的 `engage_*` 未用文案与 2 条
`mutableStateOf<Long>` 提示，与本次改版无关 —— 新增的令牌与文案没有留下一条 unused）。

发版时按仓库约定同步三处版本号：`app/build.gradle.kts` 的 `versionName`、
`CHANGELOG.md` 小节标题、`README.md` 的功能标注。


---

本内容由 AI 生成，请核实后使用。
