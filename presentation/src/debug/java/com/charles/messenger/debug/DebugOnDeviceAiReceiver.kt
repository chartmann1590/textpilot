package com.charles.messenger.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.charles.messenger.model.Message
import com.charles.messenger.repository.OnDeviceLlmRepositoryImpl
import okhttp3.OkHttpClient
import java.io.File

class DebugOnDeviceAiReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        Thread {
            val resultFile = File(context.cacheDir, "on_device_ai_e2e_result.txt")

            try {
                val prefs = context.getSharedPreferences(
                    "com.charles.messenger.v2_preferences",
                    Context.MODE_PRIVATE
                )

                val modelPath = prefs.getString("onDeviceModelPath", "").orEmpty()
                val modelName = prefs.getString("onDeviceModelName", "").orEmpty()

                require(modelPath.isNotBlank()) { "Missing onDeviceModelPath preference" }
                require(File(modelPath).isFile) { "Model file not found at $modelPath" }

                val repository = OnDeviceLlmRepositoryImpl(
                    context = context,
                    okHttpClient = OkHttpClient()
                )

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
                    appendLine("modelName=$modelName")
                    appendLine("modelPath=$modelPath")
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
                pendingResult.finish()
            }
        }.start()
    }
}
