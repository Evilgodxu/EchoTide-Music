plugins {
    // 插桩测试模块：只承载配置生成器，不参与应用打包
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.yichao.evilgodxu.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // 基准配置生成需要 API 28 及以上；本应用 minSdk 更高，直接对齐
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // 生成配置时以应用模块的 release 变体为被测对象
    targetProjectPath = ":app"

    buildTypes {
        // 测试模块自身的变体：行为对齐应用的 release，仅签名换成调试密钥
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

// 插件默认行为即为所需：生成结果写回 src/<variant>/generated/baselineProfiles，
// 并使用已连接设备（需 API 33 及以上）执行生成器。未配置托管设备，
// 因此不声明 baselineProfile{} 块，避免依赖尚未稳定的 DSL 字段。
dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.junit)
}
