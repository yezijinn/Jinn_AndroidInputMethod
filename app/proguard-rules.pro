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

# 翻译相关枚举（2026-10-03 修复 L-552）。
# `TranslationLanguage` 的**常量名**是持久化值（prefs 的 translate_target 与备份包都存 it.name），
# 另两个枚举存的是自定义 id（更稳，也一并保留以防将来改成 name）。
# ⚠ 此前完全依赖 AGP 默认规则集里「enum 的 values()/valueOf() 保留」这条**没写进仓库**的隐性契约：
# 一旦默认规则被改动或启用枚举名混淆，已存下来的 JAPANESE 之类字符串就会对新名字失效 ⇒ of() 静默
# 回落 English（用户看到「我明明选的日语」却无任何提示），跨版本导入备份同理。
-keepclassmembers enum com.jinn.inputmethod.TranslationLanguage { *; }
-keepclassmembers enum com.jinn.inputmethod.TranslationScope { *; }
-keepclassmembers enum com.jinn.inputmethod.TranslationProviderId { *; }