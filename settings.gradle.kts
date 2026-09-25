pluginManagement {
    // 依赖版本巡检插件改用本地维护分支构建，源码位于项目同级目录
    includeBuild("../refreshVersions/plugins")
    repositories {
        // 依赖与插件统一走腾讯云镜像：maven-public 聚合了 Google Maven 与 Maven Central
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        // Gradle 插件门户，插件标记与插件实现均由该仓库提供
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
    }
}

plugins {
    // 依赖版本巡检：在 libs.versions.toml 中标注可用更新，是否升级仍由人工决定
    id("de.fayard.refreshVersions")
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
