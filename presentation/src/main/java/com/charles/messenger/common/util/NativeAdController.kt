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

    fun loadInto(container: FrameLayout) {
        val adUnitId = activity.getString(R.string.admob_native_advanced_id)
        if (adUnitId.isBlank()) {
            container.isVisible = false
            return
        }

        val adLoader = AdLoader.Builder(activity, adUnitId)
            .forNativeAd { loadedAd ->
                if (activity.isFinishing || activity.isDestroyed) {
                    loadedAd.destroy()
                    return@forNativeAd
                }

                nativeAd?.destroy()
                nativeAd = loadedAd

                val adView = LayoutInflater.from(activity)
                    .inflate(R.layout.native_ad_layout, container, false) as NativeAdView

                bind(adView, loadedAd)
                container.removeAllViews()
                container.addView(adView)
                container.isVisible = true
            }
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    container.removeAllViews()
                    container.isVisible = false
                    Timber.w("Native ad failed to load: ${adError.message} (${adError.code})")
                }
            })
            .build()

        container.removeAllViews()
        container.isVisible = false
        adLoader.loadAd(AdRequest.Builder().build())
    }

    fun destroy() {
        nativeAd?.destroy()
        nativeAd = null
    }
}
