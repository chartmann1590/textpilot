package com.charles.messenger.util

import com.charles.messenger.model.Message
import com.charles.messenger.model.OllamaChatMessage
import timber.log.Timber

object AiPromptFormatter {

    fun buildChatMessages(messages: List<Message>, persona: String?): List<OllamaChatMessage> {
        return listOf(
            OllamaChatMessage(role = "system", content = buildSystemPrompt(persona)),
            OllamaChatMessage(role = "user", content = buildConversationContext(messages))
        )
    }

    fun buildSinglePrompt(messages: List<Message>, persona: String?): String {
        return buildString {
            appendLine(buildSystemPrompt(persona))
            appendLine()
            append(buildConversationContext(messages))
        }
    }

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

    private fun buildConversationContext(messages: List<Message>): String {
        if (messages.isEmpty()) {
            Timber.w("No messages provided for smart reply generation")
            return "Generate 3-5 short, friendly reply suggestions for a text message conversation."
        }

        val validMessages = messages.filter {
            val text = it.getText().trim()
            text.isNotEmpty() && text != "..." && text.length > 1
        }

        if (validMessages.isEmpty()) {
            Timber.w("No messages with valid content found")
            return "Generate 3-5 short, friendly reply suggestions for a text message conversation."
        }

        val recentMessages = validMessages.takeLast(4)
        val lastMessageFromThem = recentMessages.reversed().firstOrNull {
            !it.isMe() && it.getText().trim().isNotEmpty() && it.getText().trim() != "..."
        }

        if (lastMessageFromThem == null) {
            if (validMessages.all { it.isMe() }) {
                return "I cannot generate reply suggestions because all messages in this conversation are from me. I need a message from the other person to generate replies."
            }
            return "Generate 3-5 short, friendly reply suggestions for a text message conversation."
        }

        val conversationText = recentMessages.joinToString("\n") { message ->
            val sender = if (message.isMe()) "Me" else "Them"
            "$sender: ${message.getText().trim()}"
        }

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

    fun parseReplySuggestions(response: String): List<String> {
        val lowerResponse = response.lowercase()
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
                startIndex = if (marker == "1." || marker == "1)") markerIndex else markerIndex + marker.length
                break
            }
        }

        val relevantText = if (startIndex > 0) response.substring(startIndex).take(1000) else response.take(1000)
        val suggestions = mutableListOf<String>()
        val lines = relevantText.lines()
        val processedLines = mutableSetOf<Int>()

        lines.forEachIndexed { index, line ->
            if (index in processedLines) return@forEachIndexed

            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed

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
                (trimmed.startsWith("(") && trimmed.endsWith(")"))
            ) {
                return@forEachIndexed
            }

            val numberPrefixRegex = Regex("^\\d+[.)]\\s*(.+)")
            val match = numberPrefixRegex.find(trimmed) ?: return@forEachIndexed
            val labelOrSuggestion = match.groupValues[1].trim()

            var suggestion: String? = null
            val hasMarkdownBold = labelOrSuggestion.contains("**") || labelOrSuggestion.contains("__")
            val isLabel = labelOrSuggestion.endsWith(":") ||
                hasMarkdownBold ||
                (labelOrSuggestion.length < 50 &&
                    !labelOrSuggestion.contains("\"") &&
                    !labelOrSuggestion.contains("'"))

            if (isLabel) {
                var nextLineIndex = index + 1
                while (nextLineIndex < lines.size && nextLineIndex < index + 5) {
                    val nextLine = lines[nextLineIndex].trim()
                    if (nextLine.isEmpty()) {
                        nextLineIndex++
                        continue
                    }
                    if (nextLine.startsWith("(") && nextLine.endsWith(")")) {
                        nextLineIndex++
                        continue
                    }
                    if (numberPrefixRegex.find(nextLine) != null) break

                    suggestion = nextLine
                    processedLines.add(nextLineIndex)
                    break
                }
            } else {
                suggestion = labelOrSuggestion
            }

            suggestion ?: return@forEachIndexed
            suggestion = suggestion.removeSurrounding("\"").removeSurrounding("'").trim()

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
                lowerSuggestion.contains("[insert") ||
                lowerSuggestion.contains("[topic]") ||
                (lowerSuggestion.endsWith(":") &&
                    !lowerSuggestion.contains("\"") &&
                    !lowerSuggestion.contains("'")) ||
                (hasMarkdownBold &&
                    !suggestion.contains("\"") &&
                    !suggestion.contains("'"))
            ) {
                return@forEachIndexed
            }

            suggestion = suggestion.replace(Regex("\\([^)]*\\)"), "")
                .replace(Regex("\\[[^]]*\\]"), "")
                .trim()

            val cleanedSuggestion = cleanSuggestion(suggestion)
            if (cleanedSuggestion.isNotEmpty() && cleanedSuggestion.length > 3) {
                suggestions.add(cleanedSuggestion)
            }
        }

        if (suggestions.isEmpty()) {
            response.lines()
                .map { cleanSuggestion(it.trim()) }
                .filter { it.isNotEmpty() && it.length > 5 }
                .take(5)
                .forEach(suggestions::add)
        }

        return suggestions.take(5)
    }

    private fun cleanSuggestion(text: String): String {
        var cleaned = text
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("__([^_]+)__"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("_([^_]+)_"), "$1")
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSurrounding("\u201C", "\u201D")
            .removeSurrounding("\u2018", "\u2019")
            .removeSurrounding("\u00AB", "\u00BB")
            .trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019')

        val colonIndex = cleaned.indexOf(':')
        if (colonIndex > 0 && colonIndex < cleaned.length - 1) {
            val afterColon = cleaned.substring(colonIndex + 1).trim()
            if (afterColon.length > 3 && !afterColon.contains(':')) {
                cleaned = afterColon
            }
        }

        return cleaned.removeSurrounding("\"")
            .removeSurrounding("'")
            .trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019')
            .trim()
    }
}
