plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.capswriter.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.capswriter.ime"
        // 26 起可只提供自适应图标，且 AudioRecord / VectorDrawable 行为稳定
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
