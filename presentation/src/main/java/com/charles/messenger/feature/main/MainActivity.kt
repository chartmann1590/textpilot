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
package com.charles.messenger.feature.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewStub
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.charles.messenger.R
import com.charles.messenger.common.Navigator
import com.charles.messenger.common.androidxcompat.drawerOpen
import com.charles.messenger.common.base.QkThemedActivity
import com.charles.messenger.common.util.extensions.autoScrollToStart
import com.charles.messenger.common.util.extensions.dismissKeyboard
import com.charles.messenger.common.util.extensions.resolveThemeColor
import com.charles.messenger.common.util.extensions.scrapViews
import com.charles.messenger.common.util.extensions.setBackgroundTint
import com.charles.messenger.common.util.extensions.setTint
import com.charles.messenger.common.util.extensions.setVisible
import com.charles.messenger.feature.blocking.BlockingDialog
import com.charles.messenger.feature.changelog.ChangelogDialog
import com.charles.messenger.feature.conversations.ConversationItemTouchCallback
import com.charles.messenger.feature.conversations.ConversationsAdapter
import com.charles.messenger.manager.ChangelogManager
import com.charles.messenger.repository.SyncRepository
import com.charles.messenger.common.util.AnalyticsInitializer
import com.charles.messenger.common.util.InterstitialAdManager
import com.charles.messenger.common.util.NativeAdController
import com.jakewharton.rxbinding2.widget.textChanges
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import com.charles.messenger.databinding.MainActivityBinding
import com.charles.messenger.databinding.DrawerViewBinding
import com.charles.messenger.databinding.MainPermissionHintBinding
import com.charles.messenger.databinding.MainSyncingBinding
import javax.inject.Inject
import kotlin.math.min

class MainActivity : QkThemedActivity(), MainView {

    @Inject lateinit var interstitialAdManager: InterstitialAdManager
    @Inject lateinit var analyticsInitializer: AnalyticsInitializer
    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var drawerBadgesExperiment: DrawerBadgesExperiment
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var binding: MainActivityBinding
    private lateinit var drawerBinding: DrawerViewBinding
    private var snackbarBinding: MainPermissionHintBinding? = null
    private var syncingBinding: MainSyncingBinding? = null

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { binding.toolbarSearch.textChanges() }
    override val composeIntent by lazy { binding.compose.clicks() }
    override val drawerOpenIntent: Observable<Boolean> by lazy {
        binding.drawerLayout
                .drawerOpen(Gravity.START)
                .doOnNext { dismissKeyboard() }
    }
    override val homeIntent: Subject<Unit> = PublishSubject.create()
    override val navigationIntent: Observable<NavItem> by lazy {
        Observable.merge(listOf(
                backPressedSubject,
                drawerBinding.inbox.clicks().map { NavItem.INBOX },
                drawerBinding.archived.clicks().map { NavItem.ARCHIVED },
                drawerBinding.backup.clicks().map { NavItem.BACKUP },
                drawerBinding.scheduled.clicks().map { NavItem.SCHEDULED },
                drawerBinding.blocking.clicks().map { NavItem.BLOCKING },
                drawerBinding.rewards.clicks().map { NavItem.REWARDS },
                drawerBinding.settings.clicks().map { NavItem.SETTINGS },
                drawerBinding.plus.clicks().map { NavItem.PLUS },
                drawerBinding.help.clicks().map { NavItem.HELP },
                drawerBinding.invite.clicks().map { NavItem.INVITE }))
    }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val plusBannerIntent by lazy { drawerBinding.plusBanner.clicks() }
    override val dismissRatingIntent by lazy { drawerBinding.rateDismiss.clicks() }
    override val rateIntent by lazy { drawerBinding.rateOkay.clicks() }
    override val conversationsSelectedIntent by lazy { conversationsAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val changelogMoreIntent by lazy { changelogDialog.moreClicks }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[MainViewModel::class.java] }
    private val toggle by lazy { ActionBarDrawerToggle(this, binding.drawerLayout, binding.toolbar, R.string.main_drawer_open_cd, 0) }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
    private val progressAnimator by lazy { ObjectAnimator.ofInt(syncingBinding?.syncingProgress, "progress", 0, 0) }
    private val changelogDialog by lazy { ChangelogDialog(this) }
    private val backPressedSubject: Subject<NavItem> = PublishSubject.create()
    private lateinit var nativeAdController: NativeAdController
    private val nativeAdRetryHandler = Handler(Looper.getMainLooper())
    private var nativeAdRetryAttempt = 0
    private var shouldShowNativeAds = false
    private val maxNativeAdRetryAttempts = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        drawerBinding = DrawerViewBinding.bind(binding.drawer.root)
        nativeAdController = NativeAdController(this)
        
        // Window insets are handled by base class setupWindowInsets() in onPostCreate
        // MainActivity's DrawerLayout with mainContent is automatically handled
        
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)

        // Preload interstitial ad
        interstitialAdManager.loadAd(this)
        billingManager.upgradeStatus
            .distinctUntilChanged()
            .autoDisposable(scope())
            .subscribe { upgraded ->
                if (!upgraded) {
                    shouldShowNativeAds = true
                    loadNativeAd(force = true)
                } else {
                    shouldShowNativeAds = false
                    cancelNativeAdRetry()
                    conversationsAdapter.setInlineNativeAd(null)
                }
            }
        analyticsInitializer.init(this)

        (binding.snackbar as? ViewStub)?.setOnInflateListener { _, inflated ->
            snackbarBinding = MainPermissionHintBinding.bind(inflated)
            snackbarBinding?.snackbarButton?.clicks()
                    ?.autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
                    ?.subscribe(snackbarButtonIntent)
        }

        (binding.syncing as? ViewStub)?.setOnInflateListener { _, inflated ->
            syncingBinding = MainSyncingBinding.bind(inflated)
            syncingBinding?.syncingProgress?.progressTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
            syncingBinding?.syncingProgress?.indeterminateTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
        }

        toggle.syncState()
        binding.toolbar.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.onNext(Unit)
        }

        itemTouchCallback.adapter = conversationsAdapter
        conversationsAdapter.autoScrollToStart(binding.recyclerView)

        // Don't allow clicks to pass through the drawer layout
        binding.drawer.root.clicks().autoDisposable(scope()).subscribe()

        // Set the theme color tint to the recyclerView, progressbar, and FAB
        theme
                .autoDisposable(scope())
                .subscribe { theme ->
                    // Set the color for the drawer icons
                    val states = arrayOf(
                            intArrayOf(android.R.attr.state_activated),
                            intArrayOf(-android.R.attr.state_activated))

                    resolveThemeColor(android.R.attr.textColorSecondary)
                            .let { textSecondary -> ColorStateList(states, intArrayOf(theme.theme, textSecondary)) }
                            .let { tintList ->
                                drawerBinding.inboxIcon.imageTintList = tintList
                                drawerBinding.archivedIcon.imageTintList = tintList
                            }

                    // Miscellaneous views
                    listOf(drawerBinding.plusBadge1, drawerBinding.plusBadge2).forEach { badge ->
                        badge.setBackgroundTint(theme.theme)
                        badge.setTextColor(theme.textPrimary)
                    }
                    syncingBinding?.syncingProgress?.progressTintList = ColorStateList.valueOf(theme.theme)
                    syncingBinding?.syncingProgress?.indeterminateTintList = ColorStateList.valueOf(theme.theme)
                    drawerBinding.plusIcon.setTint(theme.theme)
                    drawerBinding.rateIcon.setTint(theme.theme)
                    binding.compose.setBackgroundTint(theme.theme)

                    // Set the FAB compose icon color
                    binding.compose.setTint(theme.textPrimary)
                }

        // These theme attributes don't apply themselves on API 21
        if (Build.VERSION.SDK_INT <= 22) {
            binding.toolbarSearch.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.run(onNewIntentIntent::onNext)
    }

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }

        binding.toolbarSearch.setVisible(state.page is Inbox && state.page.selected == 0 || state.page is Searching)
        binding.toolbarTitle.setVisible(binding.toolbarSearch.visibility != View.VISIBLE)

        binding.toolbar.menu.findItem(R.id.archive)?.isVisible = state.page is Inbox && selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.unarchive)?.isVisible = state.page is Archived && selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.delete)?.isVisible = selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.add)?.isVisible = addContact && selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.pin)?.isVisible = markPinned && selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.unpin)?.isVisible = !markPinned && selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.read)?.isVisible = markRead && selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.unread)?.isVisible = !markRead && selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.block)?.isVisible = selectedConversations != 0
        binding.toolbar.menu.findItem(R.id.upgrade)?.isVisible = !state.upgraded && selectedConversations == 0

        listOf(drawerBinding.plusBadge1, drawerBinding.plusBadge2).forEach { badge ->
            badge.isVisible = drawerBadgesExperiment.variant && !state.upgraded
        }
        drawerBinding.plus.isVisible = !state.upgraded
        // Show banner when not upgraded OR in trial mode
        drawerBinding.plusBanner.isVisible = !state.upgraded || state.trialState == com.charles.messenger.manager.BillingManager.TrialState.ACTIVE
        // Show rewards only when not upgraded and not in trial
        drawerBinding.rewards.isVisible = !state.upgraded && state.trialState != com.charles.messenger.manager.BillingManager.TrialState.ACTIVE

        // Update banner text based on trial state
        when (state.trialState) {
            com.charles.messenger.manager.BillingManager.TrialState.ACTIVE -> {
                drawerBinding.plusTitle.text = getString(R.string.drawer_trial_active_title)
                drawerBinding.plusSummary.text = getString(R.string.drawer_trial_active_summary, state.trialDaysRemaining)
            }
            com.charles.messenger.manager.BillingManager.TrialState.EXPIRED -> {
                drawerBinding.plusTitle.text = getString(R.string.drawer_trial_expired_title)
                drawerBinding.plusSummary.text = getString(R.string.drawer_trial_expired_summary)
            }
            else -> {
                drawerBinding.plusTitle.text = getString(R.string.drawer_plus_banner_title)
                drawerBinding.plusSummary.text = getString(R.string.drawer_plus_banner_summary)
            }
        }
        drawerBinding.rateLayout.setVisible(state.showRating)

        binding.compose.setVisible(state.page is Inbox || state.page is Archived)
        conversationsAdapter.emptyView = binding.empty.takeIf { state.page is Inbox || state.page is Archived }
        searchAdapter.emptyView = binding.empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(state.page.selected > 0)
                title = getString(R.string.main_title_selected, state.page.selected)
                if (binding.recyclerView.adapter !== conversationsAdapter) binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(binding.recyclerView)
                binding.empty.setText(R.string.inbox_empty_text)
            }

            is Searching -> {
                showBackButton(true)
                if (binding.recyclerView.adapter !== searchAdapter) binding.recyclerView.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                binding.empty.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                showBackButton(state.page.selected > 0)
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> getString(R.string.title_archived)
                }
                if (binding.recyclerView.adapter !== conversationsAdapter) binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                binding.empty.setText(R.string.archived_empty_text)
            }
        }

        drawerBinding.inbox.isActivated = state.page is Inbox
        drawerBinding.archived.isActivated = state.page is Archived

        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START) && !state.drawerOpen) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else if (!binding.drawerLayout.isDrawerVisible(GravityCompat.START) && state.drawerOpen) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                binding.syncing.isVisible = false
                binding.snackbar.isVisible = !state.defaultSms || !state.smsPermission || !state.contactPermission || !state.notificationPermission
            }

            is SyncRepository.SyncProgress.Running -> {
                binding.syncing.isVisible = true
                syncingBinding?.syncingProgress?.max = state.syncing.max
                syncingBinding?.syncingProgress?.progress?.let { progress ->
                    progressAnimator.apply { setIntValues(progress, state.syncing.progress) }.start()
                }
                syncingBinding?.syncingProgress?.isIndeterminate = state.syncing.indeterminate
                binding.snackbar.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                snackbarBinding?.snackbarTitle?.setText(R.string.main_default_sms_title)
                snackbarBinding?.snackbarMessage?.setText(R.string.main_default_sms_message)
                snackbarBinding?.snackbarButton?.setText(R.string.main_default_sms_change)
            }

            !state.smsPermission -> {
                snackbarBinding?.snackbarTitle?.setText(R.string.main_permission_required)
                snackbarBinding?.snackbarMessage?.setText(R.string.main_permission_sms)
                snackbarBinding?.snackbarButton?.setText(R.string.main_permission_allow)
            }

            !state.contactPermission -> {
                snackbarBinding?.snackbarTitle?.setText(R.string.main_permission_required)
                snackbarBinding?.snackbarMessage?.setText(R.string.main_permission_contacts)
                snackbarBinding?.snackbarButton?.setText(R.string.main_permission_allow)
            }

            !state.notificationPermission -> {
                snackbarBinding?.snackbarTitle?.setText(R.string.main_permission_required)
                snackbarBinding?.snackbarMessage?.setText(R.string.main_permission_notifications)
                snackbarBinding?.snackbarButton?.setText(R.string.main_permission_allow)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumedIntent.onNext(true)
        if (shouldShowNativeAds && !conversationsAdapter.hasInlineAd()) {
            loadNativeAd(force = true)
        }
    }

    override fun onPause() {
        super.onPause()
        activityResumedIntent.onNext(false)

        // Show interstitial ad when leaving the main screen
        interstitialAdManager.maybeShowAd(this)
    }

    override fun onDestroy() {
        cancelNativeAdRetry()
        nativeAdController.destroy()
        super.onDestroy()
        disposables.dispose()
    }

    private fun loadNativeAd(force: Boolean = false) {
        if (!shouldShowNativeAds) return
        if (isFinishing || isDestroyed) return
        if (!force && conversationsAdapter.hasInlineAd()) return
        com.charles.messenger.util.DebugLogger.log(
            location = "MainActivity.kt:loadNativeAd",
            message = "Attempting native ad load",
            data = mapOf(
                "force" to force.toString(),
                "retryAttempt" to nativeAdRetryAttempt.toString(),
                "hasInlineAd" to conversationsAdapter.hasInlineAd().toString()
            ),
            hypothesisId = "ADS_NATIVE_4"
        )

        nativeAdController.load(
            onLoadedAd = { nativeAd ->
                conversationsAdapter.setInlineNativeAd(nativeAd)
                nativeAdRetryAttempt = 0
                com.charles.messenger.util.DebugLogger.log(
                    location = "MainActivity.kt:loadNativeAd",
                    message = "Native ad load callback success; retry reset",
                    hypothesisId = "ADS_NATIVE_4"
                )
            },
            onFailed = {
                scheduleNativeAdRetry()
            }
        )
    }

    private fun scheduleNativeAdRetry() {
        if (!shouldShowNativeAds) return
        if (isFinishing || isDestroyed) return
        if (nativeAdRetryAttempt >= maxNativeAdRetryAttempts) {
            com.charles.messenger.util.DebugLogger.log(
                location = "MainActivity.kt:scheduleNativeAdRetry",
                message = "Native ad retry cap reached",
                data = mapOf("retryAttempt" to nativeAdRetryAttempt.toString()),
                hypothesisId = "ADS_NATIVE_5"
            )
            return
        }

        cancelNativeAdRetry()
        val cappedAttempt = min(nativeAdRetryAttempt, 4)
        val delayMs = 5_000L * (1L shl cappedAttempt)
        com.charles.messenger.util.DebugLogger.log(
            location = "MainActivity.kt:scheduleNativeAdRetry",
            message = "Scheduling native ad retry",
            data = mapOf(
                "retryAttempt" to nativeAdRetryAttempt.toString(),
                "delayMs" to delayMs.toString()
            ),
            hypothesisId = "ADS_NATIVE_5"
        )
        nativeAdRetryAttempt++
        nativeAdRetryHandler.postDelayed({ loadNativeAd(force = true) }, delayMs)
    }

    private fun cancelNativeAdRetry() {
        nativeAdRetryHandler.removeCallbacksAndMessages(null)
    }

    override fun showBackButton(show: Boolean) {
        toggle.onDrawerSlide(binding.drawer.root, if (show) 1f else 0f)
        toggle.drawerArrowDrawable.color = when (show) {
            true -> resolveThemeColor(android.R.attr.textColorSecondary)
            false -> resolveThemeColor(android.R.attr.textColorPrimary)
        }
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    override fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS), 0)
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            // For Android 12 and below, open notification settings
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }
    }

    override fun clearSearch() {
        dismissKeyboard()
        binding.toolbarSearch.text = null
    }

    override fun clearSelection() {
        conversationsAdapter.clearSelection()
    }

    override fun themeChanged() {
        binding.recyclerView.scrapViews()
    }

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        val count = conversations.size
        AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(resources.getQuantityString(R.plurals.dialog_delete_message, count, count))
                .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(conversations) }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    override fun showChangelog(changelog: ChangelogManager.CumulativeChangelog) {
        changelogDialog.show(changelog)
    }

    override fun showArchivedSnackbar() {
        Snackbar.make(binding.drawerLayout, R.string.toast_archived, Snackbar.LENGTH_LONG).apply {
            setAction(R.string.button_undo) { undoArchiveIntent.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun onBackPressed() {
        backPressedSubject.onNext(NavItem.BACK)
    }

}
