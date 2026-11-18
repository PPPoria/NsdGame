import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// ─────────────────────────────────────────────────────────────────────────────
// :net-android —— 唯一接触 Android 平台 API 的网络模块：NSD、前台服务、权限。
//
// 包归属（ADR-009）：
//   com.poria.net.android.nsd     → 服务发现与连接管理（NsdAdvertiserImpl / NsdDiscoveryImpl / NsdTypes）
//   com.poria.net.android.service → 服务发现与连接管理（RoomForegroundService / RoomNotification）
//   com.poria.net.android.perm    → 服务发现与连接管理（NetPermissions）
//
// 注意：NsdManager 没有可信的 Robolectric shadow，因此本模块的调用面必须收成一个
// 3 方法 interface（register / unregister / discover+stop），业务逻辑（TXT 组装、
// 串行解析队列、状态合并）全部放在可单测的纯类里。详见 ARCHITECTURE §7 风险 1。
// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "com.poria.net.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // :app 通过本模块拿到会话层，所以 net-core 用 api 传递。
    api(project(":net-core"))
    // ServiceCompat 等平台兼容封装会出现在实现里；core-ktx 亦供 insets/权限辅助使用。
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
