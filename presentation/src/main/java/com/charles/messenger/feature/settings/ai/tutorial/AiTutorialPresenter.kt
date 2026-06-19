package com.charles.messenger.feature.settings.ai.tutorial

import android.os.Build
import com.charles.messenger.common.base.QkPresenter
import com.charles.messenger.model.AiProvider
import com.charles.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.rxkotlin.withLatestFrom
import javax.inject.Inject

class AiTutorialPresenter @Inject constructor(
    private val prefs: Preferences
) : QkPresenter<AiTutorialView, AiTutorialState>(AiTutorialState()) {

    override fun bindIntents(view: AiTutorialView) {
        super.bindIntents(view)

        val onDeviceSupport = getOnDeviceSupport()
        newState {
            copy(
                onDeviceSupported = onDeviceSupport.first,
                onDeviceUnsupportedReason = onDeviceSupport.second,
                selectedProvider = if (onDeviceSupport.first) TutorialProvider.ON_DEVICE else TutorialProvider.OLLAMA
            )
        }

        // Ensure this never auto-shows again once user enters the tutorial.
        prefs.aiTutorialSeen.set(true)

        view.nextClicks()
            .withLatestFrom(state) { _, current -> current }
            .autoDisposable(view.scope())
            .subscribe { current ->
                if (current.page < LAST_PAGE) {
                    newState { copy(page = current.page + 1) }
                } else {
                    view.openAiSettings()
                }
            }

        view.backClicks()
            .withLatestFrom(state) { _, current -> current }
            .autoDisposable(view.scope())
            .subscribe { current ->
                if (current.page > 0) {
                    newState { copy(page = current.page - 1) }
                }
            }

        view.chooseOnDeviceClicks()
            .withLatestFrom(state) { _, current -> current }
            .filter { it.onDeviceSupported }
            .autoDisposable(view.scope())
            .subscribe {
                prefs.aiProvider.set(AiProvider.ON_DEVICE.value)
                newState { copy(selectedProvider = TutorialProvider.ON_DEVICE) }
            }

        view.chooseOllamaClicks()
            .autoDisposable(view.scope())
            .subscribe {
                prefs.aiProvider.set(AiProvider.OLLAMA.value)
                newState { copy(selectedProvider = TutorialProvider.OLLAMA) }
            }

        view.skipClicks()
            .autoDisposable(view.scope())
            .subscribe { view.closeTutorial() }

    }

    private companion object {
        const val LAST_PAGE = 2
    }

    private fun getOnDeviceSupport(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false to "On-device models require Android 6.0 or newer."
        }

        val has64BitAbi = Build.SUPPORTED_ABIS.any { abi ->
            abi.contains("arm64") || abi.contains("x86_64")
        }
        if (!has64BitAbi) {
            return false to "On-device models require a 64-bit device ABI."
        }

        return true to ""
    }
}
