# Keep WorkManager
-keep class androidx.work.** { *; }
-keep class androidx.hilt.work.** { *; }

# Keep Room entities
-keep class com.propdf.storage.data.local.entity.** { *; }
-keep class com.propdf.backup.data.local.entity.** { *; }
-keep class com.propdf.sync.data.local.entity.** { *; }
-keep class com.propdf.nas.data.local.entity.** { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep SMBJ
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.msfscc.** { *; }
-dontwarn com.hierynomus.**

# Keep Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-dontwarn com.thegrizzlylabs.sardineandroid.**

# Keep ZXing
-keep class com.google.zxing.** { *; }

# Keep Nearby Connections
-keep class com.google.android.gms.nearby.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Security Crypto
-keep class androidx.security.crypto.** { *; }
