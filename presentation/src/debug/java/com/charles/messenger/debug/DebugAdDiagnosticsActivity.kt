package com.charles.messenger.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import com.charles.messenger.BuildConfig
import com.charles.messenger.R
import com.charles.messenger.common.util.NativeAdController
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DebugAdDiagnosticsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(root)

        val resultFile = File(cacheDir, "ad_diagnostics_result.txt")
        val results = linkedMapOf<String, String>()
        val remaining = AtomicInteger(4)
        val finished = AtomicBoolean(false)
        var loadedNativeAd: NativeAd? = null

        fun finalizeResult() {
            if (!finished.compareAndSet(false, true)) return

            val status = if (results.values.all { it.startsWith("ok") }) "ok" else "error"
            val content = buildString {
                appendLine("status=$status")
                results.forEach { (key, value) -> appendLine("$key=$value") }
            }

            resultFile.writeText(content)
            loadedNativeAd?.destroy()
            finish()
        }

        fun record(key: String, value: String) {
            results[key] = value
            if (remaining.decrementAndGet() == 0) {
                finalizeResult()
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!finished.get()) {
                if (!results.containsKey("banner")) results["banner"] = "timeout"
                if (!results.containsKey("interstitial")) results["interstitial"] = "timeout"
                if (!results.containsKey("rewarded")) results["rewarded"] = "timeout"
                if (!results.containsKey("native")) results["native"] = "timeout"
                finalizeResult()
            }
        }, 90_000)

        MobileAds.initialize(this) {}

        val bannerUnitId = getString(R.string.admob_banner_id)
        if (bannerUnitId.isBlank()) {
            record("banner", "missing_unit_id")
        } else {
            val adView = AdView(this).apply {
                adUnitId = bannerUnitId
                setAdSize(AdSize.BANNER)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        record("banner", "ok")
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        record("banner", "failed:${adError.code}")
                    }
                }
            }
            root.addView(adView)
            adView.loadAd(AdRequest.Builder().build())
        }

        if (BuildConfig.ADMOB_INTERSTITIAL_ID.isBlank()) {
            record("interstitial", "missing_unit_id")
        } else {
            InterstitialAd.load(
                this,
                BuildConfig.ADMOB_INTERSTITIAL_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        record("interstitial", "ok")
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        record("interstitial", "failed:${adError.code}")
                    }
                }
            )
        }

        if (BuildConfig.ADMOB_REWARDED_ID.isBlank()) {
            record("rewarded", "missing_unit_id")
        } else {
            RewardedAd.load(
                this,
                BuildConfig.ADMOB_REWARDED_ID,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        record("rewarded", "ok")
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        record("rewarded", "failed:${adError.code}")
                    }
                }
            )
        }

        val nativeUnitId = getString(R.string.admob_native_advanced_id)
        if (nativeUnitId.isBlank()) {
            record("native", "missing_unit_id")
        } else {
            AdLoader.Builder(this, nativeUnitId)
                .forNativeAd { nativeAd ->
                    loadedNativeAd?.destroy()
                    loadedNativeAd = nativeAd

                    val adView = layoutInflater.inflate(
                        R.layout.native_ad_layout,
                        root,
                        false
                    ) as NativeAdView

                    NativeAdController.bind(adView, nativeAd)
                    root.addView(adView)
                    record("native", "ok")
                }
                .withNativeAdOptions(NativeAdOptions.Builder().build())
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        record("native", "failed:${adError.code}")
                    }
                })
                .build()
                .loadAd(AdRequest.Builder().build())
        }
    }
}
