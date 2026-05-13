package com.propdf.ads

import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op ads manager for open-source build.
 * Replace with real implementation for monetized builds.
 */
@Singleton
class AdsManager @Inject constructor() {
    fun initialize() { /* no-op */ }
    fun showBanner(container: android.view.ViewGroup) { /* no-op */ }
    fun showInterstitial(onComplete: () -> Unit) { onComplete() }
    fun showRewarded(onReward: () -> Unit, onDismiss: () -> Unit) { onDismiss() }
    fun destroy() { /* no-op */ }
}
