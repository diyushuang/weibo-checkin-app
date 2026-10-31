plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 稳定签名 keystore（PKCS12，别名 wcmsign）：本地放 app/keystore/（已 gitignore），
// CI 由 Secret WCM_KEYSTORE_B64 解出到同一路径。Android 只允许同签名 APK 覆盖安装，
// 缺失该文件时回退默认 debug 密钥，覆盖旧版会报「签名冲突」。
val wcmKeystore = file("keystore/wcm-release.p12").takeIf { it.exists() }
val wcmStorePassword = System.getenv("WCM_KEYSTORE_PASSWORD")

android {
    namespace = "com.wcm.checkin"

    // 36 = Android 16。Google Play 自 2026-08-31 起要求新应用/更新 target ≥ 36。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wcm.checkin"
        // 23 = Android 6.0：向下多覆盖 6.0/7.x。java.time 由 core library desugaring 兜底，
        // java.nio.file 等 26+ API 已在代码里换成低版本等价实现。
        minSdk = 23
        targetSdk = 36
        // 0.9.1：修「启动即闪退」（按钮样式缺 layout_height 导致整页 inflate 失败）、
        // 进程重建/后台续跑的崩溃路径、prefs 类型不符导致的启动崩溃；lint 警告清零。
        // 0.9.2：修「登录页一直停在『已检测到 Cookie，等待微博确认登录态』」——
        // 导航白名单补新浪 SSO 域（login.sina.com.cn 是登录态写回 .weibo.cn 的必经一跳）、
        // data.login 放宽布尔/数字/字符串、两套 cookie 合并口径都试、失败主动补 m 站会话、
        // 失败原因写进状态行并支持点按重试。
        // 0.9.3：换登录入口 —— 主站通道 wapsso 的登录态只写 `.weibo.com`，回到 m 站会被
        // visitor 系统发访客 SUB（表现为「登录成功却跳到游客首页、判定未登录」）；
        // 改用 m 站官方入口 passport.weibo.cn/signin/login?r=…（→ source=wapssowb）。
        // 0.9.4：登录白名单补 `.cn` 护照域 —— m 站通道的跨域分发要经过 passport.sina.cn，
        // 只放行 sina.com.cn 会卡在「已拦截跳转：passport.sina.cn」。
        // 0.9.5：修「点立即签到毫无反应」—— 运行中把主按钮置灰后点击事件不再派发，
        // 打开即自动签到的场景下用户点了整轮都静默；改为按钮常可点 + 文案变「正在签到…」，
        // 并用活跃 Job 校正残留的 busy 闸门。
        // 0.9.6：超话状态链路对齐面板 —— 请求补 MWeibo-Pwa（决定卡片/按钮语义）、
        // 签到响应认「已签到」文案、验签 errno=100015 换新 st 重试、快照跨天失效
        // （昨天的「已签」不能当今天的结论）、全部账号视图按「账号+超话」去重。
        // 0.9.7：按《免登录端口审计》修三处（详见 docs/research 与 CHANGELOG）。
        // 0.9.8：相册抓图模式（游客身份枚举相册墙）+ 18 图补全。
        // 0.9.9：全项目审查修复 —— 游客身份改两步握手、置顶帖不再污染覆盖边界、
        // 账号表加锁、长任务移出 View 作用域、图库重活移出主线程、签到与面板同步解耦、
        // 超话状态只在打开 App 时刷新一次。完整清单见 CHANGELOG.md。
        // 0.9.10：修「昵称一直显示（未获取到昵称）」（资料解析对齐面板端 + Web 端
        // 稳定链路）；「仅同步面板」升级为并列的「Cookie 同步」按钮 + 有效期面板
        // （同步 +7 天估算，临期/过期整行标红）。完整清单见 CHANGELOG.md。
        versionCode = 24
        versionName = "0.9.10"
    }

    signingConfigs {
        if (wcmKeystore != null) {
            create("wcm") {
                storeFile = wcmKeystore
                storeType = "PKCS12"
                storePassword = wcmStorePassword
                keyAlias = "wcmsign"
                keyPassword = wcmStorePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("wcm")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("wcm")?.let { signingConfig = it }
        }
    }
    compileOptions {
        // java.time（LocalDate/Instant/ZoneOffset）在 API < 26 上由 desugar 提供
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // NewApi 是硬错误：一旦有人用了高于 minSdk 的 API，构建期就拦下，
        // 避免低版本设备运行时 NoSuchMethodError。
        error += "NewApi"

        // 「有更新版本可用」类提示不是代码缺陷，且**不能**顺手升级：
        //  - okhttp 4.12 → 5.x 是破坏性主版本（拦截器/响应 API 都变了），
        //    属于需要单独评估 + 回归的迁移，不是改个版本号的事；
        //  - coroutines / Gradle 升级要联网拉包，CI 与本地都是离线构建。
        // 这几条留在这里只会长期制造噪音，给它们一个明确的处置结论：
        disable += "NewerVersionAvailable"
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
    // 本地单测跑在 JVM，android.jar 里的 org.json 是抛 Stub! 的桩，换真实实现
    testImplementation("org.json:json:20240303")
}
