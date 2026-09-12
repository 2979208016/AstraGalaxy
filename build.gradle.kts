buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
    dependencies {
        // AGP 9 内置 KGP 版本较低，这里强制提升到缓存中的 2.4.0
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
}

extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 36
