import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 游戏域内核：**有哪些游戏**（GameCatalog）+ 将来每款游戏的规则引擎（AzulEngine…）。
//
// 为什么单独一个模块而不是并进 :core：
//   :core 管"一行一条消息怎么收、怎么发"，已经稳定、几乎不再改；
//   这里管"这个游戏几个人玩、这步合不合法"，会随每加一款游戏长大。
//   两者变化速率差一个量级，混在一起等于每次改规则都把传输层拉进 diff。
//
// 而且它是**纯叶子**：不依赖任何模块（连 :core 都不依赖），所以拆出来零成本、不会造成循环。
// 依赖它的是 :app-net（服务端校验人数/玩法）和 :app（首页、设置面板）。
//
// 硬约束：这个模块不许依赖 Android、不许依赖协程、不许依赖序列化库——
// 一行 socket 也不许有，它只放"规则"和"目录"。
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
    testImplementation(libs.junit)
}
