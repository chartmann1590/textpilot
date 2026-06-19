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

import android.animation.ObjectAnimator
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.RouterTransaction
import com.google.android.material.snackbar.Snackbar
import com.charles.messenger.BuildConfig
import com.charles.messenger.R
import com.charles.messenger.manager.BillingManager
import com.charles.messenger.common.MenuItem
import com.charles.messenger.common.QkChangeHandler
import com.charles.messenger.common.QkDialog
import com.charles.messenger.common.base.QkController
import com.charles.messenger.common.util.Colors
import com.charles.messenger.common.util.extensions.animateLayoutChanges
import com.charles.messenger.common.util.extensions.setBackgroundTint
import com.charles.messenger.common.util.extensions.setVisible
import com.charles.messenger.common.widget.PreferenceView
import com.charles.messenger.common.widget.QkSwitch
import com.charles.messenger.common.widget.TextInputDialog
import com.charles.messenger.feature.settings.about.AboutController
import com.charles.messenger.feature.settings.autodelete.AutoDeleteDialog
import com.charles.messenger.feature.settings.feedback.FeedbackController
import com.charles.messenger.feature.settings.swipe.SwipeActionsController
import com.charles.messenger.feature.themepicker.ThemePickerController
import com.charles.messenger.injection.appComponent
import com.charles.messenger.repository.SyncRepository
import com.charles.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.Observable
import timber.log.Timber
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

class SettingsController : QkController<SettingsView, SettingsState, SettingsPresenter>(), SettingsView {

    @Inject lateinit var context: Context
    @Inject lateinit var colors: Colors
    @Inject lateinit var nightModeDialog: QkDialog
    @Inject lateinit var textSizeDialog: QkDialog
    @Inject lateinit var sendDelayDialog: QkDialog
    @Inject lateinit var mmsSizeDialog: QkDialog

    @Inject override lateinit var presenter: SettingsPresenter

    private lateinit var preferences: LinearLayout
    private lateinit var contentView: View
    private lateinit var theme: PreferenceView
    private lateinit var themePreview: View
    private lateinit var night: PreferenceView
    private lateinit var nightStart: PreferenceView
    private lateinit var nightEnd: PreferenceView
    private lateinit var black: PreferenceView
    private lateinit var autoEmoji: PreferenceView
    private lateinit var delayed: PreferenceView
    private lateinit var delivery: PreferenceView
    private lateinit var signature: PreferenceView
    private lateinit var textSize: PreferenceView
    private lateinit var autoColor: PreferenceView
    private lateinit var systemFont: PreferenceView
    private lateinit var unicode: PreferenceView
    private lateinit var mobileOnly: PreferenceView
    private lateinit var autoDelete: PreferenceView
    private lateinit var longAsMms: PreferenceView
    private lateinit var mmsSize: PreferenceView
    private lateinit var syncingProgress: ProgressBar
    private lateinit var trial: PreferenceView
    private lateinit var about: PreferenceView

    private val signatureDialog: TextInputDialog by lazy {
        TextInputDialog(activity!!, context.getString(R.string.settings_signature_title), signatureSubject::onNext)
    }
    private val autoDeleteDialog: AutoDeleteDialog by lazy {
        AutoDeleteDialog(activity!!, autoDeleteSubject::onNext)
    }

    private val viewQksmsPlusSubject: Subject<Unit> = PublishSubject.create()
    private val startTimeSelectedSubject: Subject<Pair<Int, Int>> = PublishSubject.create()
    private val endTimeSelectedSubject: Subject<Pair<Int, Int>> = PublishSubject.create()
    private val signatureSubject: Subject<String> = PublishSubject.create()
    private val autoDeleteSubject: Subject<Int> = PublishSubject.create()
    private val preferenceClickSubject: Subject<PreferenceView> = PublishSubject.create()
    private val aboutLongClickSubject: Subject<Unit> = PublishSubject.create()

    private val progressAnimator by lazy { ObjectAnimator.ofInt(syncingProgress, "progress", 0, 0) }

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.settings_controller

        colors.themeObservable()
                .autoDisposable(scope())
                .subscribe { activity?.recreate() }
    }

    override fun onViewCreated(view: View) {
        // Initialize all views - they should all exist in the layout
        preferences = view.findViewById(R.id.preferences)!!
        contentView = view.findViewById(R.id.contentView)!!
        theme = view.findViewById(R.id.theme)!!
        themePreview = theme.findViewById(R.id.themePreview)!!
        night = view.findViewById(R.id.night)!!
        nightStart = view.findViewById(R.id.nightStart)!!
        nightEnd = view.findViewById(R.id.nightEnd)!!
        black = view.findViewById(R.id.black)!!
        autoEmoji = view.findViewById(R.id.autoEmoji)!!
        delayed = view.findViewById(R.id.delayed)!!
        delivery = view.findViewById(R.id.delivery)!!
        signature = view.findViewById(R.id.signature)!!
        textSize = view.findViewById(R.id.textSize)!!
        autoColor = view.findViewById(R.id.autoColor)!!
        systemFont = view.findViewById(R.id.systemFont)!!
        unicode = view.findViewById(R.id.unicode)!!
        mobileOnly = view.findViewById(R.id.mobileOnly)!!
        autoDelete = view.findViewById(R.id.autoDelete)!!
        longAsMms = view.findViewById(R.id.longAsMms)!!
        mmsSize = view.findViewById(R.id.mmsSize)!!
        syncingProgress = view.findViewById(R.id.syncingProgress)!!
        trial = view.findViewById(R.id.trial)!!
        about = view.findViewById(R.id.about)!!

        (0 until preferences.childCount)
                .mapNotNull { index -> preferences.getChildAt(index) as? PreferenceView }
                .forEach { preference ->
                    // Make switches non-clickable so clicks pass through to parent
                    preference.widget.findViewById<com.charles.messenger.common.widget.QkSwitch>(R.id.checkbox)?.let { switch ->
                        switch.isClickable = false
                        switch.isFocusable = false
                    }
                    // Make theme widget non-clickable so clicks pass through to parent PreferenceView
                    if (preference.id == R.id.theme) {
                        preference.widget?.isClickable = false
                        preference.widget?.isFocusable = false
                        preference.widget?.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        // Also make the themePreview view non-clickable
                        preference.widget?.findViewById<android.view.View>(R.id.themePreview)?.let { preview ->
                            preview.isClickable = false
                            preview.isFocusable = false
                        }
                    }
                    preference.setOnClickListener { 
                        preferenceClickSubject.onNext(preference)
                    }
                }
        about.setOnLongClickListener {
            aboutLongClickSubject.onNext(Unit)
            true
        }

        // preferences.postDelayed({ preferences.animateLayoutChanges = true }, 100)

        // Always show all night mode options (System, Disabled, Always on, Automatic)
        // AppCompat supports MODE_NIGHT_FOLLOW_SYSTEM on all API levels
        nightModeDialog.adapter.setData(R.array.night_modes)
        textSizeDialog.adapter.setData(R.array.text_sizes)
        sendDelayDialog.adapter.setData(R.array.delayed_sending_labels)
        mmsSizeDialog.adapter.setData(R.array.mms_sizes, R.array.mms_sizes_ids)

        about.summary = context.getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        
        // Bind intents AFTER all views are initialized
        presenter.bindIntents(this)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        setTitle(R.string.title_settings)
        showBackButton(true)
    }

    override fun preferenceClicks(): Observable<PreferenceView> {
        if (!::preferences.isInitialized) {
            return Observable.empty()
        }
        return preferenceClickSubject
    }

    override fun aboutLongClicks(): Observable<*> {
        return if (::about.isInitialized) aboutLongClickSubject else Observable.empty<Any>()
    }

    override fun viewQksmsPlusClicks(): Observable<*> = viewQksmsPlusSubject

    override fun nightModeSelected(): Observable<Int> = nightModeDialog.adapter.menuItemClicks

    override fun nightStartSelected(): Observable<Pair<Int, Int>> = startTimeSelectedSubject

    override fun nightEndSelected(): Observable<Pair<Int, Int>> = endTimeSelectedSubject

    override fun textSizeSelected(): Observable<Int> = textSizeDialog.adapter.menuItemClicks

    override fun sendDelaySelected(): Observable<Int> = sendDelayDialog.adapter.menuItemClicks

    override fun signatureChanged(): Observable<String> = signatureSubject

    override fun autoDeleteChanged(): Observable<Int> = autoDeleteSubject

    override fun mmsSizeSelected(): Observable<Int> = mmsSizeDialog.adapter.menuItemClicks

    override fun render(state: SettingsState) {
        // Check if all views are initialized before using them
        if (!::themePreview.isInitialized || !::night.isInitialized || !::nightStart.isInitialized ||
            !::nightEnd.isInitialized || !::black.isInitialized || !::autoEmoji.isInitialized ||
            !::delayed.isInitialized || !::delivery.isInitialized || !::signature.isInitialized ||
            !::textSize.isInitialized || !::autoColor.isInitialized || !::systemFont.isInitialized ||
            !::unicode.isInitialized || !::mobileOnly.isInitialized || !::autoDelete.isInitialized ||
            !::longAsMms.isInitialized || !::mmsSize.isInitialized || !::syncingProgress.isInitialized ||
            !::trial.isInitialized) {
            return
        }
            
        themePreview.setBackgroundTint(state.theme)
        night.summary = state.nightModeSummary
        nightModeDialog.adapter.selectedItem = state.nightModeId
        nightStart.setVisible(state.nightModeId == Preferences.NIGHT_MODE_AUTO)
        nightStart.summary = state.nightStart
        nightEnd.setVisible(state.nightModeId == Preferences.NIGHT_MODE_AUTO)
        nightEnd.summary = state.nightEnd

        black.setVisible(state.nightModeId != Preferences.NIGHT_MODE_OFF)
        black.findViewById<QkSwitch>(R.id.checkbox)?.isChecked = state.black

        autoEmoji.findViewById<QkSwitch>(R.id.checkbox)?.isChecked = state.autoEmojiEnabled

        delayed.summary = state.sendDelaySummary
        sendDelayDialog.adapter.selectedItem = state.sendDelayId

        delivery.findViewById<QkSwitch>(R.id.checkbox)?.isChecked = state.deliveryEnabled

        signature.summary = state.signature.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.settings_signature_summary)

        textSize.summary = state.textSizeSummary
        textSizeDialog.adapter.selectedItem = state.textSizeId

        autoColor.findViewById<QkSwitch>(R.id.checkbox)?.isChecked = state.autoColor

        systemFont.findViewById<QkSwitch>(R.id.checkbox)?.isChecked = state.systemFontEnabled

        unicode.findViewById<QkSwitch>(R.id.checkbox)?.isChecked = state.stripUnicodeEnabled
        mobileOnly.findViewById<QkSwitch>(R.id.checkbox)?.isChecked = state.mobileOnly

        autoDelete.summary = when (state.autoDelete) {
            0 -> context.getString(R.string.settings_auto_delete_never)
            else -> context.resources.getQuantityString(
                    R.plurals.settings_auto_delete_summary, state.autoDelete, state.autoDelete)
        }

        longAsMms.findViewById<QkSwitch>(R.id.checkbox)?.isChecked = state.longAsMms

        mmsSize.summary = state.maxMmsSizeSummary
        mmsSizeDialog.adapter.selectedItem = state.maxMmsSizeId

        when (state.syncProgress) {
            is SyncRepository.SyncProgress.Idle -> syncingProgress.isVisible = false

            is SyncRepository.SyncProgress.Running -> {
                syncingProgress.isVisible = true
                syncingProgress.max = state.syncProgress.max
                progressAnimator.apply { setIntValues(syncingProgress.progress, state.syncProgress.progress) }.start()
                syncingProgress.isIndeterminate = state.syncProgress.indeterminate
            }
        }

        // Show/hide and update trial preference
        val fdroid = BuildConfig.FLAVOR == "noAnalytics"
        trial.isVisible = !fdroid && !state.upgraded
        when (state.trialState) {
            com.charles.messenger.manager.BillingManager.TrialState.ACTIVE -> {
                trial.summary = context.getString(R.string.settings_trial_summary_active, state.trialDaysRemaining)
            }
            com.charles.messenger.manager.BillingManager.TrialState.EXPIRED -> {
                trial.summary = context.getString(R.string.settings_trial_summary_expired)
            }
            else -> {
                trial.summary = context.getString(R.string.settings_trial_summary_not_started)
            }
        }
    }

    override fun showQksmsPlusSnackbar() {
        view?.run {
            Snackbar.make(contentView, R.string.toast_qksms_plus, Snackbar.LENGTH_LONG).run {
                setAction(R.string.button_more) { viewQksmsPlusSubject.onNext(Unit) }
                setActionTextColor(colors.theme().theme)
                show()
            }
        }
    }

    // TODO change this to a PopupWindow
    override fun showNightModeDialog() = nightModeDialog.show(activity!!)

    override fun showStartTimePicker(hour: Int, minute: Int) {
        TimePickerDialog(activity, { _, newHour, newMinute ->
            startTimeSelectedSubject.onNext(Pair(newHour, newMinute))
        }, hour, minute, DateFormat.is24HourFormat(activity)).show()
    }

    override fun showEndTimePicker(hour: Int, minute: Int) {
        TimePickerDialog(activity, { _, newHour, newMinute ->
            endTimeSelectedSubject.onNext(Pair(newHour, newMinute))
        }, hour, minute, DateFormat.is24HourFormat(activity)).show()
    }

    override fun showTextSizePicker() = textSizeDialog.show(activity!!)

    override fun showDelayDurationDialog() = sendDelayDialog.show(activity!!)

    override fun showSignatureDialog(signature: String) = signatureDialog.setText(signature).show()

    override fun showAutoDeleteDialog(days: Int) = autoDeleteDialog.setExpiry(days).show()

    override suspend fun showAutoDeleteWarningDialog(messages: Int): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { cont ->
            AlertDialog.Builder(activity!!)
                    .setTitle(R.string.settings_auto_delete_warning)
                    .setMessage(context.resources.getString(R.string.settings_auto_delete_warning_message, messages))
                    .setOnCancelListener { cont.resume(false) }
                    .setNegativeButton(R.string.button_cancel) { _, _ -> cont.resume(false) }
                    .setPositiveButton(R.string.button_yes) { _, _ -> cont.resume(true) }
                    .show()
        }
    }

    override fun showMmsSizePicker() = mmsSizeDialog.show(activity!!)

    override fun showSwipeActions() {
        router.pushController(RouterTransaction.with(SwipeActionsController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showThemePicker() {
        router.pushController(RouterTransaction.with(ThemePickerController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showAbout() {
        router.pushController(RouterTransaction.with(AboutController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showAiSettings() {
        try {
            router?.pushController(RouterTransaction.with(com.charles.messenger.feature.settings.ai.AiSettingsController())
                    .pushChangeHandler(QkChangeHandler())
                    .popChangeHandler(QkChangeHandler()))
        } catch (e: Exception) {
            Timber.e(e, "Error showing AI Settings")
        }
    }

    override fun showWebSyncSettings() {
        router?.pushController(RouterTransaction.with(com.charles.messenger.feature.settings.websync.WebSyncSettingsController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showFeedback() {
        router?.pushController(RouterTransaction.with(FeedbackController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

}
