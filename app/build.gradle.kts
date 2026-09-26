import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.yichao.evilgodxu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yichao.evilgodxu"
        minSdk = 33
        targetSdk = 37
        versionCode = 49
        versionName = "3.8.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../jh.keystore")
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProperties.getProperty("KEY_ALIAS", "jh")
            keyPassword = localProperties.getProperty("KEY_PASSWORD", "")
            // minSdk 33 下 v2 已覆盖全部目标设备；显式关闭 v3，否则签名器
            // 会因 minSdk ≥ 28 判定 v3 更优而省略 v2
            enableV2Signing = true
            enableV3Signing = false
        }
    }

    androidResources {
        // 禁用按语言分包，支持应用内语言切换
        localeFilters += listOf("zh", "en")
    }

    // 语言资源不分包，确保运行时语言切换可用
    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        release {
            optimization {
                // 同时开启代码优化与优化型资源缩减
                // 默认 Android keep 规则自动包含（includeDefault 默认 true）
                // 自定义保留规则由 src/<variant>/keepRules/*.keep 源集提供
                enable = true
            }
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                // Kotlin 模块元数据文件（每个 Kotlin 库都会生成，必然重复）
                "META-INF/*.kotlin_module",
                // Kotlin 协程调试探针
                "META-INF/DebugProbesKt.bin",
                "DebugProbesKt.bin",
                // 常见的重复许可证文件
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                // 版本控制索引文件
                "META-INF/INDEX.LIST",
                // 第三方库签名文件
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                // AndroidX 版本属性文件（各库独立包含，无需打包）
                "META-INF/*.version",
                "META-INF/androidx/**",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

// 启动配置：R8 依此重排 DEX 布局，把启动路径的类集中到首个 DEX。
// AGP 8.3 起默认开启，显式声明以免版本漂移改变行为。
baselineProfile {
    dexLayoutOptimization = true
}

// 构建产物统一命名为 EchoTideMusic-<versionName>-arm64.apk
val apkVersionName = android.defaultConfig.versionName ?: "0.0.0"

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("EchoTideMusic-$apkVersionName-arm64.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // 自适应窗口（WindowSizeClass 响应式布局）
    implementation(libs.androidx.window)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // 导航框架 Navigation3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // 键值存储 DataStore
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // 协程 Guava 桥接（MediaController buildAsync）
    implementation(libs.kotlinx.coroutines.guava)

    // Coil 图片加载
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Media3 ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // 在线音乐搜索网络请求
    implementation(libs.okhttp)

    // 从 :baselineprofile 模块收集基准配置与启动配置
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}