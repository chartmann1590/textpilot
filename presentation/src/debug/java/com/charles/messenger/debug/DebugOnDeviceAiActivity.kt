package com.charles.messenger.debug

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import com.charles.messenger.model.Message
import com.charles.messenger.repository.OnDeviceLlmRepositoryImpl
import okhttp3.OkHttpClient
import java.io.File

class DebugOnDeviceAiActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread {
            val resultFile = File(cacheDir, "on_device_ai_e2e_result.txt")

            try {
                val prefs = getSharedPreferences(
                    "com.charles.messenger.v2_preferences",
                    Context.MODE_PRIVATE
                )
                val requestedModelId = intent.getStringExtra("modelId").orEmpty().ifBlank { "qwen3-0.6b" }
                val downloadIfMissing = intent.getBooleanExtra("downloadIfMissing", false)

                val repository = OnDeviceLlmRepositoryImpl(
                    context = this,
                    okHttpClient = OkHttpClient()
                )
                val availableModels = repository.getManagedModels().blockingGet()
                val requestedModel = availableModels.firstOrNull { it.id == requestedModelId }
                    ?: error("Unknown model id: $requestedModelId")

                var modelPath = prefs.getString("onDeviceModelPath", "").orEmpty()
                var modelName = prefs.getString("onDeviceModelName", "").orEmpty()
                var downloaded = false

                val currentModelValid = modelName == requestedModel.displayName &&
                    modelPath.isNotBlank() &&
                    File(modelPath).isFile

                if (!currentModelValid) {
                    val installedModel = availableModels.firstOrNull {
                        it.id == requestedModelId && it.installed && it.localPath.isNotBlank()
                    }

                    if (installedModel != null) {
                        modelPath = installedModel.localPath
                        modelName = installedModel.displayName
                    } else {
                        require(downloadIfMissing) { "Model is not installed: $requestedModelId" }
                        repository.downloadModel(requestedModelId).blockingLast()
                        downloaded = true

                        val refreshedModel = repository.getManagedModels().blockingGet().firstOrNull {
                            it.id == requestedModelId && it.installed && it.localPath.isNotBlank()
                        } ?: error("Model download completed but no installed model was found")

                        modelPath = refreshedModel.localPath
                        modelName = refreshedModel.displayName
                    }

                    prefs.edit()
                        .putString("onDeviceModelName", modelName)
                        .putString("onDeviceModelPath", modelPath)
                        .apply()
                }

                require(modelPath.isNotBlank()) { "Missing onDeviceModelPath preference" }
                require(File(modelPath).isFile) { "Model file not found at $modelPath" }

                val messages = listOf(
                    Message().apply {
                        type = "sms"
                        boxId = Telephony.Sms.MESSAGE_TYPE_INBOX
                        address = "+15551234567"
                        body = "Are you free for lunch tomorrow?"
                    },
                    Message().apply {
                        type = "sms"
                        boxId = Telephony.Sms.MESSAGE_TYPE_SENT
                        address = "+15551234567"
                        body = "Maybe. What time works best?"
                    }
                )

                val suggestions = repository.generateReplySuggestions(
                    modelPath = modelPath,
                    modelName = modelName,
                    conversationContext = messages,
                    persona = null
                ).blockingGet()

                val content = buildString {
                    appendLine("status=ok")
                    appendLine("modelId=$requestedModelId")
                    appendLine("modelName=$modelName")
                    appendLine("modelPath=$modelPath")
                    appendLine("downloaded=$downloaded")
                    appendLine("suggestions=${suggestions.joinToString(" | ")}")
                }

                resultFile.writeText(content)
                Log.i("DebugOnDeviceAi", content)
            } catch (t: Throwable) {
                val content = buildString {
                    appendLine("status=error")
                    appendLine("message=${t.message}")
                    appendLine(Log.getStackTraceString(t))
                }

                resultFile.writeText(content)
                Log.e("DebugOnDeviceAi", content)
            } finally {
                runOnUiThread { finish() }
            }
        }.start()
    }
}
