import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ─────────────────────────────────────────────────────────────────────────────
// :game-api —— 游戏插件契约。零 Android、零网络、零 IO。
//
// 包归属（docs/ROLES_AND_COLLABORATION.md §3 模块归属 vs 包归属 / ADR-009）：
//   com.poria.game.api        → 游戏数据流/协议设计师
//   com.poria.game.api.model  → 游戏数据流/协议设计师
//
// 硬边界（机器可查）：本模块的源码里不允许出现 java.net / android.* / com.poria.net.*
// 依赖方向：只能被 :net-core 和 :game-* 依赖；本模块不依赖任何其它业务模块。
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
    // KSerializer 直接出现在 GamePlugin 的公开签名上（stateSerializer），必须 api。
    api(libs.kotlinx.serialization.json)
    // suspend / Flow 出现在契约签名上，必须 api。
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
