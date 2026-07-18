// security/src/main/java/com/propdf/security/provider/SecureFileProvider.kt
package com.propdf.security.provider

import android.content.Context
import androidx.core.content.FileProvider
import com.propdf.security.R

class SecureFileProvider : FileProvider() {
    companion object {
        fun getAuthority(context: Context): String {
            return "${context.packageName}.security.provider"
        }
    }
}
