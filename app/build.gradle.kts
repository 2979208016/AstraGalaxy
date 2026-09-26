import java.util.Properties

// 插件由根工程 buildscript 的 classpath 提供（离线构建）。
// 不用 plugins{} 块，避免 offline 下解析 plugin marker 失败。
plugins {
    id("com.android.application")
}

// 版本来自工程根目录的 version.properties，避免每次改版本要动多处
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "io.github.proify.lyricon.xinghe"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.proify.lyricon.xinghe"
        minSdk = 27
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").trim().toInt()
        versionName = versionProps.getProperty("versionName").trim()
    }

    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/*.version"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")


    implementation("io.github.proify.lyricon:provider:0.1.70")
    implementation("io.github.proify.lyricon.lyric:model:0.1.70")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // 星河岛接入库（AstraIsland）：挂卡片、收按钮点击都靠它。
    // LyricON 协议只负责「歌词喇叭」，互动能力在接入库里。
    implementation(files("libs/astraisland-client.aar"))

    testImplementation("junit:junit:4.13.2")
}
