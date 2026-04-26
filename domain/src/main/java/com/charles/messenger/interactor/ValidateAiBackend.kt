package com.charles.messenger.interactor

import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.AiProvider
import com.charles.messenger.repository.OllamaRepository
import com.charles.messenger.repository.OnDeviceLlmRepository
import io.reactivex.Flowable
import javax.inject.Inject

class ValidateAiBackend @Inject constructor(
    private val ollamaRepository: OllamaRepository,
    private val onDeviceLlmRepository: OnDeviceLlmRepository
) : Interactor<ValidateAiBackend.Params>() {

    data class Params(
        val provider: AiProvider,
        val baseUrl: String = "",
        val onDeviceModelName: String = "",
        val onDeviceModelPath: String = ""
    )

    override fun buildObservable(params: Params): Flowable<List<AiModelOption>> {
        return when (params.provider) {
            AiProvider.OLLAMA -> {
                ollamaRepository.getAvailableModels(params.baseUrl)
                    .map { models ->
                        models.map { model ->
                            AiModelOption(
                                id = model.name,
                                displayName = model.name,
                                summary = "Installed on server",
                                installed = true
                            )
                        }
                    }
                    .toFlowable()
            }

            AiProvider.ON_DEVICE -> {
                onDeviceLlmRepository.getAvailableModels(
                    modelPath = params.onDeviceModelPath,
                    modelName = params.onDeviceModelName
                ).toFlowable()
            }
        }
    }
}
