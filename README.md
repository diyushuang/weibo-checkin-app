# 微博签到助手 · Android 客户端

「微博超话签到管理面板」的手机端（Kotlin / Android，包名 `com.wcm.checkin`）。
把登录态的「根」放到手机上：App 内用微博官方页面登录一次（账密不经本应用），
之后在真机 + 家宽/移动网络上直接签到，并把最新 Cookie 同步回面板。

> 本工程原为面板仓库的 `android-app/` 目录，已独立为单独项目，可脱离面板单独
> 构建与迭代。面板侧文档见同级目录 `微博签到/`。

## 功能

- **打开 App 即自动签到**：逐账号列超话 → 对未签的超话随机间隔 3-8 秒逐个签到
  （已签自动跳过，与面板互相幂等）
- **账号列表**：每个账号分三行显示「用户名 / UID / 状态」；登录时没取到昵称的会在下次刷新时自动补拉
- **超话面板（v0.9.0）**：底部「超话」页把接口能读到的超话信息集中展示 ——
  账号切换、关注总数 / 今日已签 / 待签三列概览、逐条的**等级徽章（四档配色）**、
  名称、签到状态，可按待签/已签/Lv7+ 筛选、按待签优先/等级/名称排序；
  点开某条才懒加载简介、关注数、帖子数与超话 ID（缓存 30 分钟），
  行菜单可单独签到 / 复制签到链接 / 查看该条原始 JSON；⋮ →「字段说明」列出全部可读字段
- **超话状态与补签**：**每次打开首页自动刷新**各账号的超话签到状态，也可点「刷新超话」手动重拉；
  点按账号行会列出该账号**关注的全部超话并逐条标注已签/待签**，勾选漏签的一键补签，
  弹窗里的「查看全部」直接跳到超话页；账号行额外显示该账号的最高超话等级，
  Hero 卡可点进超话页，底栏「超话」上挂着待签数角标
- **原图抓取**：添加博主（UID / 主页链接 / 个性域名）并选择相册目录后，一键增量
  抓取其全部原创微博原图（`/large/` 原始分辨率）；Live Photo 端内转 GIF
  （自研 GIF89a 编码器：中值切分 256 色调色板 + LZW + Bayer 抖动，12fps/长边 640，
  失败自动回退保存原始视频），按日期命名平铺，**游标驱动、断点续跑、三层去重**；
  Live 项只存动图，不再额外存一张重复的封面静图（关掉 GIF 转换、只留 mov 时才补封面）
- **抓取进度可视化（v0.9.0）**：运行卡显示阶段（补失败 / 补新 / 补历史）、
  已处理/计划、当前目标、已下载/跳过/待重试计数与进度条；「停止」对首页自动抓图同样生效；
  有待重试的帖子会出现「待重试 N 帖」入口，可查看清单并一键只跑重试阶段
- **目录授权自检（v0.9.0）**：进入页面即校验 SAF 目录的持久授权，被系统回收时
  在存储位置卡上以红色 pill 提示，点一下直接重选，不必等到抓取全失败才发现
- **图库浏览与全屏预览**：网格缩略图（采样解码 + LruCache），全屏双指捏合/双击缩放，
  GIF 端内播放（API 28+ ImageDecoder），支持分享 / 系统应用打开 / 删除
- **图库筛选与批处理（v0.9.0）**：按类型（静图/动图/视频）、作者、关键词搜索过滤，
  五种排序，可按日期或博主分组（跨行分组头显示每组的张数）；
  长按进入多选，批量分享与批量删除（删除同步回退抓取索引，下一轮可重新抓回）
- **单条抓取**：粘贴微博链接直接抓该条，或输入 UID 列出最近微博点选抓取
- **后台每 6 小时保活轮换**：WorkManager 静默探活收下换发的新 Cookie，
  跨重启持久化，无需开机自启权限
- **同步面板**：配置面板地址 + 同步令牌后，定期把 Cookie 推送到面板
  `POST /api/mobile/sync`，覆盖同微博号账号并重置生命周期基准

Material 3 界面（底部导航：首页 / 超话 / 图片 / 设置，Android 12+ 跟随系统取色）。

超话字段（列表 / 详情 / 签到响应各有哪些可用字段、怎么解析）见
[`docs/design/topic-fields.md`](docs/design/topic-fields.md)。

界面自 v2.0 起对齐面板端 UI 系统（`微博签到/docs/ui-redesign/tokens.css`）：
冷灰底 + 纯白微描边卡 + 玫红只占小面积，颜色 / 字体 / 间距 / 圆角全部令牌化；
四档宽度（手机 / 横屏 / 平板 / 桌面）各自覆写尺寸，边到边 + 状态栏 / 导航栏 / 挖孔 /
键盘 / 深色模式统一在 `MainActivity` 的 insets 入口处理。
规范、映射表与验收清单见 [`docs/design/ui-system-v2.0.md`](docs/design/ui-system-v2.0.md)，
三屏浅深色对照稿见 [`docs/design/ui-preview.html`](docs/design/ui-preview.html)。

**权限**：`INTERNET` + `ACCESS_LOCAL_NETWORK`（自建局域网面板用，Android 17 起才需要）。
WorkManager 会通过 manifest 合并额外注入 `WAKE_LOCK`/`ACCESS_NETWORK_STATE`/
`RECEIVE_BOOT_COMPLETED`/`FOREGROUND_SERVICE` 四项，用于跨重启的后台保活调度。
App 不申请存储、定位、通讯录、通知等任何其它权限。

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| AGP | 8.13.2 |
| Gradle | 8.13（见 `gradle/wrapper/gradle-wrapper.properties`） |
| compileSdk / targetSdk | 36（Android 16，满足 Play 2026-08-31 起的强制要求） |
| minSdk | 23（Android 6.0） |
| 其它 | 开启 core library desugaring（`java.time` 在 API < 26 上靠它提供） |

> 兼容范围：Android 6.0 – 16。API 37（Android 17）需 AGP 9.1+ / Gradle 9 / Kotlin 2.4，
> 属独立迁移；相关运行时行为（局域网权限、大屏可调整大小等）已提前适配。

## 构建

```bash
# Debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# 单元测试（纯函数：命名/索引/规划器/游标/接管/面板地址/签到接口等）
./gradlew testDebugUnitTest

# 静态检查（NewApi 已设为硬错误，低版本兼容一旦被破坏会直接构建失败）
./gradlew lintDebug

# 两者一起（与 CI 一致）
./gradlew testDebugUnitTest assembleDebug
```

Windows 用 `gradlew.bat`。也可以直接用 Android Studio 打开本目录（工程根即本目录，
`settings.gradle.kts` 在此）。

命令行构建需要 Android SDK：设置环境变量 `ANDROID_HOME`，或在根目录放一个
`local.properties`（已 gitignore）：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## APK 签名（覆盖安装升级的前提）

Android 只允许签名一致的 APK 覆盖安装，否则报「软件包与现有软件包存在冲突 /
开发者签名异常」。CI 每次都是全新 runner，默认 debug 密钥是随机生成的，因此
统一改用固定 keystore 签名：

1. 密钥已生成在 `app/keystore/wcm-release.p12`（PKCS12，别名 `wcmsign`，
   口令 [REDACTED]，整个目录已 gitignore）。**请备份到仓库之外**——丢失后
   新版本将无法再覆盖升级。
2. 仓库 → Settings → Secrets and variables → Actions → New repository secret：
   新建 `WCM_KEYSTORE_B64`，值为同目录 `wcm-release.p12.b64` 的内容（即
   keystore 的 base64）。配置后 CI 构建的 APK 全部同签名，可直接覆盖升级；
   未配置时仍会构建并给出 warning，但签名退回一次性随机 debug 密钥。
3. 首次启用前旧版 App 需**卸载一次**再装新 APK（签名不同无法覆盖；卸载会清掉
   App 本地保存的账号 Cookie，需重新登录），此后每次更新直接覆盖安装即可。
4. 本地构建：把 `wcm-release.p12` 放在 `app/keystore/` 下即与 CI 产物同签名。

若需换口令，设置环境变量 `WCM_KEYSTORE_PASSWORD`（口令 [REDACTED]）。

## 结构

```
微博签到app/
├── settings.gradle.kts      # rootProject.name = "wcm-checkin"，include(":app")
├── build.gradle.kts         # AGP 8.13.2 + Kotlin 1.9.24
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── .github/workflows/build-apk.yml   # 单测 + assembleDebug + 上传 APK 产物
└── app/                     # 唯一模块
    ├── build.gradle.kts     # namespace / applicationId / 签名 / 依赖
    ├── keystore/            # 固定签名 keystore（gitignore，需自行备份）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/wcm/checkin/
        │   │   ├── WcmApp.kt / MainActivity.kt            # 应用与主壳（4 Tab）
        │   │   ├── HomeFragment.kt / SettingsFragment.kt  # 首页签到 / 设置
        │   │   ├── TopicFragment.kt / TopicStore.kt       # 超话面板 / 超话快照缓存
        │   │   ├── GalleryFragment.kt / GalleryBrowseActivity.kt
        │   │   ├── ImagePreviewActivity.kt / ZoomableImageView.kt
        │   │   ├── LoginWebViewActivity.kt                # 微博官方页登录取 Cookie
        │   │   ├── CheckInWorker.kt                       # WorkManager 定时签到/保活
        │   │   ├── AccountStore.kt                        # 账号 / 配置 / 抓取游标持久化
        │   │   ├── WeiboApi.kt / Crawler.kt               # 超话签到 / 原图抓取
        │   │   ├── CrawlPlanner.kt / CrawlCursor.kt       # 增量规划（纯函数）/ 游标模型
        │   │   ├── CrawlIndex.kt / CrawlRunner.kt         # 去重索引（NDJSON）/ 抓取单闸门
        │   │   ├── CrawlProgress.kt / SafAccess.kt        # 进度模型（纯函数）/ 目录授权自检
        │   │   ├── AssetName.kt / Takeover.kt             # 命名规范化 / 相册已有文件接管
        │   │   ├── Hashing.kt                             # 内容哈希（SHA-256 前 128bit）
        │   │   ├── PanelApi.kt                            # 面板 /api/mobile/sync 同步
        │   │   ├── GalleryFiles.kt / ThumbLoader.kt       # 相册文件 / 筛选分组 / 缩略图
        │   │   ├── GifEncoder.kt / LivePhotoConverter.kt  # Live Photo → GIF
        │   │   └── ...
        │   └── res/           # layout / drawable / values / xml
        └── test/java/com/wcm/checkin/                     # JVM 单测
```

## 抓取机制（v0.5）

### 推进顺序

每轮对每个目标依次跑三个阶段，**不再每轮从第 1 页重扫**：

| 阶段 | 做什么 | 停下来的条件 |
| --- | --- | --- |
| RETRY | 只补上次失败的槽位，不翻页 | 队列清空 / 预算耗尽 |
| A · 补新 | 第 1 页起补「水线之上」的新帖 | 本页全部已处理 **且** 已回到水线 |
| B · 补历史 | 从断点锚点继续往更早补 | 空页 / 触及起始日期 / 预算耗尽 / 回到已覆盖区间 |

- 进度记在 **游标**（`[已覆盖区间] + 锚点(postId, createdAt) + 页码提示`）里，按
  **相册目录 + uid** 双键隔离：换目录天然从头开始，切回旧目录仍能原地续跑。
- 断点**不存页码**：新帖插入会让页码整体下移、删帖会上移，页码只当搜索起点；
  锚点失效（帖被删）时退化为按时间定位，再失败则从第 1 页重走，正确性不受影响。
- 每个目标可单独设「起始日期」：它是硬下界，早于该日期的微博不再抓。
- 单轮有预算上限（页数 / 详情数 / 文件数 / 25 分钟），跑不完下轮接着来，不算失败。

### 去重三层

| 层 | 判据 | 何时用 |
| --- | --- | --- |
| L1 文件名 | 归一 ` (1)` 撞名后缀后比对目录快照 | 每次下载前（零成本） |
| L2 业务身份 | `postId` 已终态则跳过整帖；`picId` 已有映射则跳过该图 | 拉详情前 / 下载前（省一次请求） |
| L3 内容 | SHA-256 前 128bit，命中则不重复写盘 | 下载后（撞车才比较） |

索引是 `filesDir/crawl/index/<treeTag>.ndjson`（追加式，last-wins，超 1MB 自动 compact）。
规模估算：6k 张 ≈1MB 索引、≈1.5MB 内存；30k 张 ≈5MB / ≈8MB；10 万张 ≈17MB / ≈25MB。
**不引入任何新依赖**，APK 体积不变。

### Live Photo 只存一份

一张 Live 配图只落**一个**文件：`prefix_01_live.gif`（转换成功）或 `prefix_01_live.mov`
（关闭转换 / 转换失败）。旧的 v0.4 会先存 `prefix_01.jpg` 再存 `_live.gif`，同一张图在相册里
出现两遍 —— 现在的规则是：**GIF 成功就不再存封面静图**；只有落到 mov 时（图库不显示视频）
才补一张 jpg 当封面，避免这张图等于没抓到。

> 旧版本已经产生的成对文件不会自动删除（删用户文件风险太高），需要时手动删掉那些
> 与 `_live.gif` 同名同序号的 `.jpg` 即可。

### 安全策略（v0.6.0）

| 面 | 策略 |
| --- | --- |
| 微博接口 / 图片 CDN | **只允许 https**，且 host 必须在 `weibo.cn / weibo.com / weibocdn.com / sinaimg.cn` 白名单内 |
| 面板地址 | 公网**必须 https**；只有私有网段（10/8、172.16/12、192.168/16、127/8、169.254/16、`.local`、`.lan`）才允许 http |
| 明文流量 | `network-security-config` 仍整体放行（面板地址构建期无法枚举），但应用层已把明文收敛到内网；换 https 反代后把 `cleartextTrafficPermitted` 改成 `false` 即可彻底关闭 |
| WebView | 只允许「https + 微博域名」导航，其余一律拦截；关闭 `file://`/`content://` 访问与混合内容 |
| 备份 | `allowBackup=false` + `dataExtractionRules` 全量排除，Cookie 不会被云备份带走 |
| 局域网 | Android 17+ 保存局域网面板时申请 `ACCESS_LOCAL_NETWORK`，低版本无此概念直接跳过 |

## 已知遗留

旧版的 `crawl_done` 记录的是「尝试过的槽位数」而非「成功落盘数」，迁移时无法反推真实缺口：
**旧版本里下载失败却被标成完成的图，升级后不会自动补回**。需要的话请把目标移除后重新添加、
并从想要的位置设一个起始日期重跑一遍；其余情况（文件还在、进度正常）都会被无缝接管。

## 与面板的关系

App 只通过 HTTP 与面板交互，不共享代码：

- `POST {面板}/api/mobile/sync` —— 推送 Cookie（需面板「设置 → 手机保活」生成的同步令牌）
- 面板侧实现见 `微博签到/app/api/mobile.py`

面板连接失败时 App 仍可独立完成签到，仅同步功能不可用。
