package com.propdfeditor.batch.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.propdfeditor.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 closure: the real dialog_encrypt_config.xml wired to EncryptDialogBinder, without launching
 * BatchActivity (so no Hilt/storage picker needed). Does not cover the AlertDialog button wiring inside
 * BatchActivity itself or the output-directory picker; those remain untested at UI level.
 */
@RunWith(AndroidJUnit4::class)
class EncryptDialogBinderInstrumentedTest {

    /** Runs on the main thread and rethrows failures in the test thread (an exception thrown on the main thread would kill the process). */
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw it }
    }

    private class Dialog(val root: View, val binder: EncryptDialogBinder) {
        fun text(id: Int, value: String) = root.findViewById<TextInputEditText>(id).setText(value)
        fun check(id: Int, value: Boolean) { root.findViewById<CheckBox>(id).isChecked = value }
        fun layout(id: Int) = root.findViewById<TextInputLayout>(id)
        fun hint() = root.findViewById<TextView>(R.id.permissionsHint).text.toString()
    }

    private fun inflate(): Dialog {
        val ctx = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ProPDF)
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_encrypt_config, null)
        return Dialog(root, EncryptDialogBinder(root))
    }

    @Test fun defaults_areAllAllowed_singlePasswordHint() = onMain {
        val d = inflate()
        assertTrue(d.root.findViewById<CheckBox>(R.id.allowPrintingCheck).isChecked)
        assertTrue(d.root.findViewById<CheckBox>(R.id.allowCopyingCheck).isChecked)
        assertTrue(d.root.findViewById<CheckBox>(R.id.allowModifyingCheck).isChecked)
        assertEquals(d.root.context.getString(R.string.encrypt_hint_single_password), d.hint())
    }

    @Test fun unchecking_aPermission_switchesToRestrictedHint() = onMain {
        val d = inflate()
        d.check(R.id.allowCopyingCheck, false)
        assertEquals(d.root.context.getString(R.string.encrypt_hint_restricted), d.hint())
        d.check(R.id.allowCopyingCheck, true)
        assertEquals(d.root.context.getString(R.string.encrypt_hint_single_password), d.hint())
    }

    @Test fun singlePassword_validInput_buildsConfigWithoutOwner() = onMain {
        val d = inflate()
        d.text(R.id.passwordInput, "pw1"); d.text(R.id.confirmPasswordInput, "pw1")
        val v = d.binder.validateAndBuild()
        assertNotNull(v)
        assertFalse(v!!.restrictive)
        assertFalse(v.configJson.contains("ownerPassword"))
    }

    @Test fun restricted_withoutOwner_showsErrorAndBuildsNothing() = onMain {
        val d = inflate()
        d.text(R.id.passwordInput, "pw1"); d.text(R.id.confirmPasswordInput, "pw1")
        d.check(R.id.allowPrintingCheck, false)
        assertNull(d.binder.validateAndBuild())
        assertEquals(d.root.context.getString(R.string.encrypt_err_owner_required), d.layout(R.id.ownerPasswordLayout).error)
    }

    @Test fun restricted_ownerSameAsUser_showsError() = onMain {
        val d = inflate()
        d.text(R.id.passwordInput, "pw1"); d.text(R.id.confirmPasswordInput, "pw1")
        d.text(R.id.ownerPasswordInput, "pw1"); d.text(R.id.ownerConfirmInput, "pw1")
        d.check(R.id.allowModifyingCheck, false)
        assertNull(d.binder.validateAndBuild())
        assertEquals(d.root.context.getString(R.string.encrypt_err_owner_same), d.layout(R.id.ownerPasswordLayout).error)
    }

    @Test fun restricted_distinctConfirmedOwner_buildsConfigWithOwner_andClearsOldErrors() = onMain {
        val d = inflate()
        d.text(R.id.passwordInput, "pw1"); d.text(R.id.confirmPasswordInput, "pw1")
        d.check(R.id.allowPrintingCheck, false)
        assertNull(d.binder.validateAndBuild()) // sets an error first
        d.text(R.id.ownerPasswordInput, "own1"); d.text(R.id.ownerConfirmInput, "own1")
        val v = d.binder.validateAndBuild()
        assertNotNull(v)
        assertTrue(v!!.restrictive)
        assertTrue(v.configJson.contains("\"ownerPassword\":\"own1\""))
        assertNull(d.layout(R.id.ownerPasswordLayout).error)
    }

    @Test fun passwordMismatch_showsErrorOnConfirmField() = onMain {
        val d = inflate()
        d.text(R.id.passwordInput, "pw1"); d.text(R.id.confirmPasswordInput, "pw2")
        assertNull(d.binder.validateAndBuild())
        assertEquals(d.root.context.getString(R.string.encrypt_err_password_mismatch), d.layout(R.id.confirmPasswordLayout).error)
    }
}
