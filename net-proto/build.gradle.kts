import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ─────────────────────────────────────────────────────────────────────────────
// :net-proto —— 帧格式与消息外壳。纯 JVM，可在电脑上秒级单测。
//
// 包归属（ADR-009）：
//   com.poria.net.proto.frame    → 传输与消息协议（帧头、帧编解码）
//   com.poria.net.proto          → 传输与消息协议（Envelope / MsgType）
//   com.poria.net.proto.session  → 传输与消息协议（HELLO/WELCOME/JOIN*/PING/BYE 载荷）
//   com.poria.net.proto.game     → 游戏数据流/协议设计师（CMD/EVENT_BATCH/SNAPSHOT/SYNC_HASH 载荷 schema）
//   com.poria.net.proto.room     → 架构师（RoomDescriptor / HostAddress，跨层值对象）
//
// 硬边界：本模块不依赖 :net-core / :game-api（proto 是它们的地基，不能反向）。
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
    // JsonElement 出现在 Envelope.payload 的公开签名上，必须 api。
    api(libs.kotlinx.serialization.json)
    // FrameCodec.readFrame 是 suspend，必须 api。
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
