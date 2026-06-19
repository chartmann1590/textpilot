package com.charles.messenger.feature.settings.ai.tutorial

data class AiTutorialState(
    val page: Int = 0,
    val selectedProvider: TutorialProvider = TutorialProvider.ON_DEVICE,
    val onDeviceSupported: Boolean = true,
    val onDeviceUnsupportedReason: String = ""
)

enum class TutorialProvider {
    ON_DEVICE,
    OLLAMA
}
