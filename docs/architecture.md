# 架构说明（架构现代化进行中）

> 状态：2026-11 架构现代化 **Phase 0–2 已完成**；Phase 3（Compose/MVVM）起为后续增量。
> 本文描述当前真实结构，不描述最终目标态的想象结构。

## 1. Gradle 模块与依赖方向

```
:app            UI（Fragment/Activity/Compose 起点）、WorkManager、Hilt 入口
 ├── :domain    签到 / 保活 / 抓取 / 重登的服务编排
 ├── :data      微博与面板网络、SharedPreferences、NDJSON 索引、SAF、Keystore
 └── :core      领域模型、纯算法、应用级协程作用域
```

依赖规则：

- `:app -> :domain, :data, :core`
- `:domain -> :data, :core`
- `:data -> :core`
- `:core` 不依赖任何上层模块；`domain` 不得引用 Android UI；`data` 不得引用 UI/domain。

> **包名仍全部是 `com.wcm.checkin`**：这是为了模块化迁移时零 import 改动。
> 模块边界目前由 Gradle 依赖强制，而不是由 package 强制。后续可把包名拆成
> `com.wcm.checkin.core/data/domain`，那是一次独立的机械迁移。

## 2. 各模块职责

| 模块 | 放什么 | 不放什么 |
| --- | --- | --- |
| `:core` | `AssetName`、`Hashing`、`CrawlCursor`/`Budget`/`Phase`/`StopReason`、`ReloginPolicy`、`AccountStatusHead`、`AppScope` | Android UI、SharedPreferences、OkHttp 业务 |
| `:data` | `WeiboHttp/Auth/Topic/Post/ImageUrl`、`PanelApi`、`GuestSession`、`WeiboPasswordLogin`、`AccountStore`、`PanelSettings`、`CrawlSettings`、`CrawlIndex`、`SafTreeStore`、`GalleryFiles`、`TopicStore`、`CredentialVault`、`GifEncoder`、`LivePhotoConverter` | View/Fragment、签到/抓取编排 |
| `:domain` | `CheckinService`、`AccountMaintenanceService`、`CrawlEngine`、`AlbumCrawler`、`TimelineCrawler`、`CrawlSaver`、`CrawlPlanner`、`CrawlProgress`、`CrawlService`、`PostAssets`、`AutoRelogin` | Android UI、资源 `R.*` |
| `:app` | `MainActivity`、四个 Fragment、浏览/选图/预览 Activity、`RemoteThumb`、`ThumbLoader`、`ZoomableImageView`、`LogPanel`、`CheckInWorker`、`WcmApp`、Compose 设置页 | 业务规则、网络/存储实现 |

## 3. 状态与并发

- **应用级长任务**挂在 `:core` 的 `AppScope`（`SupervisorJob + Dispatchers.IO`），
  不随页面销毁；ViewModel/页面只观察状态，不拥有任务。
- **首页 UI 状态**：忙碌闸门、日志缓冲、自动签到闩在 Hilt `HomeViewModel`；
  长任务仍挂 `AppScope`，ViewModel 只持有界面态。
- **超话快照**：`HomeViewModel.topicStates` 是 `StateFlow<Map<String, TopicState>>`，
  不可变 Map 整体替换；IO 线程写、主线程读，不会在遍历时被并发扩容打断。
- **抓取单闸门**仍在 `CrawlService`：全应用同一时刻只允许一个抓取任务，
  `StateFlow<RunState>` 向图库页/首页暴露进度；`requestStop` 对自动抓取同样生效。
- **签到编排**在 `CheckinService`：账号循环、探活、列超话、逐条签到、记账；
  `CheckinService.noteResult` 是唯一的“上次结果”写入口。
- **账号维护**在 `AccountMaintenanceService`：探活落库、补昵称、面板同步；
  首页“Cookie 同步”与 `CheckInWorker` 共用这一份实现。
- **超话快照**在 `:data` 的 `TopicStore`，`StateFlow<Map<String, Snapshot>>`；
  跨天失效由快照时间判断。
- 纯函数/纯模型（`CrawlPlanner`、`CursorLogic`、`CrawlProgress.reduce`、`AssetName`、
  `ReloginPolicy` 等）保持 JVM 可测，是当前 312 个单测的主体。

## 4. 依赖注入（Hilt）

- `WcmApp` 标注 `@HiltAndroidApp`，并实现 `Configuration.Provider`；
  `HiltWorkerFactory` 通过 `@Inject` 注入。
- `CheckInWorker` 标注 `@HiltWorker` + `@AssistedInject`；
  manifest 关闭 WorkManager 默认初始化，避免自定义 WorkerFactory 被覆盖。
- 现阶段 Hilt 已覆盖 **Application / Worker / ViewModel / 抓取单闸门**：
  `CheckInWorker` 是 `@HiltWorker`，设置页 `SettingsViewModel` 是 `@HiltViewModel`，
  `CrawlService` 由 `DomainModule` 以 `@Singleton` 提供并被四个抓取入口注入
  （`MainActivity` / `HomeFragment` / `GalleryFragment` / `BrowsingActivity` /
  `PostPickActivity` 均标 `@AndroidEntryPoint`）。
  `CheckinService` / `AccountMaintenanceService` / `AutoRelogin` 仍是 `object`，
  后续再逐步改为构造注入的类（见“待办”）。
- 版本：Hilt `2.51.1`、KSP `1.9.24-1.0.20`（与 Kotlin `1.9.24` 对齐）。

## 5. 构建与门禁

```bash
# 本地（JDK17 必须显式指定，机器上没有全局 java）
JAVA_HOME=C:/Users/adsm/dev/jdk17 ./gradlew --offline \
  :app:testDebugUnitTest :app:assembleDebug

# release（R8 + 资源收缩 + 固定签名）
JAVA_HOME=C:/Users/adsm/dev/jdk17 ./gradlew --offline :app:assembleRelease
```

当前基线（2026-11-14）：

- 315 个 JVM 单测，0 failures
- `assembleDebug` / `assembleRelease` 通过
- release APK ≈ 3.56MB（R8 开启前 6.55MB；Compose 迁移中）
- `lint` 在本机 Windows 环境会长时间卡在 `lintAnalyzeDebug`；CI 的 `build-apk.yml`
  只跑单测 + assembleDebug，不依赖 lint

## 6. 迁移状态与待办

### 运行时验证（2026-11-14，Android 14 模拟器 `see-audit`）

- 冷启动、首页/超话/图库/设置四 Tab 渲染正常，无 `FATAL EXCEPTION`。
- 设置页 Compose：浅色/深色都正常；品牌玫红与 XML 页面一致；
  开关切换后 `shared_prefs/wcm.xml` 即时落盘（`auto_on_open=false/true`）；
  公网 `http://` 面板地址被拒绝并显示红色错误文案。
- 登录页打开微博官方登录 WebView；浏览博主页打开空态页。
- WorkManager 周期任务已注册；`cmd jobscheduler run -f -n androidx.work.systemjobscheduler
  com.wcm.checkin 1` 强制触发后，`CheckInWorker` 由 Hilt 实例化并返回 SUCCESS。
- 首页 Compose Hero：白卡视觉与 XML 一致，点击可跳转「超话」页。
- 首页账号列表：空态、假账号行（头像/UID/状态）、⋮ 菜单（查看超话/重登/删除）均正常。
- 首页 Cookie 卡：假账号失效态（红字/「已失效」pill）与面板未配置提示正常。
- 首页日志卡：收起摘要与展开终端窗口（深色/等宽/贴底）正常。
- 首页删除确认：Compose AlertDialog 正常（假账号，未实际删除）。
- 超话清单 Compose 对话框：编译通过；需要有效微博账号才能触发运行时验证。
- 重登凭证 Compose 对话框：状态/账号/密码/说明/保存/删除渲染正常（未实际保存凭证）。
- 未覆盖：真实签到/抓取（需要微博账号）、选图/预览（需要图片数据）、
  `PostPickActivity`/`ImagePreviewActivity`/`RemotePreviewActivity`。

已完成：

- `WeiboApi` 1114 行 → `WeiboHttp/Auth/Topic/Post/ImageUrl`
- `Crawler` 1681 行 → `CrawlEngine` + `CrawlSaver` + `AlbumCrawler`（428 行）+
  `TimelineCrawler`（307 行）+ `PostAssets`
- `AccountStore` 933 行 → `AccountStore`（643 行）+ `PanelSettings` + `CrawlSettings`
- 签到编排/保活/重登从 UI 与 Worker 收敛到 `:domain` 服务
- 4 模块 + Hilt + R8；所有改动行为保持，数据格式零迁移
- **抓取单闸门类化**：`CrawlRunner` object → `CrawlService`，由 Hilt `@Singleton` 提供
- **首页状态收口**：HomeFragment companion 的进程级 UI 状态迁入 Hilt `HomeViewModel`
- **超话快照 StateFlow**：`TopicState` 提升为顶层，`topicStates` 由 ViewModel 持有
- **Compose 试点**：设置页改为 ComposeView + `SettingsViewModel`，新增 `WcmTheme`
  （Material3，使用 `colors.xml` 品牌令牌，与 XML 页面一致；不使用动态取色）
- **首页 Hero Compose**：`HomeHeroCard` + `HomeHeroUiState`；`fragment_home` 的 Hero
  卡已换成 ComposeView，其余账号/Cookie/日志区块仍为 XML
- **首页账号列表 Compose**：`AccountList` 负责行/头像 tone/状态 chip/⋮ 菜单；
  空态与账号菜单也由 Compose 渲染
- **首页 Cookie 卡 Compose**：`CookieCard` 负责卡头 pill/提示/逐账号有效期/配置入口
- **首页日志卡 Compose**：`LogCard` 负责收起摘要/展开终端窗口/贴底跟随
- **首页全量 Compose**：Hero/账号列表/Cookie 卡/日志卡/账号菜单/删除确认/超话清单/
  重登凭证对话框均已 Compose；`HomeFragment` 只保留编排与状态构建
- ViewBinding：全部界面已替换（LogPanel 的通用 host 仍按 id 查找；Gallery 两个对话框输入框保留 dialogView 查找）

待办（按建议顺序）：

1. 超话/图库/浏览/预览 Compose 化；主题令牌继续对齐 `docs/design/ui-system-v2.0.md`。
2. 导航壳与登录页收尾；删除已迁移的旧 XML。
3. 可选：Room（签到历史/统计）、kotlinx.serialization（新 DTO）、
   `:feature` 模块（需先把 `R.*` 资源迁移到 feature）。

## 7. 不变量（改动时必须保留）

- 抓取单闸门：任何新增入口（定时、通知、快捷方式）都必须经 `CrawlService`。
- 三层去重与 `CrawlIndex` 的终态语义：不要绕过 `CrawlSaver` 直接写文件。
- 账号表并发：`AccountStore` 的读改写仍在 `LOCK` 内；设置/账号分离后，
  抓取设置由 `CrawlSettings.LOCK` 保护。
- 安全口径：出站 URL 只允许 https + 微博域名白名单；登录 WebView 白名单
  与出站白名单分开。
- 数据兼容：SharedPreferences 文件仍叫 `wcm`，索引仍是
  `filesDir/crawl/index/<treeTag>.ndjson`，keystore 与签名不变。
