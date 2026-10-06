package com.propdf.viewer.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * Asks for the password of a protected PDF. Shown only for [PDFViewerViewModel.ViewerState.PasswordRequired];
 * access/permission failures keep their own error screen. The typed text is handed over as a CharArray that the
 * ViewModel wipes after use.
 */
@Composable
fun PasswordPrompt(
    previousAttemptFailed: Boolean,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit
) {
    // Not rememberSaveable: a typed password must not be written into saved instance state.
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("This PDF is protected. Enter its password to open it.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    isError = previousAttemptFailed,
                    supportingText = {
                        if (previousAttemptFailed) {
                            Text("Incorrect password. Try again.", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    onSubmit(chars)
                }
            ) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
