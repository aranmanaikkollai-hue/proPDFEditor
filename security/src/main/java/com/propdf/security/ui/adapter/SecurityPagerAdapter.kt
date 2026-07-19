// security/src/main/java/com/propdf/security/ui/adapter/SecurityPagerAdapter.kt
package com.propdf.security.ui.adapter

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.propdf.security.ui.fragment.EncryptionFragment
import com.propdf.security.ui.fragment.PermissionsFragment
import com.propdf.security.ui.fragment.RedactionFragment
import com.propdf.security.ui.fragment.SanitizationFragment

/**
 * ViewPager2 adapter for [com.propdf.security.ui.activity.SecurityActivity]'s tabs.
 * Tab order matches the labels set up in SecurityActivity.setupViewPager():
 * 0 = Encryption, 1 = Permissions, 2 = Redaction, 3 = Sanitization.
 */
class SecurityPagerAdapter(
    activity: AppCompatActivity,
    private val documentUri: Uri?
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> EncryptionFragment.newInstance(documentUri)
            1 -> PermissionsFragment.newInstance(documentUri)
            2 -> RedactionFragment.newInstance(documentUri)
            3 -> SanitizationFragment.newInstance(documentUri)
            else -> throw IllegalArgumentException("Invalid tab position: $position")
        }
    }
}
