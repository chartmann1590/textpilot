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
package com.charles.messenger.common

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import androidx.core.provider.FontRequest
import androidx.emoji.text.EmojiCompat
import androidx.emoji.text.FontRequestEmojiCompatConfig
import com.charles.messenger.R
import com.charles.messenger.common.util.BillingTimeoutException
import com.charles.messenger.common.util.BillingUnavailableException
import com.charles.messenger.common.util.CrashlyticsTree
import com.charles.messenger.common.util.FileLoggingTree
import com.charles.messenger.injection.AppComponentManager
import com.charles.messenger.injection.appComponent
import com.charles.messenger.manager.AnalyticsManager
import com.charles.messenger.manager.BillingManager
import com.charles.messenger.manager.ReferralManager
import com.charles.messenger.migration.QkMigration
import com.charles.messenger.migration.QkRealmMigration
import com.charles.messenger.util.DebugLogger
import com.charles.messenger.util.NightModeManager
import com.uber.rxdogtag.RxDogTag
import com.uber.rxdogtag.autodispose.AutoDisposeConfigurer
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.HasBroadcastReceiverInjector
import dagger.android.HasServiceInjector
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class QKApplication : Application(), HasActivityInjector, HasBroadcastReceiverInjector, HasServiceInjector {

    /**
     * Inject these so that they are forced to initialize
     */
    @Suppress("unused")
    @Inject lateinit var analyticsManager: AnalyticsManager
    @Suppress("unused")
    @Inject lateinit var qkMigration: QkMigration

    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var dispatchingActivityInjector: DispatchingAndroidInjector<Activity>
    @Inject lateinit var dispatchingBroadcastReceiverInjector: DispatchingAndroidInjector<BroadcastReceiver>
    @Inject lateinit var dispatchingServiceInjector: DispatchingAndroidInjector<Service>
    @Inject lateinit var fileLoggingTree: FileLoggingTree
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var realmMigration: QkRealmMigration
    @Inject lateinit var referralManager: ReferralManager

    override fun onCreate() {
        super.onCreate()

        // Initialize debug logger
        DebugLogger.init(this)

        AppComponentManager.init(this)
        appComponent.inject(this)

        Realm.init(this)
        Realm.setDefaultConfiguration(RealmConfiguration.Builder()
                .compactOnLaunch()
                .migration(realmMigration)
                .schemaVersion(QkRealmMigration.SchemaVersion)
                .build())

        qkMigration.performMigration()

        GlobalScope.launch(Dispatchers.IO) {
            try {
                referralManager.trackReferrer()
            } catch (e: Exception) {
                Timber.w(e, "Error tracking referrer")
            }
            try {
                billingManager.checkForPurchases()
            } catch (e: BillingTimeoutException) {
                // Expected/recoverable on some devices (slow Play services bind, no
                // Play Store account signed in, etc.) - already handled gracefully by
                // continuing without purchase state. Not worth a Crashlytics non-fatal.
                Timber.i("Billing unavailable while checking for purchases: ${e.message}")
            } catch (e: BillingUnavailableException) {
                Timber.i("Billing unavailable while checking for purchases: ${e.message}")
            } catch (e: Exception) {
                Timber.w(e, "Error checking for purchases")
            }
            try {
                billingManager.queryProducts()
            } catch (e: BillingTimeoutException) {
                Timber.i("Billing unavailable while querying products: ${e.message}")
            } catch (e: BillingUnavailableException) {
                Timber.i("Billing unavailable while querying products: ${e.message}")
            } catch (e: Exception) {
                Timber.w(e, "Error querying products")
            }
        }

        nightModeManager.updateCurrentTheme()

        val fontRequest = FontRequest(
                "com.google.android.gms.fonts",
                "com.google.android.gms",
                "Noto Color Emoji Compat",
                R.array.com_google_android_gms_fonts_certs)

        EmojiCompat.init(FontRequestEmojiCompatConfig(this, fontRequest))

        Timber.plant(Timber.DebugTree(), CrashlyticsTree(), fileLoggingTree)

        RxDogTag.builder()
                .configureWith(AutoDisposeConfigurer::configure)
                .install()
    }

    override fun activityInjector(): AndroidInjector<Activity> {
        return dispatchingActivityInjector
    }

    override fun broadcastReceiverInjector(): AndroidInjector<BroadcastReceiver> {
        return dispatchingBroadcastReceiverInjector
    }

    override fun serviceInjector(): AndroidInjector<Service> {
        return dispatchingServiceInjector
    }

}