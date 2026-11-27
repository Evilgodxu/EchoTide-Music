pluginManagement {
    // 巡检插件从 ~/.m2 消费，改动后重新发布
    repositories {
        mavenLocal()
        // 腾讯云镜像聚合 Google 与 Maven 仓库
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        // Gradle 插件门户
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
    }
}

plugins {
    // 版本巡检，版本号须与 version.txt 一致
    id("de.fayard.refreshVersions") version "1.0.1"
}

refreshVersions {
    // 只接受正式版：带连字符的后缀一律滤除
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
// 基准与启动配置生成模块，不参与打包
include(":baselineprofile")
