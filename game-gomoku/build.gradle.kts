import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ─────────────────────────────────────────────────────────────────────────────
// :game-gomoku —— 第一个游戏插件：五子棋。公开信息博弈，天然适配事件流模型。
//
// 包归属（ADR-009）：
//   com.poria.game.gomoku → 数据流开发
//
// 硬边界（R2/R4 机器可查）：
//   - 不允许 import java.net / javax.net / android.* / com.poria.net.*
//   - 不允许 import kotlin.random / Math.random / System.currentTimeMillis
//     （随机只能来自注入的 SeededRandom，且结果必须写进 Event）
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
    // GamePlugin 实现类出现在本模块的公开 API 上 → api。
    api(project(":game-api"))

    // JSON 只用于序列化实现，不是公开签名类型 → implementation。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
