/*
 * Rewarded Ad Manager for QKSMS
 */
package com.charles.messenger.common.util

import android.app.Activity
import com.charles.messenger.BuildConfig
import com.charles.messenger.util.Preferences
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import io.reactivex.subjects.PublishSubject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RewardedAdManager @Inject constructor(
    private val prefs: Preferences
) {

    companion object {
        private const val AD_UNIT_ID = BuildConfig.ADMOB_REWARDED_ID
    }

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    val adLoadedSubject: PublishSubject<Boolean> = PublishSubject.create()
    val adEarnedRewardSubject: PublishSubject<Unit> = PublishSubject.create()
    val adDismissedSubject: PublishSubject<Boolean> = PublishSubject.create()

    /**
     * Load a rewarded ad
     */
    fun loadAd(activity: Activity) {
        if (AD_UNIT_ID.isBlank()) {
            Timber.w("Rewarded ad unit ID is blank, skipping load")
            adLoadedSubject.onNext(false)
            return
        }
        if (rewardedAd != null || isLoading) {
            Timber.d("Skipping rewarded ad load - already loaded or loading")
            adLoadedSubject.onNext(rewardedAd != null)
            return
        }

        isLoading = true
        val adRequest = AdRequest.Builder().build()
        Timber.d("Loading rewarded ad with unit ID: $AD_UNIT_ID")

        RewardedAd.load(activity, AD_UNIT_ID, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                Timber.e("Rewarded ad failed to load: ${adError.message} (code: ${adError.code})")
                rewardedAd = null
                isLoading = false
                adLoadedSubject.onNext(false)
            }

            override fun onAdLoaded(ad: RewardedAd) {
                Timber.d("Rewarded ad loaded successfully!")
                rewardedAd = ad
                isLoading = false
                adLoadedSubject.onNext(true)

                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        Timber.d("Rewarded ad dismissed")
                        rewardedAd = null
                        adDismissedSubject.onNext(true)
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        Timber.e("Rewarded ad failed to show: ${adError.message}")
                        rewardedAd = null
                        adDismissedSubject.onNext(false)
                    }

                    override fun onAdShowedFullScreenContent() {
                        Timber.d("Rewarded ad showed successfully!")
                    }
                }
            }
        })
    }

    /**
     * Show rewarded ad and award points
     */
    fun showAd(activity: Activity) {
        val ad = rewardedAd
        if (ad != null) {
            ad.show(activity) { rewardItem ->
                // Award points - each ad watched gives 1 point
                val currentPoints = prefs.rewardPoints.get()
                prefs.rewardPoints.set(currentPoints + 1)
                Timber.d("User earned reward! Total points: ${currentPoints + 1}")
                adEarnedRewardSubject.onNext(Unit)
            }
        } else {
            Timber.w("Rewarded ad not ready")
            adDismissedSubject.onNext(false)
        }
    }

    /**
     * Check if ad is ready to show
     */
    fun isAdReady(): Boolean = rewardedAd != null

    /**
     * Check if user is currently in ad-free period
     */
    fun isAdFree(): Boolean {
        val adFreeEndTime = prefs.adFreeEndTime.get()
        val currentTime = System.currentTimeMillis()
        return currentTime < adFreeEndTime
    }

    /**
     * Get remaining ad-free time in milliseconds
     */
    fun getRemainingAdFreeTime(): Long {
        if (!isAdFree()) return 0
        val adFreeEndTime = prefs.adFreeEndTime.get()
        val currentTime = System.currentTimeMillis()
        return adFreeEndTime - currentTime
    }

    /**
     * Redeem points for ad-free time
     * @param points Number of points to redeem
     * @return true if redemption was successful
     */
    fun redeemPoints(points: Int): Boolean {
        val currentPoints = prefs.rewardPoints.get()
        if (currentPoints < points) {
            Timber.w("Not enough points to redeem: $currentPoints < $points")
            return false
        }

        // Calculate ad-free time based on points
        // 1 point = 30 minutes
        val adFreeMinutes = points * 30
        val adFreeMillis = adFreeMinutes * 60 * 1000L

        // Add to existing ad-free time if already active
        val currentAdFreeEndTime = prefs.adFreeEndTime.get()
        val currentTime = System.currentTimeMillis()
        val newAdFreeEndTime = if (currentTime < currentAdFreeEndTime) {
            // Already ad-free, extend the time
            currentAdFreeEndTime + adFreeMillis
        } else {
            // Not ad-free, start from now
            currentTime + adFreeMillis
        }

        // Deduct points and set ad-free end time
        prefs.rewardPoints.set(currentPoints - points)
        prefs.adFreeEndTime.set(newAdFreeEndTime)

        Timber.d("Redeemed $points points for $adFreeMinutes minutes ad-free. New end time: $newAdFreeEndTime")
        return true
    }

    /**
     * Get current reward points
     */
    fun getCurrentPoints(): Int = prefs.rewardPoints.get()
}
