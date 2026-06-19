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
package com.charles.messenger.feature.plus

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.jakewharton.rxbinding2.view.clicks
import com.charles.messenger.BuildConfig
import com.charles.messenger.R
import com.charles.messenger.common.base.QkThemedActivity
import com.charles.messenger.common.util.FontProvider
import com.charles.messenger.common.util.extensions.makeToast
import com.charles.messenger.common.util.extensions.resolveThemeColor
import com.charles.messenger.common.util.extensions.setBackgroundTint
import com.charles.messenger.common.util.extensions.setTint
import com.charles.messenger.common.util.extensions.setVisible
import com.charles.messenger.common.widget.PreferenceView
import com.charles.messenger.feature.plus.experiment.UpgradeButtonExperiment
import com.charles.messenger.manager.BillingManager
import dagger.android.AndroidInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class PlusActivity : QkThemedActivity(), PlusView {

    @Inject lateinit var fontProvider: FontProvider
    @Inject lateinit var upgradeButtonExperiment: UpgradeButtonExperiment
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[PlusViewModel::class.java] }

    private lateinit var collapsingToolbar: CollapsingToolbarLayout
    private lateinit var linearLayout: ViewGroup
    private lateinit var free: LinearLayout
    private lateinit var toUpgrade: LinearLayout
    private lateinit var description: TextView
    private lateinit var trialStatus: TextView
    private lateinit var startTrial: TextView
    private lateinit var upgrade: TextView
    private lateinit var upgradeDonate: TextView
    private lateinit var upgraded: ViewGroup
    private lateinit var thanksIcon: ImageView
    private lateinit var donate: TextView
    private lateinit var themes: PreferenceView
    private lateinit var schedule: PreferenceView
    private lateinit var backup: PreferenceView
    private lateinit var delayed: PreferenceView
    private lateinit var night: PreferenceView

    override val upgradeIntent by lazy { upgrade.clicks() }
    override val upgradeDonateIntent by lazy { upgradeDonate.clicks() }
    override val startTrialIntent by lazy { startTrial.clicks() }
    override val donateIntent by lazy { donate.clicks() }
    override val themeClicks by lazy { themes.clicks() }
    override val scheduleClicks by lazy { schedule.clicks() }
    override val backupClicks by lazy { backup.clicks() }
    override val delayedClicks by lazy { delayed.clicks() }
    override val nightClicks by lazy { night.clicks() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.qksms_plus_activity)
        setTitle(R.string.title_qksms_plus)
        showBackButton(true)

        collapsingToolbar = findViewById(R.id.collapsingToolbar)
        linearLayout = findViewById(R.id.plusContentLayout)
        free = findViewById(R.id.free)
        toUpgrade = findViewById(R.id.toUpgrade)
        // Safe cast to handle potential layout issues - description is a QkTextView in the layout
        // Find it within the toUpgrade LinearLayout first to avoid conflicts with other views
        val descriptionViewFound = try {
            toUpgrade.findViewById<View>(R.id.description) ?: findViewById<View>(R.id.description)
        } catch (e: Exception) {
            Timber.e(e, "Error finding description view")
            null
        }
        description = when {
            descriptionViewFound is TextView -> descriptionViewFound
            descriptionViewFound != null -> {
                val foundType = descriptionViewFound.javaClass.simpleName
                Timber.e("Description view (id: description) is not a TextView. Found: $foundType")
                // Try to find it as TextView directly, or create a dummy TextView to prevent crash
                findViewById<TextView>(R.id.description) ?: TextView(this).apply {
                    text = ""
                    visibility = View.GONE
                }
            }
            else -> {
                Timber.e("Description view (id: description) not found, trying direct findViewById")
                findViewById<TextView>(R.id.description) ?: TextView(this).apply {
                    text = ""
                    visibility = View.GONE
                }
            }
        }
        trialStatus = findViewById(R.id.trialStatus)
        startTrial = findViewById(R.id.startTrial)
        upgrade = findViewById(R.id.upgrade)
        upgradeDonate = findViewById(R.id.upgradeDonate)
        upgraded = findViewById(R.id.upgraded)
        thanksIcon = findViewById(R.id.thanksIcon)
        donate = findViewById(R.id.donate)
        themes = findViewById(R.id.themes)
        schedule = findViewById(R.id.schedule)
        backup = findViewById(R.id.backup)
        delayed = findViewById(R.id.delayed)
        night = findViewById(R.id.night)

        viewModel.bindView(this)

        free.setVisible(false)

        if (!prefs.systemFont.get()) {
            fontProvider.getLato { lato ->
                val typeface = Typeface.create(lato, Typeface.BOLD)
                collapsingToolbar.setCollapsedTitleTypeface(typeface)
                collapsingToolbar.setExpandedTitleTypeface(typeface)
            }
        }

        // Make the list titles bold
        linearLayout.children
                .mapNotNull { it as? PreferenceView }
                .map { it.findViewById<TextView>(R.id.titleView) }
                .forEach { it.setTypeface(it.typeface, Typeface.BOLD) }

        val textPrimary = resolveThemeColor(android.R.attr.textColorPrimary)
        collapsingToolbar.setCollapsedTitleTextColor(textPrimary)
        collapsingToolbar.setExpandedTitleColor(textPrimary)

        val theme = colors.theme().theme
        donate.setBackgroundTint(theme)
        upgrade.setBackgroundTint(theme)
        startTrial.setBackgroundTint(theme)
        thanksIcon.setTint(theme)
    }

    override fun render(state: PlusState) {
        val fdroid = BuildConfig.FLAVOR == "noAnalytics"

        // Update description based on trial state
        when (state.trialState) {
            BillingManager.TrialState.NOT_STARTED -> {
                description.text = getString(R.string.qksms_plus_description_summary, state.upgradePrice)
                trialStatus.setVisible(false)
                startTrial.setVisible(!fdroid && !state.upgraded)
            }
            BillingManager.TrialState.ACTIVE -> {
                description.text = getString(R.string.qksms_plus_trial_active_description)
                trialStatus.text = getString(R.string.qksms_plus_trial_days_remaining, state.trialDaysRemaining)
                trialStatus.setVisible(true)
                startTrial.setVisible(false)
            }
            BillingManager.TrialState.EXPIRED -> {
                description.text = getString(R.string.qksms_plus_trial_expired_description, state.upgradePrice)
                trialStatus.text = getString(R.string.qksms_plus_trial_expired)
                trialStatus.setVisible(true)
                startTrial.setVisible(false)
            }
        }

        upgrade.text = getString(R.string.qksms_plus_upgrade, state.upgradePrice)
        upgradeDonate.text = getString(R.string.qksms_plus_upgrade_donate, state.upgradeDonatePrice, state.currency)

        free.setVisible(fdroid)
        toUpgrade.setVisible(!fdroid && !state.upgraded)
        upgraded.setVisible(!fdroid && state.upgraded)

        // Features are enabled if upgraded OR trial is active
        val featuresEnabled = state.upgraded || state.trialState == BillingManager.TrialState.ACTIVE
        themes.isEnabled = featuresEnabled
        schedule.isEnabled = featuresEnabled
        backup.isEnabled = featuresEnabled
        delayed.isEnabled = featuresEnabled
        night.isEnabled = featuresEnabled
    }

    override fun initiatePurchaseFlow(billingManager: BillingManager, sku: String) {
        // #region agent log
        com.charles.messenger.util.DebugLogger.log(
            location = "PlusActivity.kt:209",
            message = "initiatePurchaseFlow called",
            data = mapOf("sku" to sku),
            hypothesisId = "H1"
        )
        // #endregion
        GlobalScope.launch(Dispatchers.Main) {
            try {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "PlusActivity.kt:212",
                    message = "About to call billingManager.initiatePurchaseFlow",
                    hypothesisId = "H1"
                )
                // #endregion
                billingManager.initiatePurchaseFlow(this@PlusActivity, sku)
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "PlusActivity.kt:212",
                    message = "billingManager.initiatePurchaseFlow completed",
                    hypothesisId = "H1"
                )
                // #endregion
            } catch (e: IllegalStateException) {
                // Billing unavailable or timeout
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "PlusActivity.kt:214",
                    message = "Billing exception",
                    data = mapOf("error" to e.message, "errorType" to e.javaClass.simpleName),
                    hypothesisId = "H1"
                )
                // #endregion
                Timber.w("Billing error: ${e.message}")
                makeToast(R.string.qksms_plus_error_billing_unavailable)
            } catch (e: Exception) {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "PlusActivity.kt:214",
                    message = "Exception in initiatePurchaseFlow",
                    data = mapOf("error" to e.message, "errorType" to e.javaClass.simpleName),
                    hypothesisId = "H1"
                )
                // #endregion
                Timber.w(e)
                makeToast(R.string.qksms_plus_error)
            }
        }
    }

}