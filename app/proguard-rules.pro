# OkHttp 自带 consumer rules，这里只补充平台可选依赖的告警屏蔽
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── 清单按全限定名反射实例化的组件：保留类名与构造器 ──
-keep class com.capswriter.ime.CapsWriterIme { <init>(); }
-keep class com.capswriter.ime.KeepAliveService { <init>(); }
-keep class com.capswriter.ime.CapsWriterAccessibilityService { <init>(); }
-keep class com.capswriter.ime.BootReceiver { <init>(); }
-keep class com.capswriter.ime.SettingsActivity { <init>(); }

# 自定义 View 在 XML 中按全限定名实例化，保留两参构造器
-keepclasseswithmembers class com.capswriter.ime.MicButton {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# 系统通过 meta-data @xml/method 与 @xml/accessibility 资源引用，
# 这些资源里出现的类名必须不被混淆改名
-keep class com.capswriter.ime.CapsWriterIme
-keep class com.capswriter.ime.CapsWriterAccessibilityService

# data class 的字段被 JSON 序列化（org.json 反射取值），保留字段名
-keep class com.capswriter.ime.AudioMessage { *; }
-keep class com.capswriter.ime.RecognitionMessage { *; }

# 不混淆 LinkState 枚举（被 AsrClient 回调跨进程/序列化可能引用，符号需稳定）
-keep enum com.capswriter.ime.LinkState { *; }

# Shizuku 通过 AIDL 反射调用，release 需保留
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
