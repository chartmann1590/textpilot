package com.google.ai.edge.litertlm

internal object LiteRtLmJni {

    init {
        System.loadLibrary("litertlm_jni")
    }

    external fun nativeCreateEngine(
        modelPath: String,
        backend: String,
        visionBackend: String,
        audioBackend: String,
        maxNumTokens: Int,
        maxNumImages: Int,
        cacheDir: String,
        enableBenchmark: Boolean,
        enableSpeculativeDecoding: Boolean?,
        mainNpuNativeLibraryDir: String,
        visionNpuNativeLibraryDir: String,
        audioNpuNativeLibraryDir: String,
        mainBackendNumThreads: Int,
        audioBackendNumThreads: Int
    ): Long

    external fun nativeDeleteEngine(enginePointer: Long)

    external fun nativeCreateConversation(
        enginePointer: Long,
        samplerConfig: SamplerConfig?,
        messageJsonString: String,
        toolsDescriptionJsonString: String,
        channelsJsonString: String?,
        extraContextJsonString: String,
        enableConversationConstrainedDecoding: Boolean,
        filterChannelContentFromKvCache: Boolean,
        overwritePromptTemplate: String?
    ): Long

    external fun nativeDeleteConversation(conversationPointer: Long)

    external fun nativeSendMessage(
        conversationPointer: Long,
        messageJsonString: String,
        extraContextJsonString: String
    ): String

    external fun nativeSetMinLogSeverity(logSeverity: Int)
}
