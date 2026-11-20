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

// 三个模块，就三个。加模块之前先问自己：拆开能少写多少代码？
//   :core  纯 Kotlin 游戏内核（规则 + AI），零 Android 依赖，JUnit 秒跑
//   :base  Android 基础设施（Activity 基类 / 常用扩展 / 键值存储 / 线程池）
//   :app   唯一的应用模块：ui / session / net 三个包都在这里
//
// 依赖方向：:app → :base → :core，不允许反向。
include(":core")
include(":base")
include(":app")
