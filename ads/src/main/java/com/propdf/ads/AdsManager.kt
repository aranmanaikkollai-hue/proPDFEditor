package com.propdf.ads

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdsManager @Inject constructor() {

    fun initialize(context: Context) {
        // Use context or suppress if truly not needed
    }

    fun loadBanner(context: Context) {
        // Use context or suppress if truly not needed
    }

    fun loadInterstitial(context: Context) {
        // Use context or suppress if truly not needed
    }

    fun showInterstitial() {
        // TODO: Implement
    }

    fun isInterstitialLoaded(): Boolean = false
}
