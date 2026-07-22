/*
 * Copyright (C) 2020 Moez Bhatti <charles.bhatti@gmail.com>
 *
 * This file is part of messenger.
 *
 * messenger is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * messenger is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with messenger.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.charles.messenger.common.util

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryPurchaseHistoryParams
import com.android.billingclient.api.QueryPurchasesParams
import com.charles.messenger.manager.AnalyticsManager
import com.charles.messenger.manager.BillingManager
import com.charles.messenger.util.Preferences
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManagerImpl @Inject constructor(
    context: Context,
    private val analyticsManager: AnalyticsManager,
    private val prefs: Preferences
) : BillingManager, BillingClientStateListener, PurchasesUpdatedListener {

    private val productsSubject: Subject<List<ProductDetails>> = BehaviorSubject.create()
    override val products: Observable<List<BillingManager.Product>> = productsSubject
            .map { productDetailsList ->
                productDetailsList.map { productDetails ->
                    val productId = productDetails.productId
                    val price = productDetails.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
                    val currencyCode = productDetails.oneTimePurchaseOfferDetails?.priceCurrencyCode ?: ""
                    BillingManager.Product(productId, price, currencyCode)
                }
            }

    private val trialStatusSubject: BehaviorSubject<BillingManager.TrialState> = BehaviorSubject.createDefault(getTrialState())
    override val trialStatus: Observable<BillingManager.TrialState> = trialStatusSubject.distinctUntilChanged()

    override val trialDaysRemaining: Observable<Int> = trialStatusSubject.map { calculateTrialDaysRemaining() }

    private val purchaseListSubject = BehaviorSubject.create<List<Purchase>>()
    private val purchasedStatus: Observable<Boolean> = purchaseListSubject
            .map { purchases ->
                purchases
                        .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                        .any { it.products.any { productId -> productId in skus } }
            }
            .distinctUntilChanged()
            .doOnNext { upgraded -> analyticsManager.setUserProperty("Upgraded", upgraded) }

    override val upgradeStatus: Observable<Boolean> = Observable.combineLatest(
            purchasedStatus.startWith(false),
            trialStatus
    ) { purchased, trial ->
        purchased || trial == BillingManager.TrialState.ACTIVE
    }.distinctUntilChanged()

    private val skus = listOf(BillingManager.SKU_PLUS, BillingManager.SKU_PLUS_DONATE)
    private val billingClient: BillingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()

    private val billingClientState = MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        billingClientState.tryEmit(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
    }

    override suspend fun checkForPurchases() = executeServiceRequest {
        // Load the cached data
        queryPurchases()

        // On a fresh device, the purchase might not be cached, and so we'll need to force a refresh
        val historyParams = QueryPurchaseHistoryParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        billingClient.queryPurchaseHistoryAsync(historyParams) { _, _ -> }
        queryPurchases()
    }

    override suspend fun queryProducts() = executeServiceRequest {
        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "BillingManagerImpl.kt:121",
            message = "queryProducts called",
            data = mapOf("skuCount" to skus.size.toString()),
            hypothesisId = "H4"
        )
        // #endregion
        val productList = skus.map { sku ->
            QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(sku)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "BillingManagerImpl.kt:132",
                message = "queryProducts callback",
                data = mapOf(
                    "responseCode" to billingResult.responseCode.toString(),
                    "productCount" to (productDetailsList?.size ?: 0).toString(),
                    "debugMessage" to (billingResult.debugMessage ?: "null")
                ),
                hypothesisId = "H4"
            )
            // #endregion
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                productsSubject.onNext(productDetailsList.orEmpty())
            }
        }
    }

    override suspend fun initiatePurchaseFlow(activity: Activity, sku: String) = executeServiceRequest {
        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "BillingManagerImpl.kt:139",
            message = "initiatePurchaseFlow called",
            data = mapOf("sku" to sku),
            hypothesisId = "H1"
        )
        // #endregion
        val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(sku)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()

        var productDetails: ProductDetails? = null
        var callbackCompleted = false

        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "BillingManagerImpl.kt:151",
            message = "Calling queryProductDetailsAsync",
            data = mapOf("sku" to sku),
            hypothesisId = "H3"
        )
        // #endregion
        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "BillingManagerImpl.kt:152",
                message = "queryProductDetailsAsync callback",
                data = mapOf(
                    "responseCode" to billingResult.responseCode.toString(),
                    "productDetailsCount" to (productDetailsList?.size ?: 0).toString()
                ),
                hypothesisId = "H3"
            )
            // #endregion
            productDetails = productDetailsList?.firstOrNull()
            callbackCompleted = true
        }

        // Wait for async callback to complete (with timeout)
        var waitCount = 0
        while (!callbackCompleted && waitCount < 50) {
            kotlinx.coroutines.delay(100)
            waitCount++
        }

        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "BillingManagerImpl.kt:163",
            message = "After waiting for callback",
            data = mapOf(
                "callbackCompleted" to callbackCompleted.toString(),
                "waitCount" to waitCount.toString(),
                "productDetailsFound" to (productDetails != null).toString()
            ),
            hypothesisId = "H3"
        )
        // #endregion

        productDetails?.let { details ->
            // Ensure product details has required offer details
            val offerDetails = details.oneTimePurchaseOfferDetails
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "BillingManagerImpl.kt:165",
                message = "Product details found",
                data = mapOf(
                    "hasOfferDetails" to (offerDetails != null).toString(),
                    "productId" to details.productId
                ),
                hypothesisId = "H4"
            )
            // #endregion
            if (offerDetails != null) {
                try {
                    // #region agent log
                    com.charles.messenger.util.DebugLogger.log(
                        location = "BillingManagerImpl.kt:176",
                        message = "About to launch billing flow",
                        hypothesisId = "H5"
                    )
                    // #endregion
                    val productDetailsParamsList = listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(details)
                                .build()
                    )
                    val flowParams = BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(productDetailsParamsList)
                            .build()
                    val result = billingClient.launchBillingFlow(activity, flowParams)
                    // #region agent log
                    com.charles.messenger.util.DebugLogger.log(
                        location = "BillingManagerImpl.kt:176",
                        message = "launchBillingFlow result",
                        data = mapOf("responseCode" to result.responseCode.toString()),
                        hypothesisId = "H5"
                    )
                    // #endregion
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        val errorMsg = "Billing flow launch failed: ${result.responseCode}, ${result.debugMessage}"
                        Timber.w(errorMsg)
                        throw BillingUnavailableException(errorMsg)
                    }
                } catch (e: NullPointerException) {
                    // Handle ProxyBillingActivity crash - PendingIntent is null
                    Timber.e(e, "ProxyBillingActivity crash: PendingIntent is null. This may be due to billing service issues.")
                    throw com.charles.messenger.common.util.BillingUnavailableException("Billing service error: ${e.message ?: "Unknown error"}")
                } catch (e: Exception) {
                    // #region agent log
                    com.charles.messenger.util.DebugLogger.log(
                        location = "BillingManagerImpl.kt:178",
                        message = "Error launching billing flow",
                        data = mapOf("error" to e.message, "errorType" to e.javaClass.simpleName),
                        hypothesisId = "H5"
                    )
                    // #endregion
                    Timber.e(e, "Error launching billing flow")
                }
            } else {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "BillingManagerImpl.kt:181",
                    message = "Product details missing offer details",
                    data = mapOf("sku" to sku),
                    hypothesisId = "H4"
                )
                // #endregion
                Timber.w("Product details missing oneTimePurchaseOfferDetails for SKU: $sku")
            }
        } ?: run {
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "BillingManagerImpl.kt:184",
                message = "Product details not found",
                data = mapOf("sku" to sku),
                hypothesisId = "H4"
            )
            // #endregion
            Timber.w("Product details not found for SKU: $sku")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    handlePurchases(purchases.orEmpty())
                } catch (e: Exception) {
                    Timber.w(e, "Error handling purchases")
                }
            }
        }
    }

    private suspend fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        handlePurchases(purchasesList)
                    } catch (e: Exception) {
                        Timber.w(e, "Error handling purchases query")
                    }
                }
            }
        }
    }

    private suspend fun handlePurchases(purchases: List<Purchase>) = executeServiceRequest {
        purchases.forEach { purchase ->
            if (!purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()

                Timber.i("Acknowledging purchase ${purchase.orderId}")
                billingClient.acknowledgePurchase(params) { billingResult ->
                    Timber.i("Acknowledgement result: ${billingResult.responseCode}, ${billingResult.debugMessage}")
                }
            }
        }

        purchaseListSubject.onNext(purchases)
    }

    private suspend fun executeServiceRequest(runnable: suspend () -> Unit) {
        val currentState = billingClientState.first()
        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "BillingManagerImpl.kt:226",
            message = "executeServiceRequest called",
            data = mapOf("currentState" to currentState.toString()),
            hypothesisId = "H2"
        )
        // #endregion
        if (currentState != BillingClient.BillingResponseCode.OK) {
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "BillingManagerImpl.kt:229",
                message = "Starting billing service connection",
                hypothesisId = "H2"
            )
            // #endregion
            Timber.i("Starting billing service")
            billingClient.startConnection(this)
        }

        // Retry once on a cold-start timeout: some devices bind the Play Store's billing
        // service slowly on first launch. A single retry with a fresh connection attempt
        // clears most of these without giving up after only 10s.
        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "BillingManagerImpl.kt:232",
                message = "Waiting for billing client to be ready (with 10s timeout)",
                data = mapOf("attempt" to attempt.toString()),
                hypothesisId = "H2"
            )
            // #endregion
            try {
                val readyState = withTimeout(10000) {
                    billingClientState.first { state -> state == BillingClient.BillingResponseCode.OK }
                }
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "BillingManagerImpl.kt:232",
                    message = "Billing client ready",
                    data = mapOf("state" to readyState.toString(), "attempt" to attempt.toString()),
                    hypothesisId = "H2"
                )
                // #endregion
                runnable()
                return
            } catch (e: TimeoutCancellationException) {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "BillingManagerImpl.kt:232",
                    message = "Timeout waiting for billing client",
                    data = mapOf("attempt" to attempt.toString()),
                    hypothesisId = "H2"
                )
                // #endregion
                Timber.i("Timeout waiting for billing client to connect (attempt $attempt/$maxAttempts)")
                // Check if billing is unavailable (response code 3) by checking the current state
                // Since billingClientState is a SharedFlow with replay=1, we can get the current value
                val finalState = try {
                    withContext(Dispatchers.IO) {
                        kotlinx.coroutines.withTimeout(50) {
                            billingClientState.first()
                        }
                    }
                } catch (e2: kotlinx.coroutines.TimeoutCancellationException) {
                    null
                } catch (e2: Exception) {
                    Timber.w(e2, "Error checking final billing state")
                    null
                }
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "BillingManagerImpl.kt:400",
                    message = "Final billing state after timeout",
                    data = mapOf("finalState" to finalState.toString(), "attempt" to attempt.toString()),
                    hypothesisId = "H2"
                )
                // #endregion
                if (finalState == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
                    throw BillingUnavailableException("Billing service unavailable on device")
                }
                if (attempt < maxAttempts) {
                    // Retry with a fresh connection attempt before giving up.
                    Timber.i("Retrying billing service connection")
                    billingClient.startConnection(this)
                    continue
                }
                throw BillingTimeoutException("Billing service connection timeout")
            }
        }
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "BillingManagerImpl.kt:236",
            message = "onBillingSetupFinished",
            data = mapOf(
                "responseCode" to result.responseCode.toString(),
                "debugMessage" to (result.debugMessage ?: "null")
            ),
            hypothesisId = "H2"
        )
        // #endregion
        Timber.i("Billing response: ${result.responseCode}")
        billingClientState.tryEmit(result.responseCode)
    }

    override fun onBillingServiceDisconnected() {
        Timber.i("Billing service disconnected")
        billingClientState.tryEmit(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
    }

    override fun startTrial() {
        // Check if trial has already been used for this device (persists across reinstalls)
        val existingTrialStart = prefs.getTrialStartTimestampForDevice()
        if (existingTrialStart != 0L) {
            Timber.i("Trial already used for this device. Start: $existingTrialStart")
            // Update the status to reflect current state (might be expired)
            trialStatusSubject.onNext(getTrialState())
            return
        }
        
        // Check legacy preference as well
        if (prefs.trialStartTimestamp.get() != 0L) {
            Timber.i("Trial already used (legacy). Migrating to device-specific tracking.")
            // Migrate to device-specific tracking
            prefs.setTrialStartTimestampForDevice(prefs.trialStartTimestamp.get())
            trialStatusSubject.onNext(getTrialState())
            return
        }
        
        // Start new trial
        val now = System.currentTimeMillis()
        prefs.setTrialStartTimestampForDevice(now)
        trialStatusSubject.onNext(getTrialState())
        Timber.i("Trial started for device at: $now")
    }

    private fun getTrialState(): BillingManager.TrialState {
        // Check device-specific trial first (persists across reinstalls)
        var trialStart = prefs.getTrialStartTimestampForDevice()
        // Fallback to legacy preference for backward compatibility
        if (trialStart == 0L) {
            trialStart = prefs.trialStartTimestamp.get()
            // Migrate legacy preference to device-specific if it exists
            if (trialStart != 0L) {
                prefs.setTrialStartTimestampForDevice(trialStart)
            }
        }
        
        if (trialStart == 0L) {
            return BillingManager.TrialState.NOT_STARTED
        }
        val trialDurationMillis = TimeUnit.DAYS.toMillis(BillingManager.TRIAL_DURATION_DAYS.toLong())
        val trialEnd = trialStart + trialDurationMillis
        return if (System.currentTimeMillis() < trialEnd) {
            BillingManager.TrialState.ACTIVE
        } else {
            BillingManager.TrialState.EXPIRED
        }
    }

    private fun calculateTrialDaysRemaining(): Int {
        // Check device-specific trial first (persists across reinstalls)
        var trialStart = prefs.getTrialStartTimestampForDevice()
        // Fallback to legacy preference for backward compatibility
        if (trialStart == 0L) {
            trialStart = prefs.trialStartTimestamp.get()
        }
        
        if (trialStart == 0L) return 0
        val trialDurationMillis = TimeUnit.DAYS.toMillis(BillingManager.TRIAL_DURATION_DAYS.toLong())
        val trialEnd = trialStart + trialDurationMillis
        val remaining = trialEnd - System.currentTimeMillis()
        return if (remaining > 0) {
            TimeUnit.MILLISECONDS.toDays(remaining).toInt() + 1
        } else {
            0
        }
    }

}
