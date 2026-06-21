package com.charles.messenger.feature.blocking.manager

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.core.view.isInvisible
import com.jakewharton.rxbinding2.view.clicks
import com.charles.messenger.R
import com.charles.messenger.common.base.QkController
import com.charles.messenger.common.util.Colors
import com.charles.messenger.common.util.extensions.resolveThemeColor
import com.charles.messenger.injection.appComponent
import com.charles.messenger.util.Preferences
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import javax.inject.Inject

class BlockingManagerController : QkController<BlockingManagerView, BlockingManagerState, BlockingManagerPresenter>(),
    BlockingManagerView {

    @Inject lateinit var colors: Colors
    @Inject override lateinit var presenter: BlockingManagerPresenter

    private lateinit var qksms: BlockingManagerPreferenceView
    private lateinit var callBlocker: BlockingManagerPreferenceView
    private lateinit var callControl: BlockingManagerPreferenceView
    private lateinit var shouldIAnswer: BlockingManagerPreferenceView

    private val activityResumedSubject: PublishSubject<Unit> = PublishSubject.create()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.blocking_manager_controller
    }

    override fun onAttach(view: View) {
        super.onAttach(view)

        qksms = view.findViewById(R.id.qksms)
        callBlocker = view.findViewById(R.id.callBlocker)
        callControl = view.findViewById(R.id.callControl)
        shouldIAnswer = view.findViewById(R.id.shouldIAnswer)

        presenter.bindIntents(this)
        setTitle(R.string.blocking_manager_title)
        showBackButton(true)

        val states = arrayOf(
                intArrayOf(android.R.attr.state_activated),
                intArrayOf(-android.R.attr.state_activated))

        val textTertiary = view.context.resolveThemeColor(android.R.attr.textColorTertiary)
        val imageTintList = ColorStateList(states, intArrayOf(colors.theme().theme, textTertiary))

        val qksmsAction = qksms.findViewById<ImageView>(R.id.action)
        val callBlockerAction = callBlocker.findViewById<ImageView>(R.id.action)
        val callControlAction = callControl.findViewById<ImageView>(R.id.action)
        val siaAction = shouldIAnswer.findViewById<ImageView>(R.id.action)

        qksmsAction.imageTintList = imageTintList
        callBlockerAction.imageTintList = imageTintList
        callControlAction.imageTintList = imageTintList
        siaAction.imageTintList = imageTintList
    }

    override fun onActivityResumed(activity: Activity) {
        activityResumedSubject.onNext(Unit)
    }

    override fun render(state: BlockingManagerState) {
        val qksmsAction = qksms.findViewById<ImageView>(R.id.action)
        qksmsAction.setImageResource(getActionIcon(true))
        qksmsAction.isActivated = true
        qksmsAction.isInvisible = state.blockingManager != Preferences.BLOCKING_MANAGER_QKSMS

        val callBlockerAction = callBlocker.findViewById<ImageView>(R.id.action)
        callBlockerAction.setImageResource(getActionIcon(state.callBlockerInstalled))
        callBlockerAction.isActivated = state.callBlockerInstalled
        callBlockerAction.isInvisible = state.blockingManager != Preferences.BLOCKING_MANAGER_CB
                && state.callBlockerInstalled

        val callControlAction = callControl.findViewById<ImageView>(R.id.action)
        callControlAction.setImageResource(getActionIcon(state.callControlInstalled))
        callControlAction.isActivated = state.callControlInstalled
        callControlAction.isInvisible = state.blockingManager != Preferences.BLOCKING_MANAGER_CC
                && state.callControlInstalled

        val siaAction = shouldIAnswer.findViewById<ImageView>(R.id.action)
        siaAction.setImageResource(getActionIcon(state.siaInstalled))
        siaAction.isActivated = state.siaInstalled
        siaAction.isInvisible = state.blockingManager != Preferences.BLOCKING_MANAGER_SIA
                && state.siaInstalled
    }

    private fun getActionIcon(installed: Boolean): Int = when {
        !installed -> R.drawable.ic_chevron_right_black_24dp
        else -> R.drawable.ic_check_white_24dp
    }

    override fun activityResumed(): Observable<*> = activityResumedSubject
    override fun qksmsClicked(): Observable<*> = qksms.clicks()
    override fun callBlockerClicked(): Observable<*> = callBlocker.clicks()
    override fun callControlClicked(): Observable<*> = callControl.clicks()
    override fun siaClicked(): Observable<*> = shouldIAnswer.clicks()

    override fun showCopyDialog(manager: String): Single<Boolean> = Single.create { emitter ->
        AlertDialog.Builder(activity)
                .setTitle(R.string.blocking_manager_copy_title)
                .setMessage(resources?.getString(R.string.blocking_manager_copy_summary, manager))
                .setPositiveButton(R.string.button_continue) { _, _ -> emitter.onSuccess(true) }
                .setNegativeButton(R.string.button_cancel) { _, _ -> emitter.onSuccess(false) }
                .setCancelable(false)
                .show()
    }

}
