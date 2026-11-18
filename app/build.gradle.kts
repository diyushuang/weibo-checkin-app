import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// 稳定签名 keystore（PKCS12，别名 wcmsign）：本地放 app/keystore/（已 gitignore），
// CI 由 Secret WCM_KEYSTORE_B64 解出到同一路径。Android 只允许同签名 APK 覆盖安装，
// 缺失该文件时回退默认 debug 密钥，覆盖旧版会报「签名冲突」。
// 口令同样不进仓库：环境变量 WCM_KEYSTORE_PASSWORD（CI）优先，其次 local.properties
// 的 wcm.keystore.password（本地，已 gitignore）。keystore 存在但两处都没配时直接
// 构建失败，避免静默错签。
val wcmKeystore = file("keystore/wcm-release.p12").takeIf { it.exists() }
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val wcmStorePassword: String? = System.getenv("WCM_KEYSTORE_PASSWORD")
    ?: localProps.getProperty("wcm.keystore.password")
    ?: if (wcmKeystore != null) {
        throw GradleException(
            "检测到 app/keystore/wcm-release.p12 但未配置签名口令：请在 local.properties 写 " +
                "wcm.keystore.password=<口令>，或设置环境变量 WCM_KEYSTORE_PASSWORD"
        )
    } else null

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
        // 版本演进（每个版本的完整条目见 CHANGELOG.md，这里只留现行口径）：
        // · 0.9.x  —— 功能铺开：超话面板、游客态相册抓取、浏览/选图、全量扫描、
        //             账密自动重登、Cookie 有效期口径。目标一直是「不用户重新登录」。
        // · 0.10.x —— 工程重构：4 模块 + Hilt + 全量 Compose + R8。**无新增功能、
        //             数据格式零迁移**，可直接覆盖安装 0.9.18（版本号跳过 0.9.19 是
        //             为了区分「行为等价但结构已变」这条线）。
        // · 0.11.x —— 持久登录加固：cookie/ALC 加密落盘、登录墙判死、ALC 免密码重签。
        // · 0.12.x —— 超话互动任务：发帖/评论/转发/点赞，含写前预占与三态收敛。
        // · 0.13.0 —— 互动任务可用性：执行节奏可调（默认从 6 分钟压到 2 分钟）、
        //             动作三态（关闭/仅手动/自动）、单项执行、执行过程可视化。
        // 发版时这里只改 versionCode/versionName 两个数字，历史不要往这里堆。
        versionCode = 43
        versionName = "0.13.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // R8 + 资源收缩自 0.10.0 起开启并稳定出包（APK 6.55MB → 3.63MB）。
            // 当前依赖（OkHttp / WorkManager / AndroidX / Hilt / Compose）均自带 consumer rules，
            // 项目无反射、无 getIdentifier、无动态资源查找；只额外钉了 Worker 类名，
            // 原因见 proguard-rules.pro。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("wcm")?.let { signingConfig = it }
        }
    }
    buildFeatures {
        // ViewBinding 只剩登录页在用（LoginWebViewActivity ← activity_login.xml）；
        // 其余页面全部 Compose，组件树里的 View 由 ComposeView 托管。
        viewBinding = true
        buildConfig = true
        compose = true
    }
    composeOptions {
        // Kotlin 1.9.24 对应的最后一个独立 Compose 编译器版本
        kotlinCompilerExtensionVersion = "1.5.14"
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
    testOptions {
        unitTests {
            // 本地单测跑在 JVM 上，android.jar 里的方法（Log、TextUtils…）都是抛
            // RuntimeException("not mocked") 的桩。:domain 的 EngageService 会用
            // android.util.Log 打链路日志，不开这个开关单测直接炸。
            isReturnDefaultValues = true
        }
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
        // 真正触发提示的是这条：只在上面关掉 NewerVersionAvailable 会漏掉它。
        disable += "GradleDependency"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":domain"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.15.0")
    // 登录页仍是 XML + ViewBinding + AppCompatActivity（Compose 之外唯一的页面），
    // appcompat 只为它保留。material 还被 WcmApp 的 DynamicColors 用到。
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    // 下面三个不是"源码用到"，而是**版本钉**：material 1.12.0 与 appcompat 的 POM 里
    // 声明的是 recyclerview 1.0.0 / viewpager2 1.0.0 / fragment 1.5.4（2018–2022 年的版本）。
    // 删掉这三行，它们仍会以传递依赖进 APK，只是版本退回老版 —— 所以这里显式声明，
    // 把它们抬到与其余 AndroidX 对齐的版本。**不要因为"搜不到引用"就删掉它们**。
    // 源码里确实没有直接的 RecyclerView / ViewPager2 / Fragment 引用：
    // 全屏翻页是自研的 ZoomPager + ZoomableImageView（见 PreviewUi.kt），
    // 页面是 Compose route，四个 Tab 也不再走 Fragment。
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    testImplementation("junit:junit:4.13.2")
    // 本地单测跑在 JVM，android.jar 里的 org.json 是抛 Stub! 的桩，换真实实现
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
