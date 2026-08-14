pluginManagement {
    repositories {
        // 国内网络慢时把下面两行阿里云镜像取消注释，放在 google() 之前
        // maven("https://maven.aliyun.com/repository/gradle-plugin")
        // maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // maven("https://maven.aliyun.com/repository/google")
        // maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "JinnVoiceIME"
include(":app")
