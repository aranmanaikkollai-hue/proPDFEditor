# ProPDFEditor Annotation Module ProGuard Rules
# Keep Room entities
-keep class com.propdf.annotations.persistence.** { *; }
-keep class com.propdf.annotations.model.** { *; }

# Keep Gson serialization
-keepclassmembers class com.propdf.annotations.model.** {
    <fields>;
}

# Keep annotation constructors for Room
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <init>(...);
}

# PDFBox
-dontwarn com.tom_roush.pdfbox.**
-keep class com.tom_roush.pdfbox.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }
