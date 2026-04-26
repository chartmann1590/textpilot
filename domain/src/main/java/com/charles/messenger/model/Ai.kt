package com.charles.messenger.model

import java.io.File

enum class AiProvider(val value: String) {
    OLLAMA("ollama"),
    ON_DEVICE("on_device");

    companion object {
        fun fromPreference(value: String?): AiProvider {
            return values().firstOrNull { it.value == value } ?: OLLAMA
        }
    }
}

data class AiModelOption(
    val id: String,
    val displayName: String = id,
    val summary: String = "",
    val installed: Boolean = false,
    val localPath: String = "",
    val downloadUrl: String = "",
    val artifactFileName: String = ""
)

data class AiModelInstallUpdate(
    val modelId: String,
    val status: String,
    val complete: Boolean = false,
    val localPath: String = ""
)

data class AiBackendConfig(
    val provider: AiProvider,
    val ollamaBaseUrl: String = "",
    val ollamaModel: String = "",
    val onDeviceModelName: String = "",
    val onDeviceModelPath: String = ""
) {
    fun selectedModelName(): String {
        return when (provider) {
            AiProvider.OLLAMA -> ollamaModel.trim()
            AiProvider.ON_DEVICE -> onDeviceModelName.trim().ifEmpty {
                onDeviceModelPath.trim().takeIf { it.isNotEmpty() }
                    ?.let(::File)
                    ?.nameWithoutExtension
                    .orEmpty()
            }
        }
    }

    fun isConfigured(): Boolean {
        return when (provider) {
            AiProvider.OLLAMA -> ollamaBaseUrl.isNotBlank() && ollamaModel.isNotBlank()
            AiProvider.ON_DEVICE -> onDeviceModelPath.isNotBlank() && selectedModelName().isNotBlank()
        }
    }
}
