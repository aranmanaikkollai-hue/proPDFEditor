// security/src/main/java/com/propdf/security/data/model/SecurityResult.kt
package com.propdf.security.data.model

import android.net.Uri

sealed class SecurityResult {
    data class Success(val outputUri: Uri) : SecurityResult()
    data class Error(val message: String, val exception: Throwable? = null) : SecurityResult()
    object Cancelled : SecurityResult()
    data class Progress(val percentage: Int) : SecurityResult()
}
