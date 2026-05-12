# ProPDF Editor ProGuard Rules
-keep public class com.propdf.** { *; }
-keepclassmembers class com.propdf.** { *; }

# iText7
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# PDFBox
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { @androidx.room.PrimaryKey <fields>; }

# Hilt
-keep class * extends dagger.hilt.internal.GeneratedComponent
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Parcelize
-keep class * implements android.os.Parcelable { *; }
-keep class * extends android.os.Parcelable$Creator { *; }
