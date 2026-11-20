// 顶层构建脚本：只声明插件版本，不在这里应用。
// 各模块用 alias(libs.plugins.xxx) 应用，保证全项目插件版本唯一。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
