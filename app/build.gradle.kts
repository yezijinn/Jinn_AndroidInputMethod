import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.io.FileInputStream
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 签名配置 ─────────────────────────────────────────────
// 一键打包脚本通过环境变量注入 E:\JinnKeyStores 中的统一密钥；
// keystore.properties 仅作为手动构建的本地兼容回退，绝不入库。
val keystoreProps = Properties()
val ksFile = rootProject.file("keystore.properties")
if (ksFile.exists()) {
    keystoreProps.load(FileInputStream(ksFile))
}

val externalStoreFile = System.getenv("JINN_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val externalStorePassword = System.getenv("JINN_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val externalKeyAlias = System.getenv("JINN_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val externalKeyPassword = System.getenv("JINN_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val signingStoreFile = externalStoreFile?.let { File(it) }
    ?: keystoreProps.getProperty("storeFile")?.let(rootProject::file)
val signingStorePassword = externalStorePassword ?: keystoreProps.getProperty("storePassword")
val signingKeyAlias = externalKeyAlias ?: keystoreProps.getProperty("keyAlias")
val signingKeyPassword = externalKeyPassword ?: keystoreProps.getProperty("keyPassword")
val apksignerOnly = System.getenv("JINN_APKSIGNER_ONLY") == "1"

android {
    namespace = "com.jinn.inputmethod"
    compileSdk = 34

    signingConfigs {
        create("release") {
            if (!apksignerOnly && signingStoreFile != null && signingStorePassword != null &&
                signingKeyAlias != null && signingKeyPassword != null) {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                // Use modern APK Signature Schemes; V1 is intentionally disabled.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.jinn.inputmethod"
        // 26 起可只提供自适应图标，且 AudioRecord / VectorDrawable 行为稳定
        minSdk = 26
        targetSdk = 34
        // versionCode 取编译日期（yyyyMMdd，如 20260814），随每次编译递增，天然单调
        versionCode = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        versionName = "jinn"
        resourceConfigurations += setOf("zh-rCN")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 外部统一密钥或本地兼容配置完整时启用签名，否则保留未签名构建能力。
            if (!apksignerOnly && signingStoreFile != null && signingStorePassword != null &&
                signingKeyAlias != null && signingKeyPassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    androidResources {
        // 词库以 xz 压缩格式存放，再被 deflate 压一遍既无收益，还拖慢构建
        noCompress += "xz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
            "DebugProbesKt.bin",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Activity Result API（registerForActivityResult）需要 ComponentActivity
    implementation("androidx.activity:activity-ktx:1.9.0")
    // 唯一的第三方依赖：WebSocket 客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 可选：通过 Shizuku 免 root 获取 adb 级权限，用于防杀后台加白名单
    implementation("dev.rikka.shizuku:api:13.1.5")
    // 词库解压：词库以 xz 存放（28.0MB → 8.0MB，比 deflate 再省 22%，APK 体积随之下降约 20%），
    // 加载时流式解压、无需落地磁盘，实测解压约 0.6s 且发生在后台加载线程。
    implementation("org.tukaani:xz:1.9")

    // ── 本地单元测试（src/test/，JVM，无需设备） ──
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
