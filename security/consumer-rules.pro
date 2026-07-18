# security/consumer-rules.pro
-keep class com.propdf.security.data.entity.** { *; }
-keep class com.propdf.security.data.database.** { *; }
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
