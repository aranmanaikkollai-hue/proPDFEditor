package com.propdf.storage.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * ActivityResultContract for picking a document tree via SAF.
 * Supports Drive, Dropbox, OneDrive via system picker without SDKs.
 */
class OpenDocumentTreeContract : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (input != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}
