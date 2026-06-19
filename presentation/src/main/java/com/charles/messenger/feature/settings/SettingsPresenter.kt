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
package com.charles.messenger.feature.settings

import android.content.Context
import com.charles.messenger.R
import com.charles.messenger.common.Navigator
import com.charles.messenger.common.base.QkPresenter
import com.charles.messenger.common.util.Colors
import com.charles.messenger.common.util.DateFormatter
import com.charles.messenger.common.util.extensions.makeToast
import com.charles.messenger.interactor.DeleteOldMessages
import com.charles.messenger.interactor.SyncMessages
import com.charles.messenger.manager.AnalyticsManager
import com.charles.messenger.manager.BillingManager
import com.charles.messenger.repository.MessageRepository
import com.charles.messenger.repository.SyncRepository
import com.charles.messenger.service.AutoDeleteService
import com.charles.messenger.util.NightModeManager
import com.charles.messenger.util.Preferences
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SettingsPresenter @Inject constructor(
    colors: Colors,
    syncRepo: SyncRepository,
    private val analytics: AnalyticsManager,
    private val context: Context,
    private val billingManager: BillingManager,
    private val dateFormatter: DateFormatter,
    private val deleteOldMessages: DeleteOldMessages,
    private val messageRepo: MessageRepository,
    private val navigator: Navigator,
    private val nightModeManager: NightModeManager,
    private val prefs: Preferences,
    private val syncMessages: SyncMessages
) : QkPresenter<SettingsView, SettingsState>(SettingsState(
        nightModeId = prefs.nightMode.get()
)) {

    init {
        disposables += colors.themeObservable()
                .subscribe { theme -> newState { copy(theme = theme.theme) } }

        val nightModeLabels = context.resources.getStringArray(R.array.night_modes)
        disposables += prefs.nightMode.asObservable()
                .subscribe { nightMode ->
                    newState { copy(nightModeSummary = nightModeLabels[nightMode], nightModeId = nightMode) }
                }

        disposables += prefs.nightStart.asObservable()
                .map { time -> nightModeManager.parseTime(time) }
                .map { calendar -> calendar.timeInMillis }
                .map { millis -> dateFormatter.getTimestamp(millis) }
                .subscribe { nightStart -> newState { copy(nightStart = nightStart) } }

        disposables += prefs.nightEnd.asObservable()
                .map { time -> nightModeManager.parseTime(time) }
                .map { calendar -> calendar.timeInMillis }
                .map { millis -> dateFormatter.getTimestamp(millis) }
                .subscribe { nightEnd -> newState { copy(nightEnd = nightEnd) } }

        disposables += prefs.black.asObservable()
                .subscribe { black -> newState { copy(black = black) } }

        disposables += prefs.notifications().asObservable()
                .subscribe { enabled -> newState { copy(notificationsEnabled = enabled) } }

        disposables += prefs.autoEmoji.asObservable()
                .subscribe { enabled -> 
                    newState { copy(autoEmojiEnabled = enabled) }
                }

        val delayedSendingLabels = context.resources.getStringArray(R.array.delayed_sending_labels)
        disposables += prefs.sendDelay.asObservable()
                .subscribe { id -> newState { copy(sendDelaySummary = delayedSendingLabels[id], sendDelayId = id) } }

        disposables += prefs.delivery.asObservable()
                .subscribe { enabled -> 
                    newState { copy(deliveryEnabled = enabled) }
                }

        disposables += prefs.signature.asObservable()
                .subscribe { signature -> newState { copy(signature = signature) } }

        val textSizeLabels = context.resources.getStringArray(R.array.text_sizes)
        disposables += prefs.textSize.asObservable()
                .subscribe { textSize ->
                    newState { copy(textSizeSummary = textSizeLabels[textSize], textSizeId = textSize) }
                }

        disposables += prefs.autoColor.asObservable()
                .subscribe { autoColor -> 
                    newState { copy(autoColor = autoColor) }
                }

        disposables += prefs.systemFont.asObservable()
                .subscribe { enabled -> newState { copy(systemFontEnabled = enabled) } }

        disposables += prefs.unicode.asObservable()
                .subscribe { enabled -> newState { copy(stripUnicodeEnabled = enabled) } }

        disposables += prefs.mobileOnly.asObservable()
                .subscribe { enabled -> newState { copy(mobileOnly = enabled) } }

        disposables += prefs.autoDelete.asObservable()
                .subscribe { autoDelete -> newState { copy(autoDelete = autoDelete) } }

        disposables += prefs.longAsMms.asObservable()
                .subscribe { enabled -> newState { copy(longAsMms = enabled) } }

        val mmsSizeLabels = context.resources.getStringArray(R.array.mms_sizes)
        val mmsSizeIds = context.resources.getIntArray(R.array.mms_sizes_ids)
        disposables += prefs.mmsSize.asObservable()
                .subscribe { maxMmsSize ->
                    val index = mmsSizeIds.indexOf(maxMmsSize)
                    newState { copy(maxMmsSizeSummary = mmsSizeLabels[index], maxMmsSizeId = maxMmsSize) }
                }

        disposables += syncRepo.syncProgress
                .sample(16, TimeUnit.MILLISECONDS)
                .distinctUntilChanged()
                .subscribe { syncProgress -> newState { copy(syncProgress = syncProgress) } }

        disposables += syncMessages
        
        // Update trial status
        disposables += billingManager.trialStatus
                .subscribe { trialState -> newState { copy(trialState = trialState) } }
        
        disposables += billingManager.trialDaysRemaining
                .subscribe { days -> newState { copy(trialDaysRemaining = days) } }
        
        disposables += billingManager.upgradeStatus
                .subscribe { upgraded -> newState { copy(upgraded = upgraded) } }
    }

    override fun bindIntents(view: SettingsView) {
        // Keep the state subscription alive for the lifetime of the presenter
        disposables += state
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { state ->
                    view.render(state)
                }

        disposables += view.preferenceClicks()
                .subscribe(
                    { preference ->
                        // #region agent log
                        com.charles.messenger.util.DebugLogger.log(
                            location = "SettingsPresenter.kt:171",
                            message = "Preference click received",
                            data = mapOf("preferenceId" to preference.id.toString()),
                            hypothesisId = "H3"
                        )
                        // #endregion
                        try {
                            // #region agent log
                            com.charles.messenger.util.DebugLogger.log(
                                location = "SettingsPresenter.kt:180",
                                message = "Before getResourceName call",
                                data = mapOf(
                                    "contextNull" to (context == null),
                                    "preferenceId" to preference.id.toString()
                                ),
                                hypothesisId = "H3"
                            )
                            // #endregion
                            val resourceName = try {
                                context.resources.getResourceName(preference.id)
                            } catch (e: Exception) {
                                "unknown_resource"
                            }
                            Timber.v("Preference click: $resourceName")

                            // #region agent log
                            com.charles.messenger.util.DebugLogger.log(
                                location = "SettingsPresenter.kt:183",
                                message = "Before when statement",
                                data = mapOf(
                                    "viewNull" to (view == null),
                                    "preferenceId" to preference.id.toString()
                                ),
                                hypothesisId = "H3"
                            )
                            // #endregion
                            when (preference.id) {
                        R.id.theme -> view.showThemePicker()

                        R.id.night -> view.showNightModeDialog()

                        R.id.nightStart -> {
                            val date = nightModeManager.parseTime(prefs.nightStart.get())
                            view.showStartTimePicker(date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE))
                        }

                        R.id.nightEnd -> {
                            val date = nightModeManager.parseTime(prefs.nightEnd.get())
                            view.showEndTimePicker(date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE))
                        }

                        R.id.black -> {
                            prefs.black.set(!prefs.black.get())
                        }

                        R.id.autoEmoji -> {
                            prefs.autoEmoji.set(!prefs.autoEmoji.get())
                        }

                        R.id.notifications -> navigator.showNotificationSettings()

                        R.id.swipeActions -> view.showSwipeActions()

                        R.id.delayed -> view.showDelayDurationDialog()

                        R.id.delivery -> {
                            prefs.delivery.set(!prefs.delivery.get())
                        }

                        R.id.signature -> view.showSignatureDialog(prefs.signature.get())

                        R.id.textSize -> view.showTextSizePicker()

                        R.id.autoColor -> {
                            analytics.setUserProperty("Preference: Auto Color", !prefs.autoColor.get())
                            prefs.autoColor.set(!prefs.autoColor.get())
                        }

                        R.id.systemFont -> prefs.systemFont.set(!prefs.systemFont.get())

                        R.id.unicode -> prefs.unicode.set(!prefs.unicode.get())

                        R.id.mobileOnly -> prefs.mobileOnly.set(!prefs.mobileOnly.get())

                        R.id.autoDelete -> view.showAutoDeleteDialog(prefs.autoDelete.get())

                        R.id.longAsMms -> prefs.longAsMms.set(!prefs.longAsMms.get())

                        R.id.mmsSize -> view.showMmsSizePicker()

                        R.id.sync -> syncMessages.execute(Unit)

                        R.id.trial -> navigator.showQksmsPlusActivity("settings")

                        R.id.about -> view.showAbout()

                        R.id.aiSettings -> {
                            // #region agent log
                            com.charles.messenger.util.DebugLogger.log(
                                location = "SettingsPresenter.kt:243",
                                message = "About to call showAiSettings",
                                data = mapOf("viewNull" to (view == null)),
                                hypothesisId = "H3"
                            )
                            // #endregion
                            view?.showAiSettings() ?: run {
                                Timber.e("View is null when trying to show AI Settings")
                            }
                        }

                        R.id.webSyncSettings -> view.showWebSyncSettings()

                        R.id.feedback -> view.showFeedback()
                            }
                            // #region agent log
                            com.charles.messenger.util.DebugLogger.log(
                                location = "SettingsPresenter.kt:236",
                                message = "Preference click handled successfully",
                                hypothesisId = "H3"
                            )
                            // #endregion
                        } catch (e: Exception) {
                            // #region agent log
                            com.charles.messenger.util.DebugLogger.log(
                                location = "SettingsPresenter.kt:238",
                                message = "Error handling preference click",
                                data = mapOf("error" to e.message, "errorType" to e.javaClass.simpleName),
                                hypothesisId = "H3"
                            )
                            // #endregion
                            Timber.e(e, "Error handling preference click")
                        }
                    },
                    { error ->
                        // #region agent log
                        com.charles.messenger.util.DebugLogger.log(
                            location = "SettingsPresenter.kt:247",
                            message = "Error in preferenceClicks stream",
                            data = mapOf("error" to error.message, "errorType" to error.javaClass.simpleName),
                            hypothesisId = "H3"
                        )
                        // #endregion
                        Timber.e(error, "Error in preferenceClicks stream")
                    }
                )

        disposables += view.aboutLongClicks()
                .map { !prefs.logging.get() }
                .doOnNext { enabled -> prefs.logging.set(enabled) }
                .subscribe { enabled ->
                    context.makeToast(when (enabled) {
                        true -> R.string.settings_logging_enabled
                        false -> R.string.settings_logging_disabled
                    })
                }

        disposables += view.nightModeSelected()
                .withLatestFrom(billingManager.upgradeStatus) { mode, upgraded ->
                    if (!upgraded && mode == Preferences.NIGHT_MODE_AUTO) {
                        view.showQksmsPlusSnackbar()
                    } else {
                        nightModeManager.updateNightMode(mode)
                    }
                }
                .subscribe()

        disposables += view.viewQksmsPlusClicks()
                .subscribe { navigator.showQksmsPlusActivity("settings_night") }

        disposables += view.nightStartSelected()
                .subscribe { nightModeManager.setNightStart(it.first, it.second) }

        disposables += view.nightEndSelected()
                .subscribe { nightModeManager.setNightEnd(it.first, it.second) }

        disposables += view.textSizeSelected()
                .subscribe(prefs.textSize::set)

        disposables += view.sendDelaySelected()
                .withLatestFrom(billingManager.upgradeStatus) { duration, upgraded ->
                    if (!upgraded && duration != 0) {
                        view.showQksmsPlusSnackbar()
                    } else {
                        prefs.sendDelay.set(duration)
                    }
                }
                .subscribe()

        disposables += view.signatureChanged()
                .doOnNext(prefs.signature::set)
                .subscribe()

        disposables += view.autoDeleteChanged()
                .observeOn(Schedulers.io())
                .filter { maxAge ->
                    if (maxAge == 0) {
                        return@filter true
                    }

                    val counts = messageRepo.getOldMessageCounts(maxAge)
                    if (counts.values.sum() == 0) {
                        return@filter true
                    }

                    runBlocking { view.showAutoDeleteWarningDialog(counts.values.sum()) }
                }
                .doOnNext { maxAge ->
                    when (maxAge == 0) {
                        true -> AutoDeleteService.cancelJob(context)
                        false -> {
                            AutoDeleteService.scheduleJob(context)
                            deleteOldMessages.execute(Unit)
                        }
                    }
                }
                .doOnNext(prefs.autoDelete::set)
                .subscribe()

        disposables += view.mmsSizeSelected()
                .subscribe(prefs.mmsSize::set)
    }

}
