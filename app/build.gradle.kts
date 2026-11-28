import java.io.FileInputStream
import java.util.Properties
import com.android.build.api.variant.HasUnitTestBuilder

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
    // Compose 1.13 需 37.1+，取 37.2
    compileSdk = 37
    compileSdkMinor = 2
    // 自写的 usbfs 等时层经 NDK 构建
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.yichao.evilgodxu"
        minSdk = 34
        targetSdk = 37
        versionCode = 126
        versionName = "6.4.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 仅打包 arm64
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // 静态链接 C++ 运行时，不额外分发库
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../jh.keystore")
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProperties.getProperty("KEY_ALIAS", "jh")
            keyPassword = localProperties.getProperty("KEY_PASSWORD", "")
            // 仅用 v2 签名：开 v3 会让签名器省略 v2
            enableV2Signing = true
            enableV3Signing = false
        }
    }

    androidResources {
        // 禁用按语言分包，支持应用内语言切换
        localeFilters += listOf("zh", "en")
        // AGP 生成 locale_config，避免手写漂移
        generateLocaleConfig = true
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
                // 优化与资源缩减；额外保留规则在 keepRules
                enable = true
            }
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 调试变体可调试；基线配置以 release 为被测对象
            isDebuggable = true
        }
    }

    packaging {
        jniLibs {
            // 原生库不压缩，可 mmap 加载
            useLegacyPackaging = false
        }
        dex {
            // 定向压缩 DEX，减小分发体积
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                // Kotlin 模块元数据，各库必重复
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
                // AndroidX 版本属性，无需打包
                "META-INF/*.version",
                "META-INF/androidx/**",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests {
            // Preview 需要编译后的资源与主题
            isIncludeAndroidResources = true
            // android.util 桩返回默认值，日志分支可测
            isReturnDefaultValues = true
        }
    }
    buildFeatures {
        compose = true
    }

    lint {
        // 仅打包 arm64，ChromeOS 非目标设备，仅关闭此项
        disable += "ChromeOsAbiSupport"
        // 定向抑制写在 lint.xml，不整体关闭检查
        lintConfig = file("lint.xml")
        // Lint Error 阻断构建，由 check 执行
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        // 警告视为错误，弃用写法须当轮清理
        allWarningsAsErrors.set(true)
    }
}

// 启动配置：R8 重排 DEX，显式声明防行为漂移
baselineProfile {
    dexLayoutOptimization = true
}

// 统一产物命名
val apkVersionName = android.defaultConfig.versionName ?: "0.0.0"

androidComponents {
    // Preview 任务须启用单测，开关已隐藏
    beforeVariants(selector().all()) { variant ->
        (variant as HasUnitTestBuilder).enableUnitTest = true
    }
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("EchoTideMusic-$apkVersionName-arm64-v8a.apk")
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

    // 键值存储 DataStore
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // 协程 Guava 桥接，供 buildAsync
    implementation(libs.kotlinx.coroutines.guava)

    // Media3 ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // 在线音乐搜索网络请求
    implementation(libs.okhttp)

    // 收集基准与启动配置
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