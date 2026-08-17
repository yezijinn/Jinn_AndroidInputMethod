import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 签名配置（密码从 keystore.properties 读取，该文件不入库）──
// 本地新建 keystore.properties：
//   storeFile=keystore/jinn-release.jks
//   storePassword=***
//   keyAlias=jinn
//   keyPassword=***
val keystoreProps = Properties()
val ksFile = rootProject.file("keystore.properties")
if (ksFile.exists()) {
    keystoreProps.load(FileInputStream(ksFile))
}

android {
    namespace = "com.jinn.voiceinput"
    compileSdk = 34

    signingConfigs {
        create("release") {
            if (ksFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/jinn-release.jks"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias", "jinn")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.jinn.voiceinput"
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
            // 仅当本地有签名配置时才启用签名（开源仓库不含密码，构建为未签名）
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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

    // ── 本地单元测试（src/test/，JVM，无需设备） ──
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
