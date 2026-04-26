package com.charles.messenger.interactor

import com.charles.messenger.model.AiModelInstallUpdate
import com.charles.messenger.model.AiProvider
import com.charles.messenger.repository.OllamaRepository
import com.charles.messenger.repository.OnDeviceLlmRepository
import io.reactivex.Flowable
import javax.inject.Inject

class InstallAiModel @Inject constructor(
    private val ollamaRepository: OllamaRepository,
    private val onDeviceLlmRepository: OnDeviceLlmRepository
) : Interactor<InstallAiModel.Params>() {

    data class Params(
        val provider: AiProvider,
        val modelId: String,
        val baseUrl: String = ""
    )

    override fun buildObservable(params: Params): Flowable<AiModelInstallUpdate> {
        return when (params.provider) {
            AiProvider.OLLAMA -> ollamaRepository.pullModel(params.baseUrl, params.modelId).toFlowable(
                io.reactivex.BackpressureStrategy.LATEST
            )
            AiProvider.ON_DEVICE -> onDeviceLlmRepository.downloadModel(params.modelId).toFlowable(
                io.reactivex.BackpressureStrategy.LATEST
            )
        }
    }
}
