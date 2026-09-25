pluginManagement {
    repositories {
        // 依赖与插件统一走腾讯云镜像：maven-public 聚合了 Google Maven 与 Maven Central
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        // Gradle 插件门户，插件标记与插件实现均由该仓库提供
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
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
