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

// 四个模块，就四个。加模块之前先问自己：拆开能少写多少代码？
//   :core     纯 Kotlin 内核（UNO 规则 + AI + socket 通信），零 Android 依赖，JUnit 秒跑
//   :base     Android 基础设施（Activity 基类 / 常用扩展 / 键值存储 / 线程池）
//   :app-net  局域网发现（NSD/mDNS）。必须单独一个模块：NsdManager 是 Android API，
//             放进 :core 就把"JVM 能直接跑"毁了
//   :app      唯一的应用模块：ui / session 等界面与业务胶水都在这里
//
// 依赖方向：:app → :base → :core，:app → :app-net → :core，不允许反向。
include(":core")
include(":base")
include(":app-net")
include(":app")
