# 架构说明

> 适用版本：`0.11.0`（versionCode 35）
> 本文描述**当前真实结构**。迁移过程中的取舍与历史包袱不在这里叙述，见
> [`docs/review/refactor-audit-2026-11-15.md`](review/refactor-audit-2026-11-15.md)；
> 构建结果、运行时验证与待执行的端到端清单见 [`docs/verification.md`](verification.md)。

## 1. 模块划分

四个 Gradle 模块，包名全部是 `com.wcm.checkin`。

| 模块 | 规模 | 职责一句话 |
| --- | --- | --- |
| `:core` | 32 文件 / 约 5.4k 行 | 共享内核：模型、端口接口、纯算法、微博客户端、文件与媒体原语 |
| `:data` | 10 文件 / 约 2.0k 行 | 需要 `Context` 的本地状态：账号表、抓取设置、超话缓存、凭证库、端口适配器 |
| `:domain` | 11 文件 / 约 2.9k 行 | 服务编排：签到、保活、抓取、自动重登。只认端口，不认实现 |
| `:app` | 43 文件 / 约 10.4k 行 | Compose UI、Navigation 壳、WorkManager、Hilt 组合根 |

```
:app
 ├── :domain ──▶ :core
 ├── :data   ──▶ :core
 └── :core
```

### 1.1 依赖规则（CI 有门禁）

| 规则 | 检查方式 |
| --- | --- |
| `:domain` 不得依赖 `:data` | `grep 'project(":data")' domain/build.gradle.kts` 必须零命中 |
| `:core` 不依赖任何上层模块 | `core/build.gradle.kts` 只允许 AndroidX 基础库 |
| `:domain` 不引用 Android UI | 无 `androidx.compose.*` / `android.app.Activity` |
| `:data` 不引用 UI 与 `:domain` | `data/build.gradle.kts` 无 `project(":app")` / `project(":domain")` |

依赖倒置的落法：**端口接口在 `:core`**（`Ports.kt`），实现在 `:data` 的 `PortAdapters.kt`，
由 `:app` 的 `DomainModule` 装配。`:domain` 只 import 接口。

> 包名仍是同一个 `com.wcm.checkin`，这是迁移期刻意的选择——零 import 改动。
> 因此**模块边界靠 Gradle 强制，不靠 package 强制**，读代码时不要以为同名包就是同模块。
> 拆包是一次独立的机械迁移，收益是工具能自动告警越界，成本是全仓 import 重排。

## 2. 各模块放什么、不放什么

### `:core` — 共享内核

| 类别 | 内容 |
| --- | --- |
| 模型 | `Account`、`CrawlTarget`、`CrawlIndex` 记录、`AccountStatusHead` |
| 端口 | `AccountPort`（账号表读写）／`CrawlSettingsPort`／`TopicCachePort`／`PanelPort`／`CredentialPort` |
| 纯算法 | `AssetName`、`Hashing`、`CrawlCursor`、`ReloginPolicy`、`CookieLife`、`PanelUrl` |
| 微博客户端 | `WeiboHttp`（出站基座 + 域名白名单）、`WeiboAuthApi`、`WeiboTopicApi`、`WeiboPostApi`、`WeiboImageUrl`、`WeiboThrottle`、`WebAjax`、`GuestSession`、`WeiboPasswordLogin`、`WeiboAlcLogin` |
| 文件与媒体 | `CrawlIndex`、`SafTreeStore`、`GalleryFiles`、`Takeover`、`RemoteBytes`、`GifEncoder`、`LivePhotoConverter` |
| 进程级资源 | `AppScope`（应用级协程作用域） |

不放：需要 `Context` 持久化的状态、任何 Android UI 依赖。

### `:data` — 本地状态实现

`AccountStore`、`CrawlSettings`、`PanelSettings`、`TopicStore`、`CrawlMarks`、`SafAccess`、
`StorePrefs`、`SecretBox`（字段级加密）、`CredentialVault`（Keystore 凭证库）、
`PortAdapters.kt`（5 个端口 32 个方法的转发）。

不放：View、网络客户端与纯算法（已在 `:core`）。

### `:domain` — 服务编排

| 服务 | 职责 |
| --- | --- |
| `CheckinService` | 账号循环 → 探活 → 列超话 → 逐条签到（3–8s 随机间隔）→ 写小结 |
| `AccountMaintenanceService` | 探活落库、补昵称、面板同步。首页「Cookie 同步」与 `CheckInWorker` 共用这一份 |
| `AutoRelogin` | 账密 / ALC 重签、退避与硬停策略 |
| `CrawlService` | 抓取**单闸门**：全应用同一时刻只允许一个抓取任务 |
| `CrawlEngine` | 单轮抓取的总装；按 `CrawlPlanner` 的规划派发 |
| `CrawlPlanner` / `CrawlProgress` | 规划（增量 / 全量 / 重试）与进度归约，纯函数 |
| `AlbumCrawler` / `TimelineCrawler` | 两条枚举链路的实现 |
| `CrawlSaver` / `PostAssets` | 落盘、三层去重、索引写入 |

不放：Android UI、资源 `R.*`、任何对 `:data` 的 import。

### `:app` — UI 与装配

`WcmApp`（`@HiltAndroidApp`）、`MainActivity`（ComponentActivity + NavHost 壳）、
`LoginWebViewActivity`（唯一保留 ViewBinding 的页面）、四个 Tab 的 Screen/ViewModel、
浏览/选图/预览 route、`CheckInWorker`、`DomainModule`。

## 3. 运行时结构

### 3.1 导航

`MainActivity` 用 Navigation Compose + `NavigationBar`，9 条 route：

```
home ─┐
topic ─┼─ 四个 Tab（saveState/restoreState，切回保留滚动与筛选）
gallery ┤
settings ┘
        └─ galleryBrowse / browsing / postPick / imagePreview / remotePreview（入栈子页）
```

四个 Tab 的 ViewModel 挂在 **Activity 作用域**，切 Tab 不丢状态；子页各用自己的
ViewModel。只有一个 Activity，只有登录页例外。

### 3.2 状态与并发

| 主题 | 约定 |
| --- | --- |
| 长任务 | 挂 `:core` 的 `AppScope`（`SupervisorJob + Dispatchers.IO`），不随页面销毁。ViewModel 只观察，不拥有任务 |
| 跨组件状态 | `StateFlow`（不可变快照，整体替换）/ `SharedFlow`（一次性事件）。不写回 `MutableState` 字段 |
| 超话快照 | `HomeViewModel.topicStates: StateFlow<Map<String, TopicState>>`，IO 写、主线程读，避免遍历时并发扩容 |
| 抓取闸门 | `CrawlService` 单实例 + companion 里的 `GATE` / `STOP`，即使误配两个实例也不会出现两个闸门 |
| 账号表并发 | `AccountStore` 的读改写在 `LOCK` 内；抓取设置由 `CrawlSettings.LOCK` 保护 |
| 可测性 | 纯函数（`CrawlPlanner` / `CrawlCursor` / `CrawlProgress.reduce` / `AssetName` / `ReloginPolicy` / `CookieLife`）保持 JVM 可测，是 331 个 JVM 单测的主体 |

### 3.3 依赖注入

`WcmApp` 标注 `@HiltAndroidApp` 并实现 `Configuration.Provider`；
manifest 关闭 WorkManager 默认初始化，避免自定义 `HiltWorkerFactory` 被覆盖。

| 注入点 | 注解 |
| --- | --- |
| Application | `@HiltAndroidApp` |
| `CheckInWorker` | `@HiltWorker` + `@AssistedInject` |
| 9 个 ViewModel | `@HiltViewModel` |
| 服务与端口 | `DomainModule` 的 13 条 `@Provides`，全部 `@Singleton` |

`:domain` **刻意不引入 Hilt**：它只有构造函数注入，装配集中在 `:app` 的 `DomainModule`。
好处是 `:domain` 不依赖 DI 框架，将来抽 `:feature` 或换框架不用动业务代码。

`CrawlSaver` 的三个纯函数（如 `slotsForPics`）留在 `companion object`，单测调用点零改动。

版本：Hilt `2.51.1`、KSP `1.9.24-1.0.20`，与 Kotlin `1.9.24` 对齐。

## 4. 不变量：改动时必须保留

违反任意一条都会造成线上数据损坏或行为回归，**没有例外**。

1. **抓取单闸门**：任何新增入口（定时、通知、快捷方式、后台）都必须经 `CrawlService`。
   绕过它会同时出现两条抓取任务写同一个 SAF 目录。
2. **三层去重与索引终态语义**：不要绕过 `CrawlSaver` 直接写文件或索引。
   索引的终态记录是「这帖抓完了」的唯一判据。
3. **数据兼容**：SharedPreferences 文件仍叫 `wcm`，索引仍是
   `filesDir/crawl/index/<treeTag>.ndjson`，抓取模式常量仍是 `album` / `timeline`，
   keystore 与签名不变。改任何一个都会让老用户升级后丢账号或重抓。
4. **安全口径**：出站 URL 只允许 https + 微博域名白名单；面板地址公网必须 https。
   登录 WebView 的白名单与出站白名单是两套，不要把 `isLoginHost` 用到出站请求上。
5. **凭据不落地明文**：cookie、账号密码、ALC 一律经 `SecretBox` / `CredentialVault`
   用 Keystore 加密；日志与错误信息不得包含凭据本身。
6. **签到与面板同步解耦**：签到成功不因面板 5xx 被判失败。两件事的失败语义分开。
7. **不调用 `sso/updatetgt.php`**：那条旧「更新链」会让原凭证在 5 秒内作废，
   是「续期变自杀」，见 `WeiboAlcLogin` 类注释。

## 5. 加新功能时往哪放

| 你要加的东西 | 放哪 | 注意 |
| --- | --- | --- |
| 新的微博接口 | `:core` 的 `WeiboTopicApi` / `WeiboPostApi` 等 | 出站走 `WeiboHttp`，不要自己 new OkHttpClient |
| 新的纯计算逻辑 | `:core`，写成顶层纯函数 | 配套 JVM 单测，别塞进 ViewModel |
| 新的持久化字段 | `:data` 的 `StorePrefs` 键集 + `:core` 的模型 | 必须给默认值，老数据要能读 |
| 新的业务编排 | `:domain` 的新服务 | 依赖只允许是 `:core` 的端口 |
| 新服务要读的本地状态 | 先在 `:core` 的 `Ports.kt` 加接口，再在 `:data` 的 `PortAdapters.kt` 实现 | 端口**不声明默认值**，默认值留实现类 |
| 新页面 | `:app` 的 Screen + ViewModel + 一条 route | 长任务仍挂 `AppScope` |
| 新的一次性后台任务 | `:app` 的 `@HiltWorker` + `DomainModule` | Worker 类名要进 `proguard-rules.pro` 的 keep 列表 |

## 6. 构建入口

本机 JDK 需显式指定（机器上没有全局 `java`）：

```bash
JAVA_HOME=C:/Users/adsm/dev/jdk17 ./gradlew --offline \
  :app:testDebugUnitTest :app:assembleDebug

# release（R8 + 资源收缩 + 固定签名）
JAVA_HOME=C:/Users/adsm/dev/jdk17 ./gradlew --offline :app:assembleRelease

# 真机/模拟器冒烟（需连接设备）
JAVA_HOME=C:/Users/adsm/dev/jdk17 ./gradlew :app:connectedDebugAndroidTest
```

环境版本、CI 流水线与产物指标见 README 的「环境要求」「构建」「发版」三节；
最近一次全量门禁结果与待执行的真机清单见 [`docs/verification.md`](verification.md)。
