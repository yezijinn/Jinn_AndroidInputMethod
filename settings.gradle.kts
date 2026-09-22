pluginManagement {
    repositories {
        // 国内网络直连 dl.google.com / repo.maven.apache.org 常超时或解析不到，
        // 阿里云镜像作为公共仓库代理放在前面（与下面 google()/mavenCentral() 内容一致）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "com.jinn.inputmethod"
include(":app")
