/*
 * Copyright (C) 2024 Charles Hartmann
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.charles.messenger.interactor

import com.charles.messenger.model.AiProvider
import com.charles.messenger.model.Message
import com.charles.messenger.repository.OllamaRepository
import com.charles.messenger.repository.OnDeviceLlmRepository
import io.reactivex.Flowable
import javax.inject.Inject

/**
 * Generate smart reply suggestions using AI
 */
class GenerateSmartReplies @Inject constructor(
    private val ollamaRepository: OllamaRepository,
    private val onDeviceLlmRepository: OnDeviceLlmRepository
) : Interactor<GenerateSmartReplies.Params>() {

    data class Params(
        val provider: AiProvider,
        val baseUrl: String,
        val model: String,
        val onDeviceModelPath: String = "",
        val messages: List<Message>,
        val persona: String? = null
    )

    override fun buildObservable(params: Params): Flowable<List<String>> {
        return when (params.provider) {
            AiProvider.OLLAMA -> {
                ollamaRepository.generateReplySuggestions(
                    baseUrl = params.baseUrl,
                    model = params.model,
                    conversationContext = params.messages,
                    persona = params.persona
                ).toFlowable()
            }

            AiProvider.ON_DEVICE -> {
                onDeviceLlmRepository.generateReplySuggestions(
                    modelPath = params.onDeviceModelPath,
                    modelName = params.model,
                    conversationContext = params.messages,
                    persona = params.persona
                ).toFlowable()
            }
        }
    }
}
