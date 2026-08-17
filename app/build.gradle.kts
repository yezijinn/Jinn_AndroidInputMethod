import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jinn.voiceinput"
    compileSdk = 34

    // ── 正式签名 ──
    // keystore 由 keytool 生成：keystore/jinn-release.jks（alias=jinn, 密码见下）
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/jinn-release.jks")
            storePassword = "jinn123456"
            keyAlias = "jinn"
            keyPassword = "jinn123456"
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
            signingConfig = signingConfigs.getByName("release")
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
