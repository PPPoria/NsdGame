import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // 游戏状态/规则里的快照要能直接上线（@Serializable），编译器得生成 serializer
    alias(libs.plugins.kotlin.serialization)
}

// 游戏域内核：**有哪些游戏**（GameCatalog）+ 将来每款游戏的规则引擎与对局状态（AzulEngine / AzulState）。
//
// 为什么单独一个模块而不是并进 :core：
//   :core 管"一行一条消息怎么收、怎么发"，已经稳定、几乎不再改；
//   这里管"这个游戏几个人玩、这步合不合法、局面长什么样"，会随每加一款游戏长大。
//   两者变化速率差一个量级，混在一起等于每次改规则都把传输层拉进 diff。
//
// 而且它是**纯叶子**：不依赖任何模块（连 :core 都不依赖），所以拆出来零成本、不会造成循环。
// 依赖它的是 :app-net（服务端校验人数/玩法）和 :app（首页、设置面板）。
//
// 硬约束：
// - 不许依赖 Android、不许有 socket —— 这里只放"规则"和"目录"；
// - 协程只能用 `kotlinx-coroutines-core`（**纯 Kotlin 库，不是 Android**；
//   `-android` 那个 artifact 才带 `Dispatchers.Main`，这里拿不到，也不该拿：
//   规则引擎要保持同步、可重放，别在里面 `launch` 后台活）；
// - 序列化只引 `kotlinx-serialization-core`（打注解用），**不引 json**：真正的编码统一走
//   `:app-net` 的 `LinkCodec.json`。自己 new 一个 `Json {}` 会让两边配置不一致，
//   对端解不出来——那是很难查的 bug。
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
    // api：引擎会把 StateFlow/Flow 放在公开签名上（比如 `val state`），下游要能看见这些类型
    api(libs.kotlinx.coroutines.core)
    // 只打 @Serializable 注解，不暴露给下游（编码统一在 :app-net 的 Json 里做）
    implementation(libs.kotlinx.serialization.core)
    testImplementation(libs.junit)
}
