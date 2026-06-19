package com.charles.messenger.feature.settings.ai.tutorial

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.bluelinelabs.conductor.RouterTransaction
import com.charles.messenger.R
import com.charles.messenger.common.QkChangeHandler
import com.charles.messenger.common.base.QkController
import com.charles.messenger.feature.settings.ai.AiSettingsController
import com.charles.messenger.injection.appComponent
import com.jakewharton.rxbinding2.view.clicks
import io.reactivex.Observable
import javax.inject.Inject

class AiTutorialController : QkController<AiTutorialView, AiTutorialState, AiTutorialPresenter>(), AiTutorialView {

    @Inject override lateinit var presenter: AiTutorialPresenter

    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var stepView: TextView
    private lateinit var onDeviceCard: View
    private lateinit var ollamaCard: View
    private lateinit var onDeviceBadge: TextView
    private lateinit var ollamaBadge: TextView
    private lateinit var onDeviceUnsupported: TextView
    private lateinit var backButton: Button
    private lateinit var nextButton: Button
    private lateinit var skipButton: Button

    init {
        appComponent.inject(this)
        layoutRes = R.layout.ai_tutorial_controller
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        titleView = view.findViewById(R.id.titleView)
        bodyView = view.findViewById(R.id.bodyView)
        stepView = view.findViewById(R.id.stepView)
        onDeviceCard = view.findViewById(R.id.onDeviceCard)
        ollamaCard = view.findViewById(R.id.ollamaCard)
        onDeviceBadge = view.findViewById(R.id.onDeviceBadge)
        ollamaBadge = view.findViewById(R.id.ollamaBadge)
        onDeviceUnsupported = view.findViewById(R.id.onDeviceUnsupported)
        backButton = view.findViewById(R.id.backButton)
        nextButton = view.findViewById(R.id.nextButton)
        skipButton = view.findViewById(R.id.skipButton)

        presenter.bindIntents(this)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        setTitle(R.string.ai_tutorial_title)
        showBackButton(true)
    }

    override fun nextClicks(): Observable<Unit> = nextButton.clicks()

    override fun backClicks(): Observable<Unit> = backButton.clicks()

    override fun skipClicks(): Observable<Unit> = skipButton.clicks()

    override fun chooseOnDeviceClicks(): Observable<Unit> = onDeviceCard.clicks()

    override fun chooseOllamaClicks(): Observable<Unit> = ollamaCard.clicks()

    override fun closeTutorial() {
        activity?.finish()
    }

    override fun openAiSettings() {
        router.setRoot(
            RouterTransaction.with(AiSettingsController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler())
        )
    }

    override fun render(state: AiTutorialState) {
        val step = state.page + 1
        stepView.text = activity?.getString(R.string.ai_tutorial_step, step, 3)

        when (state.page) {
            0 -> {
                titleView.text = activity?.getString(R.string.ai_tutorial_welcome_title)
                bodyView.text = activity?.getString(R.string.ai_tutorial_welcome_body)
                onDeviceCard.visibility = View.GONE
                ollamaCard.visibility = View.GONE
            }
            1 -> {
                titleView.text = activity?.getString(R.string.ai_tutorial_provider_title)
                bodyView.text = activity?.getString(R.string.ai_tutorial_provider_body)
                onDeviceCard.visibility = View.VISIBLE
                ollamaCard.visibility = View.VISIBLE
                onDeviceUnsupported.visibility = if (state.onDeviceSupported) View.GONE else View.VISIBLE
                onDeviceUnsupported.text = state.onDeviceUnsupportedReason
            }
            else -> {
                titleView.text = activity?.getString(R.string.ai_tutorial_finish_title)
                bodyView.text = activity?.getString(R.string.ai_tutorial_finish_body)
                onDeviceCard.visibility = View.GONE
                ollamaCard.visibility = View.GONE
                onDeviceUnsupported.visibility = View.GONE
            }
        }

        onDeviceCard.isEnabled = state.onDeviceSupported
        onDeviceCard.isClickable = state.onDeviceSupported
        onDeviceCard.alpha = when {
            !state.onDeviceSupported -> 0.35f
            state.selectedProvider == TutorialProvider.ON_DEVICE -> 1f
            else -> 0.65f
        }
        ollamaCard.alpha = if (state.selectedProvider == TutorialProvider.OLLAMA) 1f else 0.65f
        onDeviceBadge.text = when {
            !state.onDeviceSupported -> activity?.getString(R.string.ai_tutorial_not_supported)
            state.selectedProvider == TutorialProvider.ON_DEVICE -> activity?.getString(R.string.ai_tutorial_selected)
            else -> activity?.getString(R.string.ai_tutorial_tap_to_choose)
        }
        ollamaBadge.text = if (state.selectedProvider == TutorialProvider.OLLAMA) activity?.getString(R.string.ai_tutorial_selected) else activity?.getString(R.string.ai_tutorial_tap_to_choose)

        backButton.visibility = if (state.page == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text = if (state.page == 2) {
            activity?.getString(R.string.ai_tutorial_finish)
        } else {
            activity?.getString(R.string.setup_next)
        }
    }
}
