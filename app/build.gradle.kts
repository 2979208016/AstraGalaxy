import java.util.Properties

// 插件由根工程 buildscript 的 classpath 提供（离线构建）。
// 不用 plugins{} 块，避免 offline 下解析 plugin marker 失败。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本来自工程根目录的 version.properties，避免每次改版本要动多处
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// 签名凭据放在 app/signing.properties（不随交付源码分发）：
//   storePassword=… / keyAlias=… / keyPassword=…
// 没有 keystore 或 signing.properties 时 release 不签名，源码照样能构建。
val signingProps = Properties().apply {
    file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.proify.astrafirmament"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.proify.astrafirmament"
        // COUI（ColorOS 凝光组件库）硬门槛 minSdk=31；aarMetadata 禁止 overrideLibrary。
        // 8.0 起放弃 Android 10/11，统一 API 31+（用户已确认）。
        minSdk = 31
        resourceConfigurations += listOf("zh", "en")
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").trim().toInt()
        versionName = versionProps.getProperty("versionName").trim()
    }

    signingConfigs {
        val ksFile = file("release.keystore")
        if (ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = signingProps.getProperty("storePassword", "")
                keyAlias = signingProps.getProperty("keyAlias", "astragalaxy")
                keyPassword = signingProps.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            // 体积纪律：这个模块原来 2.6MB，接了 Compose + 上游部件后未混淆的 dex 有 29MB。
            // 开 R8 全量混淆 + 资源收缩，把用不到的 Compose/图标实现全部删掉（用户要求上限 5MB）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
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

    // 星河岛接入库（AstraIsland SDK）：挂卡片、收按钮点击都靠它。
    // LyricON 协议只负责「歌词喇叭」，互动能力在接入库里。
    implementation(files("libs/astraisland-sdk-0.1.2.aar"))

    // 界面：与参考项目（ChizuruMizuhara）同一套上游部件
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    // 9.0 界面重构：ColorOS 17「凝光」组件库（底栏 NavigationBar / Item / Switch /
    // Slider / Dialog / Scaffold）。库自带 Compose BOM-alpha。
    // 9.0 起移除 hig / hig-icons / backdrop / shapes / capsule 四个上游依赖，
    // 界面只保留 COUI + 自绘图标（体积与内存纪律）。
    implementation("io.github.itoseo:coui:0.0.1-alpha11")
    // 图标统一走 COUI 官方图标库（9.1：替换自绘控件图标）。
    implementation("io.github.itoseo:coui-icons:0.0.1-alpha11")

    testImplementation("junit:junit:4.13.2")
}
