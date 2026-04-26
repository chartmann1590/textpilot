/*
 * Interstitial Ad Manager for QKSMS
 */
package com.charles.messenger.common.util

import android.app.Activity
import com.charles.messenger.manager.BillingManager
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import timber.log.Timber
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton

import com.charles.messenger.BuildConfig

@Singleton
class InterstitialAdManager @Inject constructor(
    private val billingManager: BillingManager,
    private val rewardedAdManager: RewardedAdManager
) {

    companion object {
        private const val AD_UNIT_ID = BuildConfig.ADMOB_INTERSTITIAL_ID
        private const val SHOW_PROBABILITY = 1.0 // Always show when ready for ad-supported users
    }

    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var isUpgraded = false
    private val random = Random()

    init {
        billingManager.upgradeStatus.subscribe { upgraded ->
            isUpgraded = upgraded
        }
    }

    /**
     * Preload an interstitial ad
     */
    fun loadAd(activity: Activity) {
        if (AD_UNIT_ID.isBlank()) {
            Timber.w("Interstitial ad unit ID is blank, skipping load")
            return
        }
        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "InterstitialAdManager.kt:45",
            message = "loadAd called",
            data = mapOf(
                "adUnitId" to AD_UNIT_ID,
                "isUpgraded" to isUpgraded.toString(),
                "hasAd" to (interstitialAd != null).toString(),
                "isLoading" to isLoading.toString()
            ),
            hypothesisId = "H8"
        )
        // #endregion
        if (interstitialAd != null || isLoading) {
            Timber.d("Skipping ad load - already loaded or loading")
            return
        }

        isLoading = true
        val adRequest = AdRequest.Builder().build()
        Timber.d("Loading interstitial ad with unit ID: $AD_UNIT_ID")

        InterstitialAd.load(activity, AD_UNIT_ID, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "InterstitialAdManager.kt:56",
                    message = "Interstitial ad failed to load",
                    data = mapOf(
                        "errorCode" to adError.code.toString(),
                        "errorMessage" to (adError.message ?: "null"),
                        "errorDomain" to (adError.domain ?: "null")
                    ),
                    hypothesisId = "H8"
                )
                // #endregion
                Timber.e("Interstitial ad failed to load: ${adError.message} (code: ${adError.code})")
                interstitialAd = null
                isLoading = false
            }

            override fun onAdLoaded(ad: InterstitialAd) {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "InterstitialAdManager.kt:62",
                    message = "Interstitial ad loaded successfully",
                    hypothesisId = "H8"
                )
                // #endregion
                Timber.d("Interstitial ad loaded successfully!")
                interstitialAd = ad
                isLoading = false

                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        Timber.d("Interstitial ad dismissed")
                        interstitialAd = null
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        Timber.e("Interstitial ad failed to show: ${adError.message}")
                        interstitialAd = null
                    }

                    override fun onAdShowedFullScreenContent() {
                        Timber.d("Interstitial ad showed successfully!")
                    }
                }
            }
        })
    }

    /**
     * Show interstitial ad with probability check
     * Returns true if ad was shown, false otherwise
     */
    fun maybeShowAd(activity: Activity): Boolean {
        if (AD_UNIT_ID.isBlank()) {
            Timber.w("Interstitial ad unit ID is blank, skipping show")
            return false
        }
        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "InterstitialAdManager.kt:90",
            message = "maybeShowAd called",
            data = mapOf(
                "isUpgraded" to isUpgraded.toString(),
                "hasAd" to (interstitialAd != null).toString(),
                "isAdFree" to rewardedAdManager.isAdFree().toString()
            ),
            hypothesisId = "H7"
        )
        // #endregion
        // Don't show ads to upgraded users
        if (isUpgraded) {
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "InterstitialAdManager.kt:92",
                message = "User is upgraded, skipping ad",
                hypothesisId = "H7"
            )
            // #endregion
            Timber.d("User is upgraded, skipping interstitial ad")
            return false
        }

        // Don't show ads during ad-free period from rewards
        if (rewardedAdManager.isAdFree()) {
            Timber.d("User is in ad-free period from rewards, skipping interstitial ad")
            return false
        }

        // Check probability
        val randomValue = random.nextFloat()
        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "InterstitialAdManager.kt:98",
            message = "Probability check",
            data = mapOf(
                "randomValue" to randomValue.toString(),
                "showProbability" to SHOW_PROBABILITY.toString(),
                "willShow" to (randomValue < SHOW_PROBABILITY).toString()
            ),
            hypothesisId = "H8"
        )
        // #endregion
        if (randomValue >= SHOW_PROBABILITY) {
            Timber.d("Skipping ad based on probability")
            loadAd(activity) // Preload for next time
            return false
        }

        return if (interstitialAd != null) {
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "InterstitialAdManager.kt:104",
                message = "Showing interstitial ad",
                hypothesisId = "H8"
            )
            // #endregion
            interstitialAd?.show(activity)
            true
        } else {
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "InterstitialAdManager.kt:108",
                message = "Ad not ready, loading for next time",
                hypothesisId = "H8"
            )
            // #endregion
            Timber.d("Interstitial ad not ready, loading for next time")
            loadAd(activity)
            false
        }
    }
}
