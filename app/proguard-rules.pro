# ============================================================================
# ProPDF Editor ProGuard Rules
# Production-ready configuration for R8
# ============================================================================

# Keep Application class
-keep public class com.propdfeditor.ProPDFApplication {
    public <init>();
    public void onCreate();
}

# Keep Hilt
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-keep class * extends androidx.hilt.work.HiltWorker {
    @dagger.assisted.AssistedInject <init>(...);
}
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }

# Keep Room entities and DAOs
-keep class com.propdf.core.data.database.entity.** { *; }
-keep class com.propdf.core.data.database.dao.** { *; }
-keep class com.propdf.core.domain.model.** { *; }
-keep class com.propdf.core.domain.repository.** { *; }
-keep class com.propdf.core.domain.usecase.** { *; }

# Keep Parcelize
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# PDFBox
-dontwarn com.tom_roush.pdfbox.**
-keep class com.tom_roush.pdfbox.** { *; }
-keepclassmembers class com.tom_roush.pdfbox.pdmodel.PDDocument {
    public <init>();
    public static com.tom_roush.pdfbox.pdmodel.PDDocument load(java.io.InputStream);
}
-keepclassmembers class com.tom_roush.pdfbox.rendering.PDFRenderer {
    public <init>(com.tom_roush.pdfbox.pdmodel.PDDocument);
    public android.graphics.Bitmap renderImageWithDPI(int, float);
}

# iText
-dontwarn com.itextpdf.**
-keep class com.itextpdf.** { *; }

# BouncyCastle (Security module)
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# ML Kit OCR
-keep class com.google.mlkit.vision.** { *; }
-keep class com.google.mlkit.vision.text.** { *; }
-dontwarn com.google.mlkit.vision.text.**

# OpenCV (Scanner module) -- Java bindings are called via JNI; R8 renaming/stripping
# the native method declarations here would break native calls at runtime
# (NoSuchMethodError/UnsatisfiedLinkError) in release builds specifically, separately
# from the OpenCVLoader native-library-load issue OpenCvAvailability already guards.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# WorkManager
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class androidx.work.** { *; }

# Gson (Room TypeConverters)
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { *; }

# Navigation Compose
-keep class androidx.navigation.** { *; }
-keepclassmembers class * {
    @androidx.navigation.NavHostController <fields>;
}

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Coil
-keep class coil.** { *; }

# Timber
-dontwarn timber.log.Timber

# Startup Initializers
-keep class * implements androidx.startup.Initializer { *; }

# FileProvider
-keep class androidx.core.content.FileProvider { *; }

# Keep exceptions for crash reporting
-keep public class * extends java.lang.Exception
-keep public class * extends java.lang.RuntimeException

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# Optimize
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
