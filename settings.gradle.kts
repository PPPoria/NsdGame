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

// 五个模块，就五个。加模块之前先问自己：拆开能少写多少代码？
//   :core      纯 Kotlin 内核：一行一条消息怎么收怎么发（socket / 行协议）。
//              零 Android 依赖，JUnit 秒跑。**这里不放任何游戏规则**。
//   :game-core 游戏域内核：有哪些游戏（GameCatalog）+ 将来每款游戏的规则引擎。
//              **纯叶子模块**（谁的依赖都不加），所以拆出来零成本：
//              :core 稳定、几乎不改；这里随每加一款游戏长大，变化速率差一个量级。
//   :base      Android 基础设施（Activity 基类 / 常用扩展 / 键值存储 / 线程池）
//   :app-net   局域网层：发现（NSD/mDNS）+ 链路 + 各频道。必须单独一个模块：
//              NsdManager 是 Android API，放进 :core 就把"JVM 能直接跑"毁了
//   :app       唯一的应用模块：ui / ViewModel 等界面与业务胶水都在这里
//
// 依赖方向：:app → :base → :core，:app → :app-net → :core，
//           :app → :app-net/:game-core，:app-net → :game-core；不允许反向。
include(":core")
include(":game-core")
include(":base")
include(":app-net")
include(":app")
