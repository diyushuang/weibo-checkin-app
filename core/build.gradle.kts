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
        error += "NewApi"
        disable += "NewerVersionAvailable"
        disable += "AndroidGradlePluginVersion"
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
}
