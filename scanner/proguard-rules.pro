# Scanner module ProGuard rules

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# Keep scanner domain models for serialization
-keep class com.propdfeditor.scanner.domain.model.** { *; }

# Keep ViewModel constructors for Hilt
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    @javax.inject.Inject <init>(...);
}
