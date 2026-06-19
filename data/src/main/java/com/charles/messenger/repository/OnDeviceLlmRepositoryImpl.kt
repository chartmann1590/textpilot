package com.charles.messenger.repository

import android.content.Context
import android.os.Build
import com.charles.messenger.model.AiModelInstallUpdate
import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.Message
import com.charles.messenger.util.AiPromptFormatter
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import io.reactivex.Observable
import io.reactivex.Single
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceLlmRepositoryImpl @Inject constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) : OnDeviceLlmRepository {

    companion object {
        private const val MODEL_DIR_NAME = "ai-models"
        private const val SMART_REPLY_MAX_TOKENS = 2048

        private val catalog = listOf(
            AiModelOption(
                id = "gemma-4-e2b-it",
                displayName = "Gemma 4 E2B",
                summary = "Download 2.58 GB",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                artifactFileName = "gemma-4-E2B-it.litertlm"
            ),
            AiModelOption(
                id = "qwen3-0.6b",
                displayName = "Qwen 3 0.6B",
                summary = "Download 614 MB",
                downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                artifactFileName = "Qwen3-0.6B.litertlm"
            )
        )

        private val suggestionSamplerConfig = SamplerConfig(
            topK = 20,
            topP = 0.9,
            temperature = 0.2
        )
    }

    override fun getAvailableModels(
        modelPath: String,
        modelName: String
    ): Single<List<AiModelOption>> {
        return Single.fromCallable {
            val effectiveModelName = validateAndWarmupModel(modelPath, modelName)
            listOf(
                AiModelOption(
                    id = effectiveModelName,
                    displayName = effectiveModelName,
                    summary = "Installed on device",
                    installed = true,
                    localPath = modelPath.trim()
                )
            )
        }
    }

    override fun getManagedModels(): Single<List<AiModelOption>> {
        return Single.fromCallable {
            catalog.map { model ->
                val installedPath = resolveInstalledPath(model)
                if (installedPath != null) {
                    model.copy(
                        installed = true,
                        summary = "Installed on device",
                        localPath = installedPath.absolutePath
                    )
                } else {
                    model
                }
            }
        }
    }

    override fun downloadModel(modelId: String): Observable<AiModelInstallUpdate> {
        return Observable.create { emitter ->
            val model = catalog.firstOrNull { it.id == modelId }
                ?: throw IllegalArgumentException("Unsupported on-device model: $modelId")

            val targetFile = modelFile(model)
            if (targetFile.exists()) {
                emitter.onNext(
                    AiModelInstallUpdate(
                        modelId = model.id,
                        status = "${model.displayName} is already installed",
                        complete = true,
                        localPath = targetFile.absolutePath
                    )
                )
                emitter.onComplete()
                return@create
            }

            targetFile.parentFile?.mkdirs()
            val partialFile = File(targetFile.absolutePath + ".part")
            if (partialFile.exists()) {
                partialFile.delete()
            }

            emitter.onNext(
                AiModelInstallUpdate(
                    modelId = model.id,
                    status = "Downloading ${model.displayName}..."
                )
            )

            val request = Request.Builder()
                .url(model.downloadUrl)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed: ${response.code} ${response.message}")
                }

                val body = response.body ?: throw IllegalStateException("Download failed: empty response")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(partialFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        var lastPercent = -1

                        while (input.read(buffer).also { bytesRead = it } >= 0) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                if (percent >= lastPercent + 10) {
                                    lastPercent = percent
                                    emitter.onNext(
                                        AiModelInstallUpdate(
                                            modelId = model.id,
                                            status = "Downloading ${model.displayName}: $percent%"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!partialFile.renameTo(targetFile)) {
                throw IllegalStateException("Failed to finalize download for ${model.displayName}")
            }

            emitter.onNext(
                AiModelInstallUpdate(
                    modelId = model.id,
                    status = "${model.displayName} downloaded",
                    complete = true,
                    localPath = targetFile.absolutePath
                )
            )
            emitter.onComplete()
        }
    }

    override fun generateReplySuggestions(
        modelPath: String,
        modelName: String,
        conversationContext: List<Message>,
        persona: String?
    ): Single<List<String>> {
        return Single.fromCallable {
            val effectiveModelName = validateAndWarmupModel(modelPath, modelName, warmupOnly = false)
            Timber.d("Generating on-device replies with model: $effectiveModelName at $modelPath")

            val prompt = AiPromptFormatter.buildSinglePrompt(conversationContext, persona)
            createEngine(modelPath).use { engine ->
                engine.initialize()
                val responseText = engine.createConversation(
                    ConversationConfig(samplerConfig = suggestionSamplerConfig)
                ).use { conversation ->
                    conversation.sendMessage(prompt).toString()
                }
                AiPromptFormatter.parseReplySuggestions(responseText)
            }
        }
    }

    private fun modelDirectory(): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    private fun modelFile(model: AiModelOption): File {
        return File(modelDirectory(), model.artifactFileName)
    }

    private fun resolveInstalledPath(model: AiModelOption): File? {
        val file = modelFile(model)
        return file.takeIf { it.exists() && it.isFile }
    }

    private fun validateAndWarmupModel(
        modelPath: String,
        modelName: String,
        warmupOnly: Boolean = true
    ): String {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "On-device LiteRT requires Android 6.0 or newer"
        }

        val trimmedPath = modelPath.trim()
        require(trimmedPath.isNotEmpty()) { "Select a LiteRT model first" }

        val modelFile = File(trimmedPath)
        require(modelFile.exists()) { "Model file not found: $trimmedPath" }
        require(modelFile.isFile) { "Model path is not a file: $trimmedPath" }

        val effectiveModelName = modelName.trim().ifEmpty { modelFile.nameWithoutExtension }
        require(effectiveModelName.isNotBlank()) { "Select a model such as Gemma or Qwen first" }

        if (warmupOnly) {
            // Warmup is useful when selecting a model, but doing this again immediately before
            // generation can cause unnecessary native memory pressure on large downloaded models.
            createEngine(modelFile.absolutePath).use { engine ->
                engine.initialize()
                engine.createConversation().use { }
                Timber.d("Validated on-device model: $effectiveModelName")
            }
        }

        return effectiveModelName
    }

    private fun createEngine(modelPath: String): Engine {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val cacheDir = File(context.cacheDir, "litertlm-cache").apply { mkdirs() }
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
            maxNumTokens = SMART_REPLY_MAX_TOKENS,
            cacheDir = cacheDir.absolutePath
        )

        return Engine(engineConfig)
    }
}
