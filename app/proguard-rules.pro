# ProPDF Editor ProGuard Rules

# Keep Hilt
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# Keep Room entities
-keep class com.propdf.core.data.database.entity.** { *; }
-keep class com.propdf.core.domain.model.** { *; }

# Keep Parcelize
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# PDFBox
-dontwarn com.tom_roush.pdfbox.**
-keep class com.tom_roush.pdfbox.** { *; }

# iText
-dontwarn com.itextpdf.**
-keep class com.itextpdf.** { *; }

# BouncyCastle
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Gson (for Room TypeConverters)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Navigation
-keep class androidx.navigation.** { *; }
