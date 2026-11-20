plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // 结构复杂度门禁：只跑三条规则，配置共享见 core/config/detekt/detekt.yml
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    // 三条结构规则全仓共享一份配置，口径一致才谈得上门槛
    config.setFrom(files("$rootDir/core/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    // 存量违规圈进 baseline（抓取链路的 6 个 9~11 参函数：AlbumCrawler×2 /
    // CrawlSaver×3 / TimelineCrawler×1，都在 :domain，对应 docs/testing.md §5
    // 「先补测试再重构」的区域）；新违规照样拦。重构完记得重新生成并收敛 baseline。
    baseline = file("$rootDir/core/config/detekt/baseline.xml")
}

android {
    namespace = "com.wcm.checkin.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // NewApi 是硬错误：用了高于 minSdk 的 API 一律构建期拦下。
        error += "NewApi"
        // 「有更新版本可用」不是代码缺陷，且不能顺手升级（升级要联网拉包，
        // 本地与 CI 都是离线构建）。三条规则都要关：真正触发提示的是
        // GradleDependency，只关 NewerVersionAvailable 会漏掉它。
        disable += "NewerVersionAvailable"
        disable += "AndroidGradlePluginVersion"
        disable += "GradleDependency"
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.15.0")
    // 与 app 对齐 lifecycle 传递版本，避免 data 单独解析到 2.6.2（本地缓存只有 2.8.7）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
