plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wcm.checkin.core"
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 微博客户端（WeiboHttp / Weibo*Api）随模型一起沉到 core：
    // 它们是「无状态、不碰 prefs 的纯网络与解析」，属于共享内核，
    // 让 :domain 只依赖 :core 就能发请求，不必为每个接口再造一层端口。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // GuestSession / LivePhotoConverter 用了 core-ktx 的 edit{} 与 scale()
    implementation("androidx.core:core-ktx:1.15.0")
    // core-ktx 会传递 lifecycle-runtime 2.6.2，本地缓存只有 2.8.7（与其他模块对齐）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // 同理：core-ktx 传递 profileinstaller 1.3.1，与 data/domain 的 1.4.0 对齐
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
}
