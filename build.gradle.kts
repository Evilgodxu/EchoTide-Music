plugins {
    alias(libs.plugins.android.application) apply false
    // 与 application 同属 AGP，须一并声明版本
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
}