# security/proguard-rules.pro
-keep class com.propdf.security.data.entity.** { *; }
-keep class com.propdf.security.data.database.** { *; }
-keep class com.propdf.security.data.model.** { *; }

# iText
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-keep class * extends android.app.Application
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# ViewModel
-keep class * extends androidx.lifecycle.ViewModel
-keep class * extends androidx.lifecycle.AndroidViewModel

# Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
