plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wcm.checkin.domain"
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
    implementation(project(":core"))
    // 注意：这里**不能**再出现 project(":data")。
    // domain 只认 :core 里的模型与端口（AccountPort / CrawlSettingsPort / ...），
    // 实现由 :data 提供、在 :app 的 DomainModule 里注入。
    // 一旦有人把这条依赖加回来，依赖倒置就等于没做 —— CI 里有一道 grep 门禁盯着。
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
