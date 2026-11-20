# 测试策略与覆盖率

> 适用版本：`0.15.0`（versionCode 46）
> 本文回答三个问题：**现在测了什么**、**为什么长期测不到编排层**、**下一步补什么**。
> 结构与分层见 [`architecture.md`](architecture.md)，历史审查结论见
> [`review/code-audit-2026-11-20.md`](review/code-audit-2026-11-20.md)。

## 1. 一句话结论

**用例数量很漂亮，分布是失衡的。** 审查时 593 个用例几乎全压在纯函数上，而真正会写盘、
会发写请求、会改账号表的编排服务长期零测试——不是因为"不重要"，而是因为
**它们的依赖形态让 JVM 单测够不到**。2026-11-20 修复批次后 603 个用例，编排层热点
（`CheckinService` / `AutoRelogin` / `EngageService` 全链路）已按第 5 节思路补上，
抓取链路（`CrawlSaver` / 两个 Crawler）仍是第 5 节列出的下一个目标。

## 2. 什么是「测试结构失衡」

失衡不是指用例少，而是指**用例与风险错位**：

| 维度 | 有测试的部分 | 零测试的部分 |
| --- | --- | --- |
| 逻辑性质 | 纯函数：给定输入算输出（`CrawlPlanner`、`ReloginPolicy`、`EngageSchedule`、`AssetName`、`Hashing`…） | 编排：读配置 → 发请求 → 按结果记账 → 回写本地状态 |
| 出错后的代价 | 算错一个日期键、少排一张图 | 凭据写错、账号被误判死、被风控限流、日记账与真实状态不一致 |
| 出错后被发现的速度 | 单测立刻红 | 要等真机跑一轮，甚至要等用户反馈 |
| 过去的覆盖情况 | 36 个测试类 / 573 用例 | **7 个 domain 服务 + 6 个本地存储类，引用数 0** |

换句话说：**测试写在了"容易测"的地方，而不是"值得测"的地方**。这不是态度问题，
而是成本问题——`CheckinService` 依赖一个 final 的 `AccountMaintenanceService`，
JVM 里既 new 不出来也替换不掉，想测只能上 Robolectric 或真机，于是没人写。

## 3. 根因：依赖形态决定可测性

同一份代码库里有两种端口风格，可测性差了一个数量级：

```kotlin
// ✅ 可测：端口不碰 Context，实现可以随便假
class EngageService(
    private val configPort: EngageConfigPort,   // loadConfig() / saveConfig()
    private val runPort: EngageRunPort,         // reserveSlot(uid, day, ...)
    ...
)

// ❌ 不可测（0.15.0 之前）：依赖 final 具体类，且端口方法都带 Context
class CheckinService(
    private val maintenance: AccountMaintenanceService,  // final，三个构造依赖
)
```

`Context` 形参是硬约束：JVM 单测里 `android.jar` 的方法全是抛 `Stub!` 的桩，
拿不到一个真的 `Context`（除非引入 Robolectric 那套运行时）。

> 端口保留 `Context` 是刻意的（见 `Ports.kt` 的三条约定），它换来了「端口不持有
> Android 单例」。所以**不要为了可测性去改端口签名**，正确的做法是二选一：
> ① 给编排服务抽一个**不带 Context** 的窄端口；② 用 mockk 造一个 mock `Context`。

## 4. 现状（2026-11-20 修复批次后实测）

`./gradlew :app:jacocoTestReport` 的输出（`app/build/reports/jacoco/`）：

| 指标 | 数值 |
| --- | --- |
| 行覆盖 | **4,150 / 16,302 ≈ 25.5%**（审查时 22.5%；死代码清理后分母 16,315→16,302） |
| 分支覆盖 | 2,449 / 13,140 ≈ 18.7% |
| 方法覆盖 | 1,093 / 3,761 ≈ 29.1% |
| 单测 | **603 用例 / 39 类 / 0 失败 / 0 跳过** |

按类看，风险最高的几处（本轮变化在前）：

| 类 | 覆盖行 / 总行 | 说明 |
| --- | --- | --- |
| `EngageRunExecution`（新文件） | **335 / 367 ≈ 91.3%** | 拆分时新增：单条动作执行、熔断、预算截断、人工确认两段式，由 10 个特征测试覆盖 |
| `EngageService` | **245 / 316 ≈ 77.5%** | 605 行 `runInternal` 拆成 ~46 行编排器 + 五段准备/收尾；此前仅 71/574（前置拒绝分支） |
| `CheckinService` | 77 / 97 | 0.15.0 新增 7 个用例，覆盖判死短路、探活失败、全签成功、单条失败、**取消上抛** |
| `AutoRelogin` | 37 / 96 | 0.15.0 新增 9 个用例，覆盖总开关/账号开关/无凭证/ALC-only/退避/24h 上限/硬停/强制冷却 |
| `ReloginPolicy` | 77 / 81 ≈ 95.1% | 纯策略函数，8 参签名捆包成 `Facts`+`State`（5 参）后用例同步迁移 |
| `CrawlIndex` | 317 / 371 | 被 `CrawlPlanner`/`CrawlCursor` 的用例间接触达 |
| `AccountStore` | 110 / 393 | 只有纯序列化部分（无 Context 的那半边） |
| `CrawlSaver` | **0 / 168** | 落盘与三层去重，全部依赖 SAF + 真实字节流——**仍是最大单点** |
| `CrawlService` / `CrawlEngine` | **0 / 179** | 抓取闸门与总装 |
| `AlbumCrawler` / `TimelineCrawler` | **0 / 362** | 两条枚举链路；这三个类的 9~11 参函数在 detekt baseline 里，重构前先补测试 |
| `AccountMaintenanceService` | **0 / 65** | 探活落库（现由 `CheckinServiceTest` 通过 `ProbePort` 旁路） |
| 10 个 ViewModel | 基本 0 | 只有 `HomeViewModel` 的 1 个用例 |

## 5. 补测优先级（按 ROI）

1. **`CrawlSaver`**（未覆盖 168 行，是最大的单点）：把三层去重的判定抽成纯函数
   `shouldSave(picOwner, kind, hasCanonical, hashHit)`，纯函数一套用例即可覆盖
   「Live 的 mov 与其封面静图共用 picId」这类**已经修过一次的坑**。
2. **`CrawlService` 的单闸门**：它靠 companion 的 `GATE`/`STOP` 保证「全应用同时只有一个
   抓取」，多实例会同时写同一个 SAF 目录。这条不变式目前**没有任何自动化保护**，
   连 `@Singleton` 漏标都不会被发现。
3. **`AccountStore.migrateToSealed`**：一次性把明文 cookie 改写成密文的迁移，
   失败会连累整表。抽 `verifyMigration(before, after)` 纯函数即可测。
4. UI 层（10 个 Screen）**不建议**强上 Compose 测试：投入产出比远低于上面三条，
   真正的 UI 回归仍然靠 `docs/verification.md` 的真机清单。

## 6. 工具链

```bash
# 单测（本地必跑）
JAVA_HOME=$JDK17_HOME ./gradlew :app:testDebugUnitTest

# 覆盖率（含 :core/:data/:domain，报告在 app/build/reports/jacoco/html）
JAVA_HOME=$JDK17_HOME ./gradlew :app:jacocoTestReport

# detekt 静态检查（配置 core/config/detekt/detekt.yml，:app 的 Compose UI 暂不纳入）
JAVA_HOME=$JDK17_HOME ./gradlew detekt
```

- **detekt 只开三条规则**：`LongMethod(100)` / `LargeClass(650)` / `LongParameterList(≤8)`，
  其余规则集显式关闭（`buildUponDefaultConfig = true` 会把 complexity 默认开启的
  `CyclomaticComplexMethod` / `TooManyFunctions` 等一并带进来，必须逐条 `active: false`）。
  口径：`LongParameterList` 的语义是「参数数 ≥ 阈值触发」，阈值 9 = 允许 8 参；
  `ignoreDefaultParameters: true` 让带默认值的参数不计数。
- **存量违规圈进 baseline，不圈死新代码**：6 条抓取链路的 9~11 参函数
  （`AlbumCrawler`×2 / `CrawlSaver`×3 / `TimelineCrawler`×1）在
  `core/config/detekt/baseline.xml`，对应第 5 节「先补测试再重构」的区域；
  baseline 外出现新违规（函数再变长、类再变大、参数再变多）直接拦。
  重构完对应函数后记得从 baseline 里删掉条目，别让名单越攒越长。
- **detekt 已进 CI 门禁**：`build-apk.yml` 的 `Detekt static check` 步骤，
  违规明细转成 check 注解。

- **用 JaCoCo，不用 Kover**：Kover 0.8.x 的 JVM agent 会把 `-javaagent` 里的
  settings 路径按平台编码写进参数，仓库路径含中文（`E:\项目\...`）时被改写成
  `E:\???\...`，`testDebugUnitTest` 直接起不来。JaCoCo 只传 destfile，不受影响。
- **mockk 用于两类单测拿不到的东西**：一个 `Context` 实例、以及把 `WeiboXxx` 这类
  `object` 换成假响应（`mockkObject`）。它只出现在 `testImplementation`，不进 APK。
- 覆盖率**未接入 CI 门禁**：当前只是诊断工具。等第 5 节前三条落地、数字稳定后，
  再考虑给 `:domain` 加一条「不低于 X%」的硬门槛。

## 7. 写新测试时的约定

1. **假实现集中在 `app/src/test/.../FakePorts.kt`**：端口加方法时只有一处要跟着改，
   不会出现「两份假实现中的一份静默失效」。
2. **只测到网络之前**：`AutoRelogin` 的用例全部停在 `attemptLogin()` 之前，
   `CheckinService` 的用例用 `mockkObject(WeiboTopicApi)` 截断 HTTP。真发请求的
   路径属于端到端，写在 `docs/verification.md` 的真机清单里，不进单测。
3. **一个用例只钉一条不变式**，并在 KDoc 里写清「这条错了会怎样」——例如
   `cancellationDuringPause_propagatesAndWritesNoSummary` 对应的就是 0.15.0 修掉的
   「取消被记成失败小结」。
