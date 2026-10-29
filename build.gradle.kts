plugins {
    // AGP 8.13 是官方支持矩阵里最高能编译到 API 36.1 的 8.x 版本。
    // 再往上（API 37 / Android 17）需要 AGP 9.1+ + Gradle 9 + Kotlin 2.4，属独立迁移。
    // 当前：compileSdk/targetSdk 36 —— 满足 Google Play 自 2026-08-31 起的强制要求。
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
