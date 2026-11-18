import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// ─────────────────────────────────────────────────────────────────────────────
// :net-core —— 传输 + 会话。整个项目最值钱的模块：不含一行 Android 代码，
// 因此会话状态机、心跳超时、断线重连、事件重放全部能在 JVM 上单测。
//
// 包归属（ADR-009；本模块是唯一的多归属模块，按包划分）：
//   com.poria.net.core.transport → 传输与消息协议（Transport/TcpTransport/FrameCodec 装配/FakeTransport）
//   com.poria.net.core.session   → 游戏数据流/协议设计师（RoomSession/HostSession/GuestSession/SessionState/EventLog）
//   com.poria.net.core.room      → 服务发现与连接管理（RoomDiscovery/RoomAdvertiser 接口 + RoomCode）
//
// 硬边界：不允许出现 android.*；UI 层不允许 import 本模块的 transport 包（R3 规则）。
// ─────────────────────────────────────────────────────────────────────────────

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Envelope / RoomDescriptor 出现在会话层公开签名上 → api，让 :app 不必再单独依赖 :net-proto。
    api(project(":net-proto"))
    // GamePlugin 出现在 HostSession / GuestSession 构造签名上 → api。
    api(project(":game-api"))
    api(libs.kotlinx.coroutines.core)

    // 快照序列化用 Json，但不是公开签名类型 → implementation。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
