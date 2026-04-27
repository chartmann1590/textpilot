package com.charles.messenger.common.util

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.charles.messenger.R
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import timber.log.Timber

class NativeAdController(
    private val activity: Activity
) {
    companion object {
        // Google's official Android native test ad unit ID.
        private const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

        fun bind(adView: NativeAdView, ad: NativeAd) {
            val headlineView = adView.findViewById<TextView>(R.id.ad_headline)
            val bodyView = adView.findViewById<TextView>(R.id.ad_body)
            val advertiserView = adView.findViewById<TextView>(R.id.ad_advertiser)
            val callToActionView = adView.findViewById<Button>(R.id.ad_call_to_action)
            val iconView = adView.findViewById<ImageView>(R.id.ad_app_icon)
            val mediaView = adView.findViewById<MediaView>(R.id.ad_media)

            adView.headlineView = headlineView
            adView.bodyView = bodyView
            adView.advertiserView = advertiserView
            adView.callToActionView = callToActionView
            adView.iconView = iconView
            adView.mediaView = mediaView

            headlineView.text = ad.headline

            bodyView.text = ad.body
            bodyView.isVisible = !ad.body.isNullOrBlank()

            advertiserView.text = ad.advertiser
            advertiserView.isVisible = !ad.advertiser.isNullOrBlank()

            callToActionView.text = ad.callToAction
            callToActionView.isVisible = !ad.callToAction.isNullOrBlank()

            val icon = ad.icon
            if (icon != null) {
                iconView.setImageDrawable(icon.drawable)
                iconView.isVisible = true
            } else {
                iconView.isVisible = false
            }

            val mediaContent = ad.mediaContent
            mediaView.mediaContent = mediaContent
            mediaView.visibility = if (mediaContent != null) View.VISIBLE else View.GONE

            adView.setNativeAd(ad)
        }
    }

    private var nativeAd: NativeAd? = null
    private var loading = false

    fun load(
        onLoadedAd: ((NativeAd) -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        if (loading) return

        val configuredAdUnitId = activity.getString(R.string.admob_native_advanced_id)
        val adUnitId = configuredAdUnitId.ifBlank { TEST_NATIVE_AD_UNIT_ID }
        com.charles.messenger.util.DebugLogger.log(
            location = "NativeAdController.kt:loadInto",
            message = "Native ad load requested",
            data = mapOf(
                "adUnitId" to adUnitId,
                "loading" to loading.toString(),
                "usingFallbackTestId" to configuredAdUnitId.isBlank().toString()
            ),
            hypothesisId = "ADS_NATIVE_1"
        )

        loading = true

        val adLoader = AdLoader.Builder(activity, adUnitId)
            .forNativeAd { loadedAd ->
                loading = false
                if (activity.isFinishing || activity.isDestroyed) {
                    loadedAd.destroy()
                    com.charles.messenger.util.DebugLogger.log(
                        location = "NativeAdController.kt:onNativeAdLoaded",
                        message = "Activity is finishing/destroyed; native ad discarded",
                        hypothesisId = "ADS_NATIVE_2"
                    )
                    onFailed?.invoke()
                    return@forNativeAd
                }

                nativeAd?.destroy()
                nativeAd = loadedAd
                com.charles.messenger.util.DebugLogger.log(
                    location = "NativeAdController.kt:onNativeAdLoaded",
                    message = "Native ad loaded successfully",
                    hypothesisId = "ADS_NATIVE_2"
                )
                onLoadedAd?.invoke(loadedAd)
            }
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    loading = false
                    com.charles.messenger.util.DebugLogger.log(
                        location = "NativeAdController.kt:onAdFailedToLoad",
                        message = "Native ad failed to load",
                        data = mapOf(
                            "errorCode" to adError.code.toString(),
                            "errorMessage" to adError.message,
                            "errorDomain" to adError.domain
                        ),
                        hypothesisId = "ADS_NATIVE_3"
                    )
                    Timber.w("Native ad failed to load: ${adError.message} (${adError.code})")
                    onFailed?.invoke()
                }
            })
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    fun loadInto(
        container: FrameLayout,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        container.removeAllViews()
        container.isVisible = false
        load(
            onLoadedAd = { loadedAd ->
                val adView = LayoutInflater.from(activity)
                    .inflate(R.layout.native_ad_layout, container, false) as NativeAdView
                bind(adView, loadedAd)
                container.removeAllViews()
                container.addView(adView)
                container.isVisible = true
                onLoaded?.invoke()
            },
            onFailed = onFailed
        )
    }

    fun destroy() {
        loading = false
        nativeAd?.destroy()
        nativeAd = null
    }
}
