package com.propdf.ads

import android.content.Context
import android.view.ViewGroup
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdsManager @Inject constructor() {

    fun initialize(context: Context) {
        // Initialize ads SDK
    }

    fun loadBanner(container: ViewGroup) {
        // Banner implementation - container used
        container.removeAllViews()
        // Add banner view to container
    }

    fun loadInterstitial(context: Context) {
        // Interstitial implementation
    }

    fun showRewardedAd(context: Context, onReward: (Boolean) -> Unit) {
        // Rewarded ad implementation - onReward used
        onReward(true)
    }

    fun dispose() {
        // Cleanup
    }
}
