pluginManagement {
    // 版本巡检插件由本机仓库（~/.m2）消费：源码分支改完执行一次 publishToMavenLocal 即可生效，
    // 不再 includeBuild 源码工程，避免每次构建都为它配置并编译插件
    repositories {
        mavenLocal()
        // 依赖与插件统一走腾讯云镜像：maven-public 聚合了 Google Maven 与 Maven Central
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        // Gradle 插件门户，插件标记与插件实现均由该仓库提供
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
    }
}

plugins {
    // 依赖版本巡检：在 libs.versions.toml 中标注可用更新，是否升级仍由人工决定。
    // 版本号须与 ../refreshVersions/plugins/version.txt 一致
    id("de.fayard.refreshVersions") version "1.0.1"
}

refreshVersions {
    // 只接受正式版本：预发布标签与 JetBrains IDE 内部构建号（-ij262-、-KBA-）都带连字符，
    // 而插件对无法识别的后缀会兜底判为 Stable，故按连字符特征统一滤除
    rejectVersionIf {
        candidate.value.contains('-')
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    }
}

rootProject.name = "EchoTideMusic"
include(":app")
// 基准配置与启动配置的生成模块，不参与应用打包
include(":baselineprofile")
