import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * v112：发布签名口令从 local.properties 读取，不写进仓库。
 * 仓库里的 keystore/debug.keystore 是所有人共有的调试密钥，
 * 用它签 release 等于任何人拿到仓库就能伪造同包名覆盖包。
 */
fun releaseSigningProperties(): Properties {
    val file = rootProject.file("local.properties")
    return Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.xmu.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xmu.assistant.xia"
        minSdk = 26
        targetSdk = 35
        // versionName 只随用户要求变动（2026-11-19 由基座版本 1.8.4 改为 1.0，
        // 2026-11-20 升为 1.1，2026-11-25 升为 1.3）。轮次只体现在安装包文件名的 vNN 上；
        // versionCode 仅作内部构建号，用于 dumpsys / adb install 判断
        // 「装进去的是否为新包」，界面上不展示。
        versionCode = 209
        versionName = "1.3"
        buildConfigField("boolean", "NETWORK_METRICS", "false")
        // v24：改用高德轻量版地图 SDK（纯 jar，零 so），不再需要 abiFilters。
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // v17：AGP 8 默认 extractNativeLibs=false，so 以「不压缩」形式进 APK，
        // 高德那一颗 libAMapSDK_MAP 就有 19MB，直接把安装包从 4MB 顶到 27MB。
        // 打开 legacy packaging 后 so 会被 zip 压缩（19MB → 约 7.3MB），
        // 安装包回落到 15MB 上下；代价只是安装时解压到 /data（运行时占用不变）。
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
            // R8 removes these unused post-quantum algorithms, but retains their large
            // parameter resources. PDF text extraction and our TLS/storage do not use them.
            excludes += setOf(
                "org/bouncycastle/pqc/crypto/picnic/lowmc.properties",
                "org/bouncycastle/pqc/crypto/sike/p434.properties",
                "org/bouncycastle/pqc/crypto/sike/p503.properties",
                "org/bouncycastle/pqc/crypto/sike/p610.properties",
                "org/bouncycastle/pqc/crypto/sike/p751.properties",
            )
        }
    }

    signingConfigs {
        // 开发用固定调试签名：keystore 固定到项目内，保证本机覆盖安装（install -r）不丢数据。
        // 注意它只服务 debug 变体，不得再用于 release。
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // v112：发布签名。keystore 与口令都不进仓库：文件在 .gitignore 里，
        // 口令在 local.properties 的 xmu.release.* 四项。
        create("release") {
            val props = releaseSigningProperties()
            storeFile = rootProject.file(props.getProperty("xmu.release.storeFile", "keystore/release.keystore"))
            storePassword = props.getProperty("xmu.release.storePassword")
            keyAlias = props.getProperty("xmu.release.keyAlias")
            keyPassword = props.getProperty("xmu.release.keyPassword")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            // v112：独立发布签名。从这一版起，已装 debug 签名包的设备无法覆盖升级，
            // 必须卸载重装（本地数据丢一次）—— 这是换掉公共调试密钥的必然代价。
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.pdfbox.android)
    // v29：地图选点改为自绘瓦片（TileMapPicker.kt），不再依赖任何高德 SDK。
    // 轻量版 SDK 内置的 JS API Key 已被高德判 INVALID_USER_KEY，官方无修复版；
    // 公开栅格瓦片无需 Key、无需鉴权，jar 与 assets 一起从 APK 里彻底消失。
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.okhttp)
}

// v112：缺口令时让 release 构建立刻失败，而不是签出一个不可发布的包、
// 或者悄悄退回仓库里的公共调试密钥。
gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release", ignoreCase = true) }) {
        val props = releaseSigningProperties()
        listOf(
            "xmu.release.storePassword",
            "xmu.release.keyAlias",
            "xmu.release.keyPassword",
        ).forEach { key ->
            check(!props.getProperty(key).isNullOrBlank()) {
                "release 签名未配置：local.properties 缺少 $key"
            }
        }
    }
}
