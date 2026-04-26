package com.google.ai.edge.litertlm

class Engine(private val engineConfig: EngineConfig) : AutoCloseable {
    private var handle: Long? = null

    fun initialize() {
        check(handle == null) { "Engine is already initialized." }

        val mainBackendNumThreads =
            (engineConfig.backend as? Backend.CPU)?.numOfThreads?.takeIf { it > 0 } ?: -1

        handle = LiteRtLmJni.nativeCreateEngine(
            engineConfig.modelPath,
            engineConfig.backend.name,
            "",
            "",
            engineConfig.maxNumTokens ?: -1,
            -1,
            engineConfig.cacheDir.orEmpty(),
            false,
            null,
            (engineConfig.backend as? Backend.NPU)?.nativeLibraryDir.orEmpty(),
            "",
            "",
            mainBackendNumThreads,
            -1
        )
    }

    fun createConversation(
        conversationConfig: ConversationConfig = ConversationConfig()
    ): Conversation {
        val currentHandle = checkNotNull(handle) { "Engine is not initialized." }
        return Conversation(
            LiteRtLmJni.nativeCreateConversation(
                currentHandle,
                conversationConfig.samplerConfig,
                "[]",
                "[]",
                null,
                "{}",
                false,
                false,
                null
            )
        )
    }

    override fun close() {
        val currentHandle = checkNotNull(handle) { "Engine is not initialized." }
        LiteRtLmJni.nativeDeleteEngine(currentHandle)
        handle = null
    }

    companion object {
        fun setNativeMinLogSeverity(level: LogSeverity) {
            LiteRtLmJni.nativeSetMinLogSeverity(level.severity)
        }
    }
}
