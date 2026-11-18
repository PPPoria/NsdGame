pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        google()
        mavenCentral()
    }
}

rootProject.name = "NsdGames"

// :core 纯 Kotlin（零 Android 依赖，可在 JVM 上秒级单测）
// :base Android 基础设施（UI 基类 / 扩展 / 存储 / 线程池）
// :app  壳工程（Manifest、主题、组装）
include(":core")
include(":base")
include(":app")

// ── 局域网通信底座（docs/ARCHITECTURE.md）─────────────────────────────────────
// 依赖方向严格单向，反向依赖会在编译期失败：
//   :app → :net-android → :net-core → :net-proto
//   :app → :game-*      → :game-api
//   :net-core → :game-api（会话层驱动插件，插件看不见传输）
//
// 模块是构建单元，归架构师；包是归属单元，按角色表 §3 分配（ADR-009）。
include(":game-api")      // 纯 Kotlin：GamePlugin 契约，零网络零 Android
include(":net-proto")     // 纯 Kotlin：帧格式 / Envelope / RoomDescriptor
include(":net-core")      // 纯 Kotlin：transport + session + room（★ 最值钱，全 JVM 可测）
include(":net-android")   // Android 库：NsdManager / 前台服务 / 权限
include(":game-gomoku")   // 纯 Kotlin：第一个游戏插件（五子棋）
