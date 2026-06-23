package com.propdf.ads

import android.view.ViewGroup

/**
 * No-op ads manager for open-source build.
 * Replace with real implementation for monetized builds.
 */
class AdsManager {
    fun initialize() { /* no-op */ }
    fun showBanner(container: ViewGroup) { 
        // container is intentionally unused in no-op implementation
        /* no-op */ 
    }
    fun showInterstitial(onComplete: () -> Unit) { onComplete() }
    fun showRewarded(onReward: () -> Unit, onDismiss: () -> Unit) { 
        // onReward is intentionally unused in no-op implementation
        onDismiss() 
    }
    fun destroy() { /* no-op */ }
}
