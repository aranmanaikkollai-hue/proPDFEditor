# PATH: app/proguard-rules.pro
# ProGuard/R8 rules for ProPDF Editor

# ── iText 7 ────────────────────────────────────────────────────
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# ── PDFBox ─────────────────────────────────────────────────────
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ── BouncyCastle ───────────────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── ML Kit ─────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── CameraX ────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Hilt / Dagger ──────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ── Room ───────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ── Kotlin coroutines ──────────────────────────────────────────
-dontwarn kotlinx.coroutines.**

# ── General Android ────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class * implements android.os.Parcelable { *; }
-keep class * implements java.io.Serializable { *; }

# ── Gson (if used) ─────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keep class * { @com.google.gson.annotations.SerializedName <fields>; }

# ── Keep app data classes ──────────────────────────────────────
-keep class com.propdf.editor.data.** { *; }
-keep class com.propdf.editor.ui.viewer.AnnotationCanvasView$* { *; }
