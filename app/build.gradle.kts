import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ppporia.nsdgames"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ppporia.nsdgames"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 走 base 就能拿到 appcompat / material / activity / fragment / lifecycle / coroutines / core
    implementation(project(":base"))
    // 显式声明：app 直接用 GameCatalog（游戏目录）
    implementation(project(":game-core"))
    // 联网的房间发现：ServiceDiscovery / ServiceAdvertiser（顺带把 TcpServer / TcpClient 带进来）
    implementation(project(":app-net"))
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // 两个页面都用 RecyclerView（房间列表 / 聊天气泡）。虽然 Material 会传递依赖它，
    // 但代码里直接用的类必须显式声明，不然哪天 Material 改依赖就莫名编译不过。
    implementation(libs.androidx.recyclerview)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
