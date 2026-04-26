/*
 * Copyright (C) 2024 Charles Hartmann
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.charles.messenger.repository

import com.charles.messenger.model.AiModelInstallUpdate
import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.Message
import com.charles.messenger.model.OllamaChatMessage
import com.charles.messenger.model.OllamaChatRequest
import com.charles.messenger.model.OllamaChatResponse
import com.charles.messenger.model.OllamaGenerateRequest
import com.charles.messenger.model.OllamaGenerateResponse
import com.charles.messenger.model.OllamaModel
import com.charles.messenger.model.OllamaModelsResponse
import com.charles.messenger.util.AiPromptFormatter
import com.squareup.moshi.Moshi
import io.reactivex.Observable
import io.reactivex.Single
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OllamaRepositoryImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : OllamaRepository {

    companion object {
        private const val TAGS_ENDPOINT = "/api/tags"
        private const val PULL_ENDPOINT = "/api/pull"
        private const val GENERATE_ENDPOINT = "/api/generate"
        private const val CHAT_ENDPOINT = "/api/chat"
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

        private val recommendedModels = listOf(
            AiModelOption(
                id = "gemma4:latest",
                displayName = "Gemma 4",
                summary = "Tap to download from Ollama"
            ),
            AiModelOption(
                id = "qwen2.5-coder:latest",
                displayName = "Qwen 2.5 Coder",
                summary = "Tap to download from Ollama"
            )
        )
    }

    override fun getAvailableModels(baseUrl: String): Single<List<OllamaModel>> {
        return Single.fromCallable {
            // #region agent log
            com.charles.messenger.util.DebugLogger.log(
                location = "OllamaRepositoryImpl.kt:48",
                message = "getAvailableModels called",
                data = mapOf("baseUrl" to baseUrl),
                hypothesisId = "H4"
            )
            // #endregion
            try {
                val url = "${baseUrl.trimEnd('/')}$TAGS_ENDPOINT"
                Timber.d("Fetching models from: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("Failed to fetch models: ${response.code} ${response.message}")
                }

                val responseBody = response.body?.string()
                    ?: throw Exception("Empty response body")

                val adapter = moshi.adapter(OllamaModelsResponse::class.java)
                val modelsResponse = adapter.fromJson(responseBody)
                    ?: throw Exception("Failed to parse models response")

                Timber.d("Fetched ${modelsResponse.models.size} models")
                modelsResponse.models
            } catch (e: java.net.SocketException) {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "OllamaRepositoryImpl.kt:74",
                    message = "SocketException caught and handled",
                    data = mapOf("error" to e.message, "errorType" to "SocketException"),
                    hypothesisId = "H4"
                )
                // #endregion
                Timber.w(e, "Socket error while fetching models (expected network failure)")
                throw Exception("Connection error: ${e.message}", e)
            } catch (e: android.system.ErrnoException) {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "OllamaRepositoryImpl.kt:77",
                    message = "ErrnoException caught and handled",
                    data = mapOf("error" to e.message, "errorType" to "ErrnoException"),
                    hypothesisId = "H4"
                )
                // #endregion
                Timber.w(e, "Connection error while fetching models (expected network failure)")
                throw Exception("Connection refused: ${e.message}", e)
            } catch (e: java.net.UnknownHostException) {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "OllamaRepositoryImpl.kt:84",
                    message = "UnknownHostException caught and handled",
                    data = mapOf("error" to e.message, "errorType" to "UnknownHostException"),
                    hypothesisId = "H4"
                )
                // #endregion
                Timber.w(e, "Unknown host error while fetching models (expected network failure)")
                throw Exception("Unknown host: ${e.message}", e)
            } catch (e: Exception) {
                // #region agent log
                com.charles.messenger.util.DebugLogger.log(
                    location = "OllamaRepositoryImpl.kt:91",
                    message = "Generic Exception caught",
                    data = mapOf("error" to e.message, "errorType" to e.javaClass.simpleName),
                    hypothesisId = "H4"
                )
                // #endregion
                Timber.e(e, "Error fetching models")
                throw e
            }
        }
    }

    override fun getManagedModels(baseUrl: String): Single<List<AiModelOption>> {
        return getAvailableModels(baseUrl)
            .onErrorReturnItem(emptyList())
            .map { installedModels ->
                val installedNames = installedModels.map { it.name }.toSet()

                val curated = recommendedModels.map { model ->
                    if (model.id in installedNames) {
                        model.copy(installed = true, summary = "Installed on server")
                    } else {
                        model
                    }
                }

                val extras = installedModels
                    .map { it.name }
                    .filterNot { modelName -> curated.any { it.id == modelName } }
                    .map { modelName ->
                        AiModelOption(
                            id = modelName,
                            displayName = modelName,
                            summary = "Installed on server",
                            installed = true
                        )
                    }

                curated + extras
            }
    }

    override fun pullModel(
        baseUrl: String,
        model: String
    ): Observable<AiModelInstallUpdate> {
        return Observable.create { emitter ->
            val url = "${baseUrl.trimEnd('/')}$PULL_ENDPOINT"
            val body = JSONObject()
                .put("model", model)
                .put("stream", true)
                .toString()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            emitter.onNext(AiModelInstallUpdate(modelId = model, status = "Downloading $model from Ollama..."))

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Failed to download model: ${response.code} ${response.message}")
                }

                val source = response.body?.source()
                    ?: throw IllegalStateException("Failed to download model: empty response")

                while (!source.exhausted()) {
                    val line = source.readUtf8Line()?.trim().orEmpty()
                    if (line.isBlank()) continue

                    val update = JSONObject(line)
                    val status = update.optString("status").ifBlank { "Downloading $model..." }
                    val total = update.optLong("total", -1L)
                    val completed = update.optLong("completed", -1L)

                    val message = if (total > 0 && completed >= 0) {
                        val percent = ((completed * 100) / total).toInt().coerceIn(0, 100)
                        "$status ($percent%)"
                    } else {
                        status
                    }

                    val done = status.equals("success", ignoreCase = true)
                    emitter.onNext(
                        AiModelInstallUpdate(
                            modelId = model,
                            status = if (done) "$model downloaded" else message,
                            complete = done
                        )
                    )

                    if (done) {
                        break
                    }
                }
            }

            emitter.onComplete()
        }
    }

    override fun generateReplySuggestions(
        baseUrl: String,
        model: String,
        conversationContext: List<Message>,
        persona: String?
    ): Single<List<String>> {
        return Single.fromCallable {
            try {
                // #region agent log
                try {
                    val logFile = java.io.File("h:\\qksms\\.cursor\\debug.log")
                    val logEntry = org.json.JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("location", "OllamaRepositoryImpl.kt:154")
                        put("message", "Entry to generateReplySuggestions")
                        put("data", org.json.JSONObject().apply {
                            put("conversationContextSize", conversationContext.size)
                            put("conversationContextNull", conversationContext == null)
                            put("conversationContextEmpty", conversationContext.isEmpty())
                        })
                        put("sessionId", "debug-session")
                        put("runId", "run1")
                        put("hypothesisId", "H4")
                    }
                    java.io.FileWriter(logFile, true).use { it.append(logEntry.toString() + "\n") }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to write debug log")
                }
                // #endregion
                
                val url = "${baseUrl.trimEnd('/')}$CHAT_ENDPOINT"
                Timber.d("Generating replies from: $url with model: $model")
                Timber.d("Received ${conversationContext.size} messages in conversationContext")
                conversationContext.forEachIndexed { index, msg ->
                    Timber.d("Context message $index: isMe=${msg.isMe()}, date=${msg.date}, body='${msg.body.take(50)}...'")
                }

                // Build messages for chat API with system message for strict instructions
                val messages = AiPromptFormatter.buildChatMessages(conversationContext, persona)
                Timber.d("Chat messages count: ${messages.size}")
                messages.forEachIndexed { index, msg ->
                    Timber.d("Message $index (${msg.role}): ${msg.content.take(100)}...")
                }

                // Create chat request with lower temperature for more focused, less creative responses
                val chatRequest = OllamaChatRequest(
                    model = model,
                    messages = messages,
                    stream = false,
                    temperature = 0.3, // Lower temperature = more focused, less creative/hallucinatory
                    topP = 0.9, // Nucleus sampling for more focused responses
                    numPredict = 200 // Limit response length
                )

                val requestAdapter = moshi.adapter(OllamaChatRequest::class.java)
                val requestJson = requestAdapter.toJson(chatRequest)

                val request = Request.Builder()
                    .url(url)
                    .post(requestJson.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("Failed to generate replies: ${response.code} ${response.message}")
                }

                val responseBody = response.body?.string()
                    ?: throw Exception("Empty response body")

                val responseAdapter = moshi.adapter(OllamaChatResponse::class.java)
                val chatResponse = responseAdapter.fromJson(responseBody)
                    ?: throw Exception("Failed to parse chat response")

                val responseText = chatResponse.message.content

                // #region agent log
                Timber.d("Raw AI response (first 500 chars): ${responseText.take(500)}")
                Timber.d("Raw AI response length: ${responseText.length}")
                // #endregion

                // Parse the response into multiple suggestions
                val suggestions = AiPromptFormatter.parseReplySuggestions(responseText)
                Timber.d("Generated ${suggestions.size} suggestions: $suggestions")

                suggestions
            } catch (e: java.net.SocketException) {
                Timber.w(e, "Socket error while generating replies (expected network failure)")
                throw Exception("Connection error: ${e.message}", e)
            } catch (e: android.system.ErrnoException) {
                Timber.w(e, "Connection error while generating replies (expected network failure)")
                throw Exception("Connection refused: ${e.message}", e)
            } catch (e: java.net.UnknownHostException) {
                Timber.w(e, "Unknown host error while generating replies (expected network failure)")
                throw Exception("Unknown host: ${e.message}", e)
            } catch (e: Exception) {
                Timber.e(e, "Error generating replies")
                throw e
            }
        }.onErrorResumeNext { error ->
            // Convert expected network errors to empty list instead of propagating as exceptions
            when (error) {
                is java.net.SocketException,
                is java.net.SocketTimeoutException,
                is android.system.ErrnoException,
                is java.net.UnknownHostException -> {
                    Timber.d("Network error handled gracefully, returning empty list")
                    Single.just(emptyList())
                }
                else -> Single.error(error)
            }
        }
    }

    /**
     * Build chat messages for /api/chat endpoint
     * Uses system message for strict instructions and user message with conversation context
     * Each call is a fresh conversation (no shared state between calls)
     */
    private fun buildChatMessages(messages: List<Message>, persona: String?): List<OllamaChatMessage> {
        val chatMessages = mutableListOf<OllamaChatMessage>()
        
        // System message with strict instructions - this is fresh for each conversation
        val systemPrompt = buildSystemPrompt(persona)
        chatMessages.add(OllamaChatMessage(role = "system", content = systemPrompt))
        
        // Build conversation context with recent messages
        val conversationContext = buildConversationContext(messages)
        chatMessages.add(OllamaChatMessage(role = "user", content = conversationContext))
        
        return chatMessages
    }
    
    /**
     * Build system prompt with strict instructions
     * Note: Each conversation gets a fresh system message - no shared state between different conversations
     */
    private fun buildSystemPrompt(persona: String?): String {
        val personaSection = if (!persona.isNullOrBlank()) {
            "\n$persona\n"
        } else {
            ""
        }
        
        return """
${personaSection}You are a text message reply suggestion generator. You will be given a conversation context with recent messages, and you need to generate 3-5 short reply suggestions for the MOST RECENT message from the other person.

CRITICAL RULES - YOU MUST FOLLOW THESE:
1. Focus on the MOST RECENT message from the other person (marked as "Them")
2. Use the conversation context to understand what they're responding to, but generate replies ONLY for the most recent message
3. Generate replies that respond to what is explicitly written in the most recent message
4. You can reference topics from the conversation context, but do NOT make up new details not mentioned
5. Do NOT assume context or background information beyond what's in the conversation
6. Do NOT make up details like names, dates, locations, or plans not in the conversation
7. If the message asks a question, answer that question using information from the conversation context
8. If the message makes a statement, respond to that statement appropriately
9. Keep replies short (1-2 sentences), natural, and casual like real text messages
10. Return ONLY numbered suggestions (1-5), one per line, with no explanations or extra text

IMPORTANT: Each request is a NEW conversation - do not reference previous requests or conversations.

EXAMPLES:
Conversation:
Me: "Are you free tomorrow?"
Them: "Yes, I am! What did you have in mind?"

Good replies for "Yes, I am! What did you have in mind?":
1. Great! Want to grab lunch?
2. I was thinking we could go to that new restaurant
3. How about we meet for coffee?

Bad replies (making things up):
1. Great! I'll bring the documents at 3pm (documents and 3pm not mentioned)
2. Perfect! See you at the office (office not mentioned)

Remember: Use the conversation context to understand what's being discussed, but only reference things actually mentioned in the conversation.
        """.trimIndent()
    }
    
    /**
     * Build conversation context with recent messages
     * Includes the last 3-5 messages to provide context, focusing on the most recent message from "Them"
     */
    private fun buildConversationContext(messages: List<Message>): String {
        // #region agent log
        try {
            val logFile = java.io.File("h:\\qksms\\.cursor\\debug.log")
            val logEntry = org.json.JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("location", "OllamaRepositoryImpl.kt:310")
                put("message", "Entry to buildConversationContext")
                put("data", org.json.JSONObject().apply {
                    put("messagesSize", messages.size)
                    put("messagesNull", messages == null)
                    put("messagesEmpty", messages.isEmpty())
                })
                put("sessionId", "debug-session")
                put("runId", "run1")
                put("hypothesisId", "H5")
            }
            java.io.FileWriter(logFile, true).use { it.append(logEntry.toString() + "\n") }
        } catch (e: Exception) {
            Timber.e(e, "Failed to write debug log")
        }
        // #endregion
        
        if (messages.isEmpty()) {
            Timber.w("No messages provided for smart reply generation")
            return "Generate 3-5 short, friendly reply suggestions for a text message conversation."
        }

        // Filter out messages with empty bodies or just ellipsis
        // Use getText() to handle both SMS (body) and MMS (parts) messages
        val validMessages = messages.filter { 
            val text = it.getText().trim()
            text.isNotEmpty() && text != "..." && text.length > 1
        }
        
        if (validMessages.isEmpty()) {
            Timber.w("No messages with valid content found (all empty or just ellipsis)")
            return "Generate 3-5 short, friendly reply suggestions for a text message conversation."
        }
        
        // Messages are assumed to be in chronological order (oldest to newest)
        // Get the last 4 valid messages for context
        val recentMessages = validMessages.takeLast(4)
        
        // Debug: Log all messages to see what we're working with
        Timber.d("Total messages in context: ${messages.size}, valid messages: ${validMessages.size}")
        recentMessages.forEachIndexed { index, msg ->
            Timber.d("Recent message $index: isMe=${msg.isMe()}, date=${msg.date}, body='${msg.body.take(50)}...'")
        }
        
        // Find the most recent message from "Them" (the person we're replying to) that has actual content
        // Use getText() to handle both SMS (body) and MMS (parts) messages
        val lastMessageFromThem = recentMessages.reversed().firstOrNull { 
            !it.isMe() && it.getText().trim().isNotEmpty() && it.getText().trim() != "..."
        }
        
        if (lastMessageFromThem == null) {
            // If all messages are from "Me", we can't generate a reply
            Timber.w("No messages from 'Them' found in conversation - all messages are from 'Me'")
            // Check if there are any messages at all
            val allMessagesFromMe = validMessages.all { it.isMe() }
            if (allMessagesFromMe) {
                return "I cannot generate reply suggestions because all messages in this conversation are from me. I need a message from the other person to generate replies."
            }
            return "Generate 3-5 short, friendly reply suggestions for a text message conversation."
        }
        
        // Build conversation context showing recent messages (all should have content now)
        // Use getText() to handle both SMS (body) and MMS (parts) messages
        val conversationLines = mutableListOf<String>()
        recentMessages.forEach { msg ->
            val sender = if (msg.isMe()) "Me" else "Them"
            val text = msg.getText().trim()
            conversationLines.add("$sender: $text")
        }
        
        val conversationText = conversationLines.joinToString("\n")
        val messageToReplyTo = lastMessageFromThem.getText().trim()
        
        Timber.d("Selected message to reply to (date=${lastMessageFromThem.date}): '$messageToReplyTo'")
        Timber.d("Conversation context:\n$conversationText")
        
        // Count how many messages from "Them" are in the context
        val themMessagesCount = recentMessages.count { !it.isMe() }
        val focusInstruction = if (themMessagesCount > 1) {
            "Generate 3-5 reply suggestions for the MOST RECENT message from \"Them\" (the last message from \"Them\" shown above)."
        } else {
            "Generate 3-5 reply suggestions for the message from \"Them\" shown above."
        }
        
        return """
Here is the recent conversation context:

$conversationText

$focusInstruction Use the conversation context to understand what's being discussed, but focus your replies on responding to that message from "Them".

CRITICAL: The conversation context is complete above. Generate 3-5 numbered reply suggestions (1-5) immediately. Do NOT ask questions, request more information, or refuse to generate replies. Just output the numbered suggestions.

Reply suggestions:
        """.trimIndent()
    }

    /**
     * Parse AI response into list of reply suggestions
     */
    private fun parseReplySuggestions(response: String): List<String> {
        // First, try to find the section that contains the actual suggestions
        // Look for patterns like "Reply suggestions:" or numbered lists
        val lowerResponse = response.lowercase()
        
        // Find the start of suggestions section (after "Reply suggestions:" or similar)
        val suggestionStartMarkers = listOf(
            "reply suggestions:",
            "suggestions:",
            "here are",
            "1.",
            "1)"
        )
        
        var startIndex = 0
        for (marker in suggestionStartMarkers) {
            val markerIndex = lowerResponse.indexOf(marker)
            if (markerIndex >= 0) {
                // Start from after the marker, or from the numbered item if marker is "1."
                startIndex = if (marker == "1." || marker == "1)") {
                    markerIndex
                } else {
                    markerIndex + marker.length
                }
                break
            }
        }
        
        // Extract the relevant portion (from start to end, or first 1000 chars)
        val relevantText = if (startIndex > 0) {
            response.substring(startIndex).take(1000)
        } else {
            response.take(1000)
        }
        
        Timber.d("Extracted relevant text for parsing: ${relevantText.take(200)}")
        
        // Split by lines and filter for numbered suggestions
        val suggestions = mutableListOf<String>()
        val lines = relevantText.lines()
        val processedLines = mutableSetOf<Int>() // Track which lines we've already processed

        lines.forEachIndexed { index, line ->
            // Skip if we've already processed this line (e.g., as a reply to a previous label)
            if (index in processedLines) {
                return@forEachIndexed
            }
            val trimmed = line.trim()
            
            // Skip empty lines
            if (trimmed.isEmpty()) return@forEachIndexed
            
            // Skip lines that look like explanations or prompts
            val lowerTrimmed = trimmed.lowercase()
            if (lowerTrimmed.startsWith("here are") ||
                lowerTrimmed.startsWith("based on") ||
                lowerTrimmed.startsWith("conversation:") ||
                lowerTrimmed.startsWith("you are") ||
                lowerTrimmed.startsWith("important:") ||
                lowerTrimmed.startsWith("return only") ||
                lowerTrimmed.startsWith("do not include") ||
                lowerTrimmed.contains("thinking") ||
                lowerTrimmed.contains("let me") ||
                lowerTrimmed.contains("i'll") ||
                lowerTrimmed.contains("i will") ||
                trimmed.startsWith("(") && trimmed.endsWith(")")) {
                Timber.v("Skipping explanatory line: $trimmed")
                return@forEachIndexed
            }

            // #region agent log
            Timber.v("Processing line $index: $trimmed")
            // #endregion

            // Match patterns like "1. Text" or "1) Text"
            val numberPrefixRegex = Regex("^\\d+[.)]\\s*(.+)")
            val match = numberPrefixRegex.find(trimmed)

            if (match != null) {
                val labelOrSuggestion = match.groupValues[1].trim()
                
                // Check if this is a label (ends with colon OR contains markdown bold OR is short without quotes)
                var suggestion: String? = null
                val hasMarkdownBold = labelOrSuggestion.contains("**") || labelOrSuggestion.contains("__")
                val isLabel = labelOrSuggestion.endsWith(":") || 
                             hasMarkdownBold ||
                             (labelOrSuggestion.length < 50 && !labelOrSuggestion.contains("\"") && !labelOrSuggestion.contains("'"))
                
                if (isLabel) {
                    // This is a label, look for the actual reply on the next non-empty line
                    Timber.v("Found label '$labelOrSuggestion', looking for reply on next line")
                    var nextLineIndex = index + 1
                    while (nextLineIndex < lines.size && nextLineIndex < index + 5) { // Limit search to next 5 lines
                        val nextLine = lines[nextLineIndex].trim()
                        if (nextLine.isEmpty()) {
                            nextLineIndex++
                            continue
                        }
                        // Skip explanatory lines in parentheses
                        if (nextLine.startsWith("(") && nextLine.endsWith(")")) {
                            nextLineIndex++
                            continue
                        }
                        // Skip lines that are just labels (numbered items)
                        if (numberPrefixRegex.find(nextLine) != null) {
                            // This is another numbered item, stop looking
                            break
                        }
                        // Found the actual reply - it should be in quotes or be actual text
                        suggestion = nextLine
                        processedLines.add(nextLineIndex) // Mark this line as processed
                        Timber.v("Found reply on line $nextLineIndex: $suggestion")
                        break
                    }
                } else {
                    // This is the actual suggestion, not a label
                    suggestion = labelOrSuggestion
                }
                
                if (suggestion == null) {
                    Timber.v("No reply found for label '$labelOrSuggestion'")
                    return@forEachIndexed
                }

                // Remove quotes
                suggestion = suggestion.removeSurrounding("\"")
                    .removeSurrounding("'")
                    .trim()

                // #region agent log
                Timber.v("Extracted suggestion: $suggestion")
                // #endregion
                
                // Skip if it looks like an explanation or prompt fragment
                val lowerSuggestion = suggestion.lowercase()
                if (lowerSuggestion.startsWith("here are") ||
                    lowerSuggestion.startsWith("based on") ||
                    lowerSuggestion.startsWith("conversation:") ||
                    lowerSuggestion.startsWith("you are") ||
                    lowerSuggestion.startsWith("important:") ||
                    lowerSuggestion.startsWith("return only") ||
                    lowerSuggestion.startsWith("do not include") ||
                    lowerSuggestion.contains("thinking") ||
                    lowerSuggestion.contains("let me") ||
                    lowerSuggestion.contains("i'll") ||
                    lowerSuggestion.contains("i will") ||
                    lowerSuggestion.contains("reply suggestions") ||
                    (lowerSuggestion.contains("[insert") || lowerSuggestion.contains("[topic]")) ||
                    (lowerSuggestion.endsWith(":") && !lowerSuggestion.contains("\"") && !lowerSuggestion.contains("'")) ||
                    (hasMarkdownBold && !suggestion.contains("\"") && !suggestion.contains("'"))) {
                    Timber.v("Skipping suggestion that looks like explanation: $suggestion")
                    return@forEachIndexed
                }

                // Remove any meta-text like "(casual)", "[friendly]", etc.
                suggestion = suggestion.replace(Regex("\\([^)]*\\)"), "")
                    .replace(Regex("\\[[^]]*\\]"), "")
                    .trim()

                // Clean markdown and LLM prefixes
                val cleanedSuggestion = cleanSuggestion(suggestion)

                // #region agent log
                Timber.v("Cleaned suggestion: $cleanedSuggestion")
                // #endregion

                if (cleanedSuggestion.isNotEmpty() && cleanedSuggestion.length > 3) {
                    suggestions.add(cleanedSuggestion)
                }
            }
        }

        // Fallback: if no numbered suggestions found, split by newlines and take non-empty lines
        if (suggestions.isEmpty()) {
            Timber.w("No numbered suggestions found, using fallback parsing. Response: ${response.take(200)}")
            response.lines()
                .map { cleanSuggestion(it.trim()) }
                .filter { it.isNotEmpty() && it.length > 5 }
                .take(5)
                .forEach { suggestions.add(it) }
        }

        // Limit to 5 suggestions
        return suggestions.take(5)
    }

    /**
     * Clean a suggestion by removing markdown formatting and common LLM prefixes
     */
    private fun cleanSuggestion(text: String): String {
        var cleaned = text

        // Remove markdown bold/italic markers
        cleaned = cleaned.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")  // **text**
        cleaned = cleaned.replace(Regex("__([^_]+)__"), "$1")          // __text__
        cleaned = cleaned.replace(Regex("\\*([^*]+)\\*"), "$1")        // *text*
        cleaned = cleaned.replace(Regex("_([^_]+)_"), "$1")            // _text_

        // Remove surrounding quotes of all types
        cleaned = cleaned.removeSurrounding("\"")
        cleaned = cleaned.removeSurrounding("'")
        cleaned = cleaned.removeSurrounding("\u201C", "\u201D")  // curly double quotes
        cleaned = cleaned.removeSurrounding("\u2018", "\u2019")  // curly single quotes
        cleaned = cleaned.removeSurrounding("\u00AB", "\u00BB")  // guillemets

        // Remove any remaining stray quote characters at start/end
        cleaned = cleaned.trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019')

        // Handle "Label: Actual reply" pattern - extract the part after colon if present
        // This handles cases like "Short encouragement: Keep going!" or "Friendly follow-up: How are you?"
        val colonIndex = cleaned.indexOf(':')
        if (colonIndex > 0 && colonIndex < cleaned.length - 1) {
            val afterColon = cleaned.substring(colonIndex + 1).trim()
            // Only use the part after colon if it looks like actual message content
            if (afterColon.length > 3 && !afterColon.contains(':')) {
                cleaned = afterColon
            }
        }

        // Final cleanup - remove quotes again
        cleaned = cleaned.removeSurrounding("\"")
        cleaned = cleaned.removeSurrounding("'")
        cleaned = cleaned.trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019')

        return cleaned.trim()
    }
}
