package com.propdfeditor.ui.signature

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class SignaturePagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    override fun getItemCount(): Int = 3
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> SignatureListFragment()
        1 -> FavoriteSignaturesFragment()
        2 -> SignatureHistoryFragment()
        else -> throw IllegalArgumentException("Invalid position: $position")
    }
}
