package com.google.ai.edge.litertlm

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class Conversation(private val handle: Long) : AutoCloseable {
    private val isAlive = AtomicBoolean(true)

    fun sendMessage(text: String): Message {
        check(isAlive.get()) { "Conversation is not alive." }

        val messageJson = JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", text)
                )
            )

        val responseJson = LiteRtLmJni.nativeSendMessage(handle, messageJson.toString(), "{}")
        return Message(extractText(responseJson))
    }

    override fun close() {
        if (isAlive.compareAndSet(true, false)) {
            LiteRtLmJni.nativeDeleteConversation(handle)
        } else {
            throw IllegalStateException("Conversation is closed already.")
        }
    }

    private fun extractText(responseJson: String): String {
        val parsed = JSONObject(responseJson)
        val content = parsed.optJSONArray("content") ?: return ""
        val builder = StringBuilder()

        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            if (item.optString("type") == "text") {
                builder.append(item.optString("text"))
            }
        }

        return builder.toString()
    }
}

class Message(private val text: String) {
    override fun toString(): String = text
}
