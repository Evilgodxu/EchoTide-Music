plugins {
    // 仅承载配置生成器，不参与打包
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.yichao.evilgodxu.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // 基准配置生成需 API 28+，对齐应用 minSdk
        minSdk = 34
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // 以 app 的 release 变体为被测对象
    targetProjectPath = ":app"

    buildTypes {
        // 行为对齐 release，仅换调试签名
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

// 用已连接设备（API 34+）生成，故省略托管设备配置
dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.junit)
}
