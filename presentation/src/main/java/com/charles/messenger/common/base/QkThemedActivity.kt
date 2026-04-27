/*
 * Copyright (C) 2017 Moez Bhatti <charles.bhatti@gmail.com>
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
package com.charles.messenger.common.base

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.iterator
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.charles.messenger.R
import com.charles.messenger.common.util.Colors
import com.charles.messenger.common.util.extensions.resolveThemeBoolean
import com.charles.messenger.common.util.extensions.resolveThemeColor
import com.charles.messenger.extensions.Optional
import com.charles.messenger.extensions.asObservable
import com.charles.messenger.extensions.mapNotNull
import com.charles.messenger.repository.ConversationRepository
import com.charles.messenger.repository.MessageRepository
import com.charles.messenger.util.PhoneNumberUtils
import com.charles.messenger.util.Preferences
import com.charles.messenger.manager.BillingManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap
import javax.inject.Inject

/**
 * Base activity that automatically applies any necessary theme theme settings and colors
 *
 * In most cases, this should be used instead of the base QkActivity, except for when
 * an activity does not depend on the theme
 */
abstract class QkThemedActivity : QkActivity() {
    private data class InitialPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class InitialMargins(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    companion object {
        private val initialPaddingCache = WeakHashMap<View, InitialPadding>()
        private val initialMarginsCache = WeakHashMap<View, InitialMargins>()
    }

    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var colors: Colors
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var phoneNumberUtils: PhoneNumberUtils
    @Inject lateinit var prefs: Preferences

    /**
     * In case the activity should be themed for a specific conversation, the selected conversation
     * can be changed by pushing the threadId to this subject
     */
    val threadId: Subject<Long> = BehaviorSubject.createDefault(0)

    /**
     * Switch the theme if the threadId changes
     * Set it based on the latest message in the conversation
     */
    val theme: Observable<Colors.Theme> = threadId
            .distinctUntilChanged()
            .switchMap { threadId ->
                val conversation = conversationRepo.getConversation(threadId)
                when {
                    conversation == null -> Observable.just(Optional(null))

                    conversation.recipients.size == 1 -> Observable.just(Optional(conversation.recipients.first()))

                    else -> messageRepo.getLastIncomingMessage(conversation.id)
                            .asObservable()
                            .mapNotNull { messages -> messages.firstOrNull() }
                            .distinctUntilChanged { message -> message.address }
                            .mapNotNull { message ->
                                conversation.recipients.find { recipient ->
                                    phoneNumberUtils.compare(recipient.address, message.address)
                                }
                            }
                            .map { recipient -> Optional(recipient) }
                            .startWith(Optional(conversation.recipients.firstOrNull()))
                            .distinctUntilChanged()
                }
            }
            .switchMap { colors.themeObservable(it.value) }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getActivityThemeRes(prefs.black.get()))
        super.onCreate(savedInstanceState)

        // Initialize AdMob
        MobileAds.initialize(this) {}

        // When certain preferences change, we need to recreate the activity
        val triggers = listOf(prefs.nightMode, prefs.night, prefs.black, prefs.textSize, prefs.systemFont)
        Observable.merge(triggers.map { it.asObservable().skip(1) })
                .debounce(400, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposable(scope())
                .subscribe { recreate() }

        // ...
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // Handle window insets to respect system bars (status bar and navigation bar)
        // This ensures content doesn't extend behind system bars on all activities
        setupWindowInsets()

        // Initialize and load AdMob banner (only for non-upgraded users)
        try {
            val adView = findViewById<AdView>(R.id.adView)
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "QkThemedActivity.kt:128",
                message = "Banner ad view lookup",
                data = mapOf("adViewFound" to (adView != null).toString()),
                hypothesisId = "H9"
            )
            // #endregion
            adView?.let { ad ->
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "QkThemedActivity.kt:130",
                    message = "Subscribing to upgrade status",
                    data = mapOf("adUnitId" to (ad.adUnitId ?: "null")),
                    hypothesisId = "H9"
                )
                // #endregion
                billingManager.upgradeStatus
                    .take(1)
                    .autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
                    .subscribe { upgraded ->
                        // #region agent log
                        com.charles.messenger.util.DebugLogger.log(
                            location = "QkThemedActivity.kt:133",
                            message = "Upgrade status received",
                            data = mapOf("upgraded" to upgraded.toString()),
                            hypothesisId = "H7"
                        )
                        // #endregion
                        if (upgraded) {
                            ad.visibility = View.GONE
                            timber.log.Timber.d("User is upgraded, hiding banner ad")
                        } else {
                            ad.visibility = View.VISIBLE
                            // #region agent log
                            com.charles.messenger.util.DebugLogger.log(
                                location = "QkThemedActivity.kt:138",
                                message = "Loading banner ad",
                                data = mapOf("adUnitId" to (ad.adUnitId ?: "null")),
                                hypothesisId = "H8"
                            )
                            // #endregion
                            val adRequest = AdRequest.Builder().build()
                            ad.loadAd(adRequest)
                            timber.log.Timber.d("Banner ad loading with unit ID: ${ad.adUnitId}")

                            ad.adListener = object : com.google.android.gms.ads.AdListener() {
                                override fun onAdLoaded() {
                                    // #region agent log
                                    com.charles.messenger.util.DebugLogger.log(
                                        location = "QkThemedActivity.kt:143",
                                        message = "Banner ad loaded successfully",
                                        hypothesisId = "H8"
                                    )
                                    // #endregion
                                    timber.log.Timber.d("Banner ad loaded successfully")
                                }

                                override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                                    ad.visibility = View.GONE
                                    // #region agent log
                                    com.charles.messenger.util.DebugLogger.log(
                                        location = "QkThemedActivity.kt:147",
                                        message = "Banner ad failed to load",
                                        data = mapOf(
                                            "errorCode" to error.code.toString(),
                                            "errorMessage" to (error.message ?: "null"),
                                            "errorDomain" to (error.domain ?: "null")
                                        ),
                                        hypothesisId = "H8"
                                    )
                                    // #endregion
                                    timber.log.Timber.e("Banner ad failed to load: ${error.message} (${error.code})")
                                }
                            }
                        }
                    }
            } ?: run {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "QkThemedActivity.kt:129",
                    message = "Banner ad view not found in layout",
                    hypothesisId = "H9"
                )
                // #endregion
            }
        } catch (e: Exception) {
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "QkThemedActivity.kt:154",
                message = "Exception loading banner ad",
                data = mapOf("error" to e.message, "errorType" to e.javaClass.simpleName),
                hypothesisId = "H10"
            )
            // #endregion
            timber.log.Timber.e(e, "Error loading banner ad")
        }

        // Set the color for the overflow and navigation icon
        val textSecondary = resolveThemeColor(android.R.attr.textColorSecondary)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.overflowIcon = toolbar?.overflowIcon?.apply { setTint(textSecondary) }

        // Update the colours of the menu items
        Observables.combineLatest(menu, theme) { menu, theme ->
            menu.iterator().forEach { menuItem ->
                val tint = when (menuItem.itemId) {
                    in getColoredMenuItems() -> theme.theme
                    else -> textSecondary
                }

                menuItem.icon = menuItem.icon?.apply { setTint(tint) }
            }
        }.autoDisposable(scope(Lifecycle.Event.ON_DESTROY)).subscribe()
    }

    open fun getColoredMenuItems(): List<Int> {
        return listOf()
    }

    /**
     * This can be overridden in case an activity does not want to use the default themes
     */
    open fun getActivityThemeRes(black: Boolean) = when {
        black -> R.style.AppTheme_Black
        else -> R.style.AppTheme
    }

    /**
     * Sets up window insets handling to respect system bars (status bar and navigation bar).
     * This ensures content doesn't extend behind system bars on physical devices.
     * 
     * This method tries to find common layout patterns and applies padding appropriately:
     * - mainContent (MainActivity with DrawerLayout)
     * - containerContent (container_activity.xml used by Settings, Backup, etc.)
     * - contentView (ComposeActivity)
     * - Falls back to root view if no specific content view is found
     * 
     * Activities can override this method to provide custom insets handling if needed.
     * 
     * Note: This overrides QkActivity's setupWindowInsets() to provide more specific handling
     * for themed activities, so we don't call super to avoid duplicate insets application.
     */
    override fun setupWindowInsets() {
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        rootView?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                // Keep the main content clear of status/navigation bars.
                val contentView = root.findViewById<View>(R.id.mainContent)
                    ?: root.findViewById(R.id.containerContent)
                    ?: root.findViewById(R.id.contentView)
                    ?: root
                contentView.updatePadding(
                    left = contentView.initialPadding().left + systemBars.left,
                    top = contentView.initialPadding().top + systemBars.top,
                    right = contentView.initialPadding().right + systemBars.right,
                    bottom = contentView.initialPadding().bottom
                )

                // Ensure bottom pinned ad containers stay above gesture/navigation bar.
                val adContainer = root.findViewById<View>(resolveId("adContainer"))
                adContainer?.let { view ->
                    val initialMargins = view.initialMargins()
                    view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        leftMargin = initialMargins.left + systemBars.left
                        rightMargin = initialMargins.right + systemBars.right
                        bottomMargin = initialMargins.bottom + systemBars.bottom
                    }
                }
                val adView = root.findViewById<View>(R.id.adView)
                adView?.takeIf { adContainer == null }?.let { view ->
                    val initialMargins = view.initialMargins()
                    view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        leftMargin = initialMargins.left + systemBars.left
                        rightMargin = initialMargins.right + systemBars.right
                        bottomMargin = initialMargins.bottom + systemBars.bottom
                    }
                }

                // Ensure scrolled content remains reachable above the bottom bar/ads.
                root.findViewById<View>(R.id.recyclerView)?.let { view ->
                    view.updatePadding(bottom = view.initialPadding().bottom + systemBars.bottom)
                }
                root.findViewById<View>(R.id.messageList)?.let { view ->
                    view.updatePadding(bottom = view.initialPadding().bottom + systemBars.bottom)
                }
                root.findViewById<View>(R.id.container)?.let { view ->
                    view.updatePadding(bottom = view.initialPadding().bottom + systemBars.bottom)
                }

                insets
            }
            ViewCompat.requestApplyInsets(root)
        }
    }

    private fun View.initialPadding(): InitialPadding {
        val cached = initialPaddingCache[this]
        if (cached != null) return cached

        val initial = InitialPadding(
            left = paddingLeft,
            top = paddingTop,
            right = paddingRight,
            bottom = paddingBottom
        )
        initialPaddingCache[this] = initial
        return initial
    }

    private fun resolveId(name: String): Int {
        return resources.getIdentifier(name, "id", packageName)
    }

    private fun View.initialMargins(): InitialMargins {
        val cached = initialMarginsCache[this]
        if (cached != null) return cached

        val layoutParams = layoutParams as? ViewGroup.MarginLayoutParams
        val initial = InitialMargins(
            left = layoutParams?.leftMargin ?: 0,
            top = layoutParams?.topMargin ?: 0,
            right = layoutParams?.rightMargin ?: 0,
            bottom = layoutParams?.bottomMargin ?: 0
        )
        initialMarginsCache[this] = initial
        return initial
    }

}
