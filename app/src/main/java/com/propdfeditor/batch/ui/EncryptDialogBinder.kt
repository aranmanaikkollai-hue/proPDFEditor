package com.propdfeditor.batch.ui

import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.propdfeditor.R
import com.propdfeditor.batch.util.EncryptInputValidator
import com.propdfeditor.batch.util.EncryptInputValidator.Problem

/**
 * Wires dialog_encrypt_config.xml to [EncryptInputValidator]. Holds no state beyond the views and never logs or
 * copies passwords anywhere except into the validator input at submit time.
 */
class EncryptDialogBinder(private val root: View) {

    private val passwordLayout: TextInputLayout = root.findViewById(R.id.passwordLayout)
    private val confirmLayout: TextInputLayout = root.findViewById(R.id.confirmPasswordLayout)
    private val ownerLayout: TextInputLayout = root.findViewById(R.id.ownerPasswordLayout)
    private val ownerConfirmLayout: TextInputLayout = root.findViewById(R.id.ownerConfirmLayout)
    private val passwordInput: TextInputEditText = root.findViewById(R.id.passwordInput)
    private val confirmInput: TextInputEditText = root.findViewById(R.id.confirmPasswordInput)
    private val ownerInput: TextInputEditText = root.findViewById(R.id.ownerPasswordInput)
    private val ownerConfirmInput: TextInputEditText = root.findViewById(R.id.ownerConfirmInput)
    private val printCheck: CheckBox = root.findViewById(R.id.allowPrintingCheck)
    private val copyCheck: CheckBox = root.findViewById(R.id.allowCopyingCheck)
    private val modifyCheck: CheckBox = root.findViewById(R.id.allowModifyingCheck)
    private val hint: TextView = root.findViewById(R.id.permissionsHint)

    init {
        printCheck.setOnCheckedChangeListener { _, _ -> refreshHint() }
        copyCheck.setOnCheckedChangeListener { _, _ -> refreshHint() }
        modifyCheck.setOnCheckedChangeListener { _, _ -> refreshHint() }
        refreshHint()
    }

    fun read(): EncryptInputValidator.Input = EncryptInputValidator.Input(
        password = passwordInput.text?.toString().orEmpty(),
        confirmPassword = confirmInput.text?.toString().orEmpty(),
        ownerPassword = ownerInput.text?.toString().orEmpty(),
        ownerConfirm = ownerConfirmInput.text?.toString().orEmpty(),
        allowPrinting = printCheck.isChecked,
        allowCopying = copyCheck.isChecked,
        allowModifying = modifyCheck.isChecked
    )

    private fun refreshHint() {
        val restrictive = EncryptInputValidator.isRestrictive(read())
        hint.setText(if (restrictive) R.string.encrypt_hint_restricted else R.string.encrypt_hint_single_password)
    }

    /** Validates; shows field errors and returns null when invalid, otherwise returns the config JSON. */
    fun validateAndBuild(): EncryptInputValidator.Result.Valid? {
        clearErrors()
        return when (val r = EncryptInputValidator.validate(read())) {
            is EncryptInputValidator.Result.Valid -> r
            is EncryptInputValidator.Result.Invalid -> { showErrors(r.problems); null }
        }
    }

    private fun clearErrors() {
        passwordLayout.error = null; confirmLayout.error = null
        ownerLayout.error = null; ownerConfirmLayout.error = null
    }

    private fun showErrors(problems: Set<Problem>) {
        val ctx = root.context
        if (Problem.PASSWORD_EMPTY in problems) passwordLayout.error = ctx.getString(R.string.encrypt_err_password_empty)
        if (Problem.PASSWORD_MISMATCH in problems) confirmLayout.error = ctx.getString(R.string.encrypt_err_password_mismatch)
        if (Problem.OWNER_REQUIRED in problems) ownerLayout.error = ctx.getString(R.string.encrypt_err_owner_required)
        if (Problem.OWNER_SAME_AS_USER in problems) ownerLayout.error = ctx.getString(R.string.encrypt_err_owner_same)
        if (Problem.OWNER_MISMATCH in problems) ownerConfirmLayout.error = ctx.getString(R.string.encrypt_err_owner_mismatch)
    }
}
