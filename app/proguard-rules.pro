# OkHttp 自带 consumer rules，这里只补充平台可选依赖的告警屏蔽
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── 清单按全限定名反射实例化的组件：保留类名与构造器 ──
# JinnIme 的构造器由下方 `-keep class com.jinn.inputmethod.JinnIme`（保留类与全部成员）覆盖，勿重复声明
-keep class com.jinn.inputmethod.SettingsActivity { <init>(); }

# 自定义 View 在 XML 中按全限定名实例化，保留两参构造器
-keepclasseswithmembers class com.jinn.inputmethod.MicButton {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# 系统通过 meta-data @xml/method 引用输入法服务，类名必须不被混淆改名
-keep class com.jinn.inputmethod.JinnIme

# data class 的字段被 JSON 序列化（org.json 反射取值），保留字段名
-keep class com.jinn.inputmethod.AudioMessage { *; }
-keep class com.jinn.inputmethod.RecognitionMessage { *; }

# 不混淆 LinkState 枚举（被 AsrClient 回调跨进程/序列化可能引用，符号需稳定）
-keep enum com.jinn.inputmethod.LinkState { *; }
