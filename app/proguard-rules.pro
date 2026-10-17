# Compose 运行时由 R8 规则自动处理；这里只保留必须反射/序列化的成员。
-keepclassmembers class dev.aigw.core.trae.** { <fields>; }
-keep class dev.aigw.core.store.** { *; }
-dontwarn org.nanohttpd.**

# Marvis 原生桥：JNI 按类全限定名/方法名/字段名绑定符号，捆绑的类禁止改名裁剪。
# libyyb_csech.so 的 JNI_OnLoad 会 FindClass("com/qq/taf/jce/*") 并 RegisterNatives 到
# CsCommManager——R8 改名任何一个都会导致注册失败直接 abort（闪退）。
-keep class com.tencent.assistant.protocol.scu.cscomm.** { *; }
-keep class com.tencent.assistant.protocol.jce.** { *; }
-keep class com.qq.taf.jce.** { *; }

# Tink（androidx.security.crypto 的依赖）引用了仅编译期存在的 JSR-305 注解
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
