import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.poria.base"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 只开 viewBinding。dataBinding 没用上就别开 —— 每个模块都会多跑一遍 dataBindingGenBaseClasses。
    // buildConfig 需要，BaseApp 里靠 BuildConfig.DEBUG 决定是否开 StrictMode。
    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    // 纯 Kotlin 工具（时间/颜色/哈希），用 api 是为了让 :app 也能直接用
    api(project(":core"))

    // 下面这些类型都出现在 base 的 public API 里（BaseActivity 继承 AppCompatActivity、
    // BaseFragment 继承 Fragment、hook 签名里有 ViewBinding 等），所以必须用 api 而不是 implementation，
    // 否则消费方在写子类时会看不到这些类型。
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    // activity-ktx：提供 `by viewModels()` 等扩展；viewmodel-ktx：提供 `viewModelScope`
    api(libs.androidx.activity.ktx)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.fragment.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.kotlinx.coroutines.android)
    // Material3 主题由 base 提供（Theme.Base），drawable 里也引用了 ?attr/colorSurface
    api(libs.material)

    // 实现细节，不对外暴露类型
    // 注意：这里没有 kotlinx-serialization —— base 不做存档/序列化；
    // 需要序列化的是 :app-net 的房间协议（它自己声明插件和依赖），base 不背这个包。
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
