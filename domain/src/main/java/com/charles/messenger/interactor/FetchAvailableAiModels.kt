package com.charles.messenger.interactor

import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.AiProvider
import com.charles.messenger.repository.OllamaRepository
import com.charles.messenger.repository.OnDeviceLlmRepository
import io.reactivex.Flowable
import javax.inject.Inject

class FetchAvailableAiModels @Inject constructor(
    private val ollamaRepository: OllamaRepository,
    private val onDeviceLlmRepository: OnDeviceLlmRepository
) : Interactor<FetchAvailableAiModels.Params>() {

    data class Params(
        val provider: AiProvider,
        val baseUrl: String = ""
    )

    override fun buildObservable(params: Params): Flowable<List<AiModelOption>> {
        return when (params.provider) {
            AiProvider.OLLAMA -> ollamaRepository.getManagedModels(params.baseUrl).toFlowable()
            AiProvider.ON_DEVICE -> onDeviceLlmRepository.getManagedModels().toFlowable()
        }
    }
}
