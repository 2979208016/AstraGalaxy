pluginManagement {
    repositories {
        mavenCentral()
maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // mavenCentral 放最前：aliyun 镜像对新发布的第三方库会 502，且 Gradle 一旦报错
        // 会把整个仓库标记为 disabled，后面的依赖就全解析不出来。
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "AstraFirmament"
include(":app")
