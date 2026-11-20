import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// 局域网发现层：只做一件事——用 NSD（mDNS）把"我开了个牌局 / 局域网里有谁开了牌局"告诉别人。
//
// 为什么单独一个模块而不是塞进 :core：NsdManager 是 android.net.nsd 的 API，
// 塞进 :core 会让这个"零 Android 依赖、JVM 秒跑单测"的模块变成 Android 模块。
// socket 通信仍然在 :core（纯 JVM，可测），这里只负责"怎么找到对方"。
android {
    namespace = "com.poria.netnsd"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    // 没有资源、没有运行时依赖，所以不开 viewBinding / buildConfig
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
    // api：FoundHost 的 host/port 要直接喂给 core 的 TcpClient，下游不用再单独声明 :core
    api(project(":core"))
}
