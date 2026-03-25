package com.propdf.editor.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileHelper {

fun uriToFile(context: Context, uri: Uri): File? {
return try {
val inputStream = context.contentResolver.openInputStream(uri) ?: return null
val file = File(context.cacheDir, "temp_pdf.pdf")

val outputStream = FileOutputStream(file)

inputStream.copyTo(outputStream)

inputStream.close()
outputStream.close()

file
} catch (e: Exception) {
e.printStackTrace()
null
}
}
}