import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // 房间协议用 @Serializable 数据类：快照/命令整份 JSON，不手写拼装与转义
    alias(libs.plugins.kotlin.serialization)
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
    // api：FoundHost 的 host/port 要直接喂给 core 的 Tcp.connect，下游不用再单独声明 :core
    api(project(":core"))

    // 游戏目录/规则（GameCatalog）：大厅要用它校验"这个游戏几个人玩、有哪些玩法开关"
    implementation(project(":game-core"))

    // ServiceDiscovery 用 channel + 挂起 for 循环把"一次只解析一个"交给结构化并发，
    // 省掉 pending 队列 + resolving 标志位；RoomSession 也用它做后台建房间/连接。
    // 注意：只有这一个模块用协程，:core 的 socket 层刻意保持零依赖（阻塞写，天然背压）。
    implementation(libs.kotlinx.coroutines.android)

    // 房间协议（room/）的 JSON 编解码：快照是一份 @Serializable 数据类，
    // 手写"键=值 + 转义"的方案已经删掉（见 docs/room-reference.md 第 3 节）。
    implementation(libs.kotlinx.serialization.json)

    // Room / LobbyProtocol 不碰 Android，用普通 JUnit 在 JVM 上直接测
    testImplementation(libs.junit)
}
