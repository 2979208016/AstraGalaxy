buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
    dependencies {
        // AGP 9 + 独立的 Kotlin Gradle 插件（离线缓存里是 2.4.0）
        classpath("com.android.tools.build:gradle:9.3.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

extra["compileSdk"] = 37

extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 36
