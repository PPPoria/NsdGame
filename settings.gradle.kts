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
