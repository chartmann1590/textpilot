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
package com.charles.messenger.repository

import com.charles.messenger.model.Message
import com.charles.messenger.model.AiModelInstallUpdate
import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.OllamaModel
import io.reactivex.Observable
import io.reactivex.Single

/**
 * Repository for interacting with Ollama AI API
 */
interface OllamaRepository {

    /**
     * Fetch available AI models from Ollama server
     *
     * @param baseUrl The base URL of the Ollama API (e.g., "http://10.0.2.2:11434")
     * @return Single emitting list of available models
     */
    fun getAvailableModels(baseUrl: String): Single<List<OllamaModel>>

    fun getManagedModels(baseUrl: String): Single<List<AiModelOption>>

    fun pullModel(
        baseUrl: String,
        model: String
    ): Observable<AiModelInstallUpdate>

    /**
     * Generate smart reply suggestions based on conversation context
     *
     * @param baseUrl The base URL of the Ollama API
     * @param model The name of the model to use (e.g., "llama2")
     * @param conversationContext Recent messages from the conversation (last 10)
     * @param persona Optional persona description to guide AI responses
     * @return Single emitting list of 3-5 suggested reply strings
     */
    fun generateReplySuggestions(
        baseUrl: String,
        model: String,
        conversationContext: List<Message>,
        persona: String? = null
    ): Single<List<String>>
}
