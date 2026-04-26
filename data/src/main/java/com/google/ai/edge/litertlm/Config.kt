package com.google.ai.edge.litertlm

sealed class Backend(val name: String) {
    data class CPU(val numOfThreads: Int? = null) : Backend("CPU")
    class GPU : Backend("GPU")
    data class NPU(val nativeLibraryDir: String = "") : Backend("NPU")
}

data class EngineConfig(
    val modelPath: String,
    val backend: Backend = Backend.CPU(),
    val maxNumTokens: Int? = null,
    val cacheDir: String? = null
)

data class ConversationConfig(
    val samplerConfig: SamplerConfig? = null
)

data class SamplerConfig(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int = 0
)

enum class LogSeverity(val severity: Int) {
    VERBOSE(0),
    DEBUG(1),
    INFO(2),
    WARNING(3),
    ERROR(4),
    FATAL(5),
    INFINITY(1000)
}
