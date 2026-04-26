package com.charles.messenger.repository

import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.AiModelInstallUpdate
import com.charles.messenger.model.Message
import io.reactivex.Observable
import io.reactivex.Single

interface OnDeviceLlmRepository {

    fun getAvailableModels(
        modelPath: String,
        modelName: String
    ): Single<List<AiModelOption>>

    fun getManagedModels(): Single<List<AiModelOption>>

    fun downloadModel(modelId: String): Observable<AiModelInstallUpdate>

    fun generateReplySuggestions(
        modelPath: String,
        modelName: String,
        conversationContext: List<Message>,
        persona: String? = null
    ): Single<List<String>>
}
