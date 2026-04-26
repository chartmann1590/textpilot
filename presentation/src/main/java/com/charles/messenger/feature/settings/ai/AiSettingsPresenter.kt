package com.charles.messenger.feature.settings.ai

import com.charles.messenger.common.base.QkPresenter
import com.charles.messenger.interactor.FetchAvailableAiModels
import com.charles.messenger.interactor.InstallAiModel
import com.charles.messenger.interactor.ValidateAiBackend
import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.AiProvider
import com.charles.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

class AiSettingsPresenter @Inject constructor(
    private val prefs: Preferences,
    private val fetchAvailableAiModels: FetchAvailableAiModels,
    private val validateAiBackend: ValidateAiBackend,
    private val installAiModel: InstallAiModel,
    private val autoReplyNotification: com.charles.messenger.common.util.AiAutoReplyNotification
) : QkPresenter<AiSettingsView, AiSettingsState>(
    AiSettingsState(
        aiEnabled = false,
        provider = AiProvider.OLLAMA,
        ollamaUrl = "",
        ollamaModel = "",
        onDeviceModelName = "",
        onDeviceModelPath = "",
        autoReplyToAll = false,
        persona = "",
        signatureEnabled = false,
        signatureText = ""
    )
) {

    override fun bindIntents(view: AiSettingsView) {
        super.bindIntents(view)

        val initialProvider = prefs.currentAiProvider()
        val initialOllamaUrl = prefs.ollamaApiUrl.get()
        val initialOllamaModel = prefs.ollamaModel.get()
        val initialOnDeviceModelName = prefs.onDeviceModelName.get().ifBlank { prefs.currentAiModel() }
        val initialOnDeviceModelPath = prefs.onDeviceModelPath.get().takeIf { it.isNotBlank() }.orEmpty()
        val initialPersona = prefs.aiPersona.get()
        val initialSignatureText = prefs.aiSignatureText.get()

        newState {
            copy(
                aiEnabled = prefs.aiReplyEnabled.get(),
                provider = initialProvider,
                ollamaUrl = initialOllamaUrl,
                ollamaModel = initialOllamaModel,
                onDeviceModelName = initialOnDeviceModelName,
                onDeviceModelPath = initialOnDeviceModelPath,
                autoReplyToAll = prefs.aiAutoReplyToAll.get(),
                persona = initialPersona,
                signatureEnabled = prefs.aiSignatureEnabled.get(),
                signatureText = initialSignatureText
            )
        }

        view.aiEnabledChanged()
            .doOnNext { enabled ->
                prefs.aiReplyEnabled.set(enabled)
                Timber.d("AI Reply enabled: $enabled")
            }
            .autoDisposable(view.scope())
            .subscribe { enabled ->
                newState { copy(aiEnabled = enabled) }
            }

        view.providerSelected()
            .doOnNext { provider ->
                prefs.aiProvider.set(provider.value)
                Timber.d("AI provider selected: $provider")
            }
            .autoDisposable(view.scope())
            .subscribe { provider ->
                newState {
                    copy(
                        provider = provider,
                        availableModels = emptyList(),
                        connectionStatus = ConnectionStatus.Unknown,
                        installStatus = "",
                        loadingModels = true
                    )
                }
                refreshCatalog(view, provider, prefs.ollamaApiUrl.get())
            }

        view.ollamaUrlChanged()
            .doOnNext { url ->
                prefs.ollamaApiUrl.set(url)
                Timber.d("Ollama URL updated: $url")
            }
            .autoDisposable(view.scope())
            .subscribe { url ->
                newState {
                    copy(
                        ollamaUrl = url,
                        availableModels = emptyList(),
                        connectionStatus = ConnectionStatus.Unknown,
                        installStatus = "",
                        loadingModels = true
                    )
                }
                refreshCatalog(view, prefs.currentAiProvider(), url)
            }

        view.modelSelected()
            .withLatestFrom(state) { modelId, currentState -> modelId to currentState }
            .autoDisposable(view.scope())
            .subscribe { (modelId, currentState) ->
                val selectedModel = currentState.availableModels.firstOrNull { it.id == modelId }
                    ?: return@subscribe

                when (currentState.provider) {
                    AiProvider.OLLAMA -> {
                        if (selectedModel.installed) {
                            prefs.ollamaModel.set(selectedModel.id)
                            newState { copy(ollamaModel = selectedModel.id, installStatus = "") }
                            view.showToast("Selected ${selectedModel.displayName}")
                        } else {
                            startModelInstall(view, currentState.provider, selectedModel, currentState.ollamaUrl)
                        }
                    }

                    AiProvider.ON_DEVICE -> {
                        if (selectedModel.installed && selectedModel.localPath.isNotBlank()) {
                            prefs.onDeviceModelName.set(selectedModel.displayName)
                            prefs.onDeviceModelPath.set(selectedModel.localPath)
                            newState {
                                copy(
                                    onDeviceModelName = selectedModel.displayName,
                                    onDeviceModelPath = selectedModel.localPath,
                                    installStatus = ""
                                )
                            }
                            view.showToast("Selected ${selectedModel.displayName}")
                        } else {
                            startModelInstall(view, currentState.provider, selectedModel, currentState.ollamaUrl)
                        }
                    }
                }
            }

        view.autoReplyToAllChanged()
            .doOnNext { enabled ->
                prefs.aiAutoReplyToAll.set(enabled)
                Timber.d("Auto-Reply to All: $enabled")
                if (enabled) {
                    autoReplyNotification.resetCount()
                }
                autoReplyNotification.updateIfNeeded()
            }
            .autoDisposable(view.scope())
            .subscribe { enabled ->
                newState { copy(autoReplyToAll = enabled) }
                if (enabled) {
                    view.showToast("Auto-reply is now active for all messages")
                } else {
                    view.showToast("Auto-reply disabled")
                }
            }

        view.personaChanged()
            .doOnNext { persona ->
                prefs.aiPersona.set(persona)
                Timber.d("AI Persona updated")
            }
            .autoDisposable(view.scope())
            .subscribe { persona ->
                newState { copy(persona = persona) }
            }

        view.signatureEnabledChanged()
            .doOnNext { enabled ->
                prefs.aiSignatureEnabled.set(enabled)
                Timber.d("AI Signature enabled: $enabled")
            }
            .autoDisposable(view.scope())
            .subscribe { enabled ->
                newState { copy(signatureEnabled = enabled) }
            }

        view.signatureTextChanged()
            .doOnNext { text ->
                prefs.aiSignatureText.set(text)
                Timber.d("AI Signature text updated")
            }
            .autoDisposable(view.scope())
            .subscribe { text ->
                newState { copy(signatureText = text) }
            }

        view.testConnectionClicks()
            .doOnNext {
                newState {
                    copy(
                        connectionStatus = ConnectionStatus.Testing,
                        loadingModels = true,
                        installStatus = ""
                    )
                }
            }
            .withLatestFrom(state) { _, currentState -> currentState }
            .observeOn(Schedulers.io())
            .switchMap { currentState ->
                validateAiBackend.buildObservable(
                    ValidateAiBackend.Params(
                        provider = currentState.provider,
                        baseUrl = currentState.ollamaUrl,
                        onDeviceModelName = currentState.onDeviceModelName,
                        onDeviceModelPath = currentState.onDeviceModelPath
                    )
                ).map { Triple(currentState.provider, currentState.ollamaUrl, it) }
                    .toObservable()
            }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe(
                { (provider, baseUrl, models) ->
                    Timber.d("Validated AI backend for provider $provider")
                    newState {
                        copy(
                            connectionStatus = ConnectionStatus.Connected,
                            loadingModels = false
                        )
                    }

                    if (provider == AiProvider.OLLAMA) {
                        view.showToast("Connected. Found ${models.size} models on the server")
                    } else {
                        view.showToast("On-device model validated")
                    }

                    refreshCatalog(view, provider, baseUrl)
                },
                { error ->
                    Timber.e(error, "Failed to validate AI backend")
                    newState {
                        copy(
                            connectionStatus = ConnectionStatus.Failed,
                            loadingModels = false,
                            installStatus = error.message.orEmpty()
                        )
                    }
                    view.showToast("Validation failed: ${error.message}")
                }
            )

        newState { copy(loadingModels = true) }
        refreshCatalog(view, initialProvider, initialOllamaUrl)
    }

    private fun startModelInstall(
        view: AiSettingsView,
        provider: AiProvider,
        model: AiModelOption,
        baseUrl: String
    ) {
        installAiModel.buildObservable(
            InstallAiModel.Params(
                provider = provider,
                modelId = model.id,
                baseUrl = baseUrl
            )
        )
            .toObservable()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe(
                { update ->
                    newState {
                        copy(
                            loadingModels = !update.complete,
                            installStatus = update.status,
                            connectionStatus = ConnectionStatus.Unknown
                        )
                    }

                    if (update.complete) {
                        when (provider) {
                            AiProvider.OLLAMA -> {
                                prefs.ollamaModel.set(model.id)
                                newState { copy(ollamaModel = model.id, loadingModels = false) }
                            }

                            AiProvider.ON_DEVICE -> {
                                prefs.onDeviceModelName.set(model.displayName)
                                prefs.onDeviceModelPath.set(update.localPath)
                                newState {
                                    copy(
                                        onDeviceModelName = model.displayName,
                                        onDeviceModelPath = update.localPath,
                                        loadingModels = false
                                    )
                                }
                            }
                        }

                        view.showToast(update.status)
                        refreshCatalog(view, provider, baseUrl)
                    }
                },
                { error ->
                    Timber.e(error, "Failed to install AI model ${model.id}")
                    newState {
                        copy(
                            loadingModels = false,
                            installStatus = error.message.orEmpty()
                        )
                    }
                    view.showToast("Model install failed: ${error.message}")
                }
            )
    }

    private fun refreshCatalog(
        view: AiSettingsView,
        provider: AiProvider,
        baseUrl: String
    ) {
        fetchAvailableAiModels.buildObservable(
            FetchAvailableAiModels.Params(
                provider = provider,
                baseUrl = baseUrl
            )
        )
            .toObservable()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe(
                { models ->
                    newState {
                        copy(
                            availableModels = models,
                            loadingModels = false
                        )
                    }
                },
                { error ->
                    Timber.w(error, "Failed to refresh AI model catalog for provider $provider")
                    newState {
                        copy(
                            availableModels = emptyList(),
                            loadingModels = false
                        )
                    }
                }
            )
    }
}
