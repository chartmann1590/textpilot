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

import android.content.Context
import android.util.Base64
import com.charles.messenger.manager.CredentialManager
import com.charles.messenger.model.Conversation
import com.charles.messenger.model.Message
import com.charles.messenger.util.Preferences
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.reactivex.Flowable
import io.reactivex.schedulers.Schedulers
import io.reactivex.Single
import io.realm.Realm
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSyncRepositoryImpl @Inject constructor(
    private val context: Context,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val preferences: Preferences,
    private val credentialManager: CredentialManager
) : WebSyncRepository {

    companion object {
        private const val BATCH_SIZE = 100
        private const val TIMEOUT_SECONDS = 60L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var refreshToken: String? = null

    override fun register(serverUrl: String, username: String, password: String): Single<Boolean> {
        return Single.fromCallable {
            try {
                val registerUrl = "${serverUrl.trimEnd('/')}/api/auth/register"
                val deviceId = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )

                val requestBody = RegisterRequest(username, password, deviceId)
                val json = moshi.adapter(RegisterRequest::class.java).toJson(requestBody)
                Timber.d("Sending registration request to $registerUrl")

                val request = Request.Builder()
                    .url(registerUrl)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val registerResponse = moshi.adapter(RegisterResponse::class.java).fromJson(responseBody)
                    Timber.d("Registration successful: ${registerResponse?.message}")
                    true
                } else {
                    Timber.w("Registration failed: ${response.code} - $responseBody")
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "Registration failed")
                false
            }
        }.subscribeOn(Schedulers.io())
    }

    override fun testConnection(serverUrl: String, username: String, password: String): Single<Boolean> {
        return Single.fromCallable {
            try {
                val loginUrl = "${serverUrl.trimEnd('/')}/api/auth/login"
                val deviceId = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )

                val requestBody = LoginRequest(username, password, deviceId)
                val json = moshi.adapter(LoginRequest::class.java).toJson(requestBody)
                Timber.d("Sending login request to $loginUrl with JSON: $json")

                val request = Request.Builder()
                    .url(loginUrl)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    Timber.d("Login successful, received tokens")
                    true
                } else {
                    Timber.w("Login failed: ${response.code} - $responseBody")
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "Connection test failed")
                false
            }
        }.subscribeOn(Schedulers.io())
    }

    override fun performFullSync(): Flowable<WebSyncRepository.SyncProgress> {
        return Flowable.create({ emitter ->
            try {
                // Authenticate
                emitter.onNext(WebSyncRepository.SyncProgress(
                    WebSyncRepository.SyncProgress.Stage.AUTHENTICATING, 0, 1
                ))

                if (!authenticate()) {
                    emitter.onNext(WebSyncRepository.SyncProgress(
                        WebSyncRepository.SyncProgress.Stage.ERROR, 0, 0, "Authentication failed"
                    ))
                    emitter.onComplete()
                    return@create
                }

                // Get all conversations
                emitter.onNext(WebSyncRepository.SyncProgress(
                    WebSyncRepository.SyncProgress.Stage.SYNCING_CONVERSATIONS, 0, 1
                ))

                val realm = Realm.getDefaultInstance()
                val conversations = realm.where(Conversation::class.java).findAll()
                Timber.d("Found ${conversations.size} conversations to sync")

                // Get all messages
                val messages = realm.where(Message::class.java).findAll()
                Timber.d("Found ${messages.size} messages to sync")

                val totalBatches = (messages.size + BATCH_SIZE - 1) / BATCH_SIZE
                Timber.d("Will sync in $totalBatches batches")

                // Sync messages in batches
                messages.chunked(BATCH_SIZE).forEachIndexed { index, batch ->
                    emitter.onNext(WebSyncRepository.SyncProgress(
                        WebSyncRepository.SyncProgress.Stage.SYNCING_MESSAGES,
                        index + 1,
                        totalBatches
                    ))

                    syncMessageBatch(conversations.toList(), batch, index + 1, totalBatches)
                    Thread.sleep(500) // Small delay between batches
                }

                realm.close()

                // Update sync timestamp
                preferences.webSyncLastFullSync.set(System.currentTimeMillis())

                emitter.onNext(WebSyncRepository.SyncProgress(
                    WebSyncRepository.SyncProgress.Stage.COMPLETE, 1, 1
                ))
                emitter.onComplete()
            } catch (e: Exception) {
                Timber.e(e, "Full sync failed")
                emitter.onNext(WebSyncRepository.SyncProgress(
                    WebSyncRepository.SyncProgress.Stage.ERROR, 0, 0, e.message ?: "Unknown error"
                ))
                emitter.onError(e)
            }
        }, io.reactivex.BackpressureStrategy.BUFFER)
    }

    override fun performIncrementalSync(): Flowable<WebSyncRepository.SyncProgress> {
        return Flowable.create({ emitter ->
            try {
                emitter.onNext(WebSyncRepository.SyncProgress(
                    WebSyncRepository.SyncProgress.Stage.AUTHENTICATING, 0, 1
                ))

                if (!authenticate()) {
                    emitter.onNext(WebSyncRepository.SyncProgress(
                        WebSyncRepository.SyncProgress.Stage.ERROR, 0, 0, "Authentication failed"
                    ))
                    emitter.onComplete()
                    return@create
                }

                // Get messages since last sync
                val lastSyncTime = preferences.webSyncLastIncrementalSync.get()
                val realm = Realm.getDefaultInstance()
                val newMessages = realm.where(Message::class.java)
                    .greaterThan("date", lastSyncTime)
                    .findAll()

                if (newMessages.isEmpty()) {
                    Timber.d("No new messages to sync")
                    emitter.onNext(WebSyncRepository.SyncProgress(
                        WebSyncRepository.SyncProgress.Stage.COMPLETE, 1, 1
                    ))
                    emitter.onComplete()
                    realm.close()
                    return@create
                }

                Timber.d("Found ${newMessages.size} new messages to sync")

                emitter.onNext(WebSyncRepository.SyncProgress(
                    WebSyncRepository.SyncProgress.Stage.SYNCING_MESSAGES, 0, 1
                ))

                val conversations = realm.where(Conversation::class.java).findAll()
                syncIncrementalMessages(conversations.toList(), newMessages.toList())

                realm.close()

                preferences.webSyncLastIncrementalSync.set(System.currentTimeMillis())

                emitter.onNext(WebSyncRepository.SyncProgress(
                    WebSyncRepository.SyncProgress.Stage.COMPLETE, 1, 1
                ))
                emitter.onComplete()
            } catch (e: Exception) {
                Timber.e(e, "Incremental sync failed")

                // Check if this is an invalid sync token error (500 or 400 status)
                val errorMessage = e.message ?: ""
                if (errorMessage.contains("500") ||
                    errorMessage.contains("400") ||
                    errorMessage.contains("Invalid sync token", ignoreCase = true) ||
                    errorMessage.contains("Sync token required", ignoreCase = true)) {
                    Timber.w("Invalid or missing sync token detected, clearing token and triggering full sync")

                    // Clear the invalid sync token
                    preferences.webSyncToken.set("")

                    // Notify that we're recovering
                    emitter.onNext(WebSyncRepository.SyncProgress(
                        WebSyncRepository.SyncProgress.Stage.SYNCING_MESSAGES, 0, 1, "Recovering via full sync"
                    ))

                    // Trigger a full sync to recover - use blockingLast() to wait for completion
                    try {
                        performFullSync().blockingLast()
                        Timber.i("Automatic full sync completed successfully after invalid token")

                        // Complete successfully after recovery
                        emitter.onNext(WebSyncRepository.SyncProgress(
                            WebSyncRepository.SyncProgress.Stage.COMPLETE, 1, 1, "Recovered via full sync"
                        ))
                        emitter.onComplete()
                        return@create
                    } catch (fullSyncError: Exception) {
                        Timber.e(fullSyncError, "Full sync recovery failed")
                        emitter.onNext(WebSyncRepository.SyncProgress(
                            WebSyncRepository.SyncProgress.Stage.ERROR, 0, 0, "Recovery failed: ${fullSyncError.message}"
                        ))
                        emitter.onError(fullSyncError)
                        return@create
                    }
                }

                // For other errors, emit the error normally
                emitter.onNext(WebSyncRepository.SyncProgress(
                    WebSyncRepository.SyncProgress.Stage.ERROR, 0, 0, e.message ?: "Unknown error"
                ))
                emitter.onError(e)
            }
        }, io.reactivex.BackpressureStrategy.BUFFER)
    }

    override fun fetchQueuedMessages(): Single<List<WebSyncRepository.QueuedMessage>> {
        return Single.fromCallable {
            try {
                if (!authenticate()) {
                    return@fromCallable emptyList<WebSyncRepository.QueuedMessage>()
                }

                val url = "${getServerUrl()}/api/sync/queue"
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val queueResponse = moshi.adapter(QueueResponse::class.java).fromJson(responseBody)
                    queueResponse?.queuedMessages?.map {
                        WebSyncRepository.QueuedMessage(
                            queueId = it.id,
                            conversationId = it.conversationId?.toLongOrNull(),
                            addresses = it.addresses,
                            body = it.body
                        )
                    } ?: emptyList()
                } else {
                    Timber.w("Failed to fetch queued messages: ${response.code}")
                    emptyList()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error fetching queued messages")
                emptyList()
            }
        }
    }

    override fun confirmMessageSent(queueId: String, androidMessageId: Long): Single<Boolean> {
        return Single.fromCallable {
            try {
                if (!authenticate()) {
                    return@fromCallable false
                }

                val url = "${getServerUrl()}/api/sync/confirm"
                val requestBody = ConfirmRequest(queueId, androidMessageId.toString())
                val json = moshi.adapter(ConfirmRequest::class.java).toJson(requestBody)

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                Timber.e(e, "Error confirming message sent")
                false
            }
        }
    }

    private fun authenticate(): Boolean {
        try {
            val serverUrl = preferences.webSyncServerUrl.get()
            val username = preferences.webSyncUsername.get()
            val password = credentialManager.getPassword()

            if (serverUrl.isEmpty() || username.isEmpty() || password.isNullOrEmpty()) {
                Timber.w("Missing credentials for authentication")
                return false
            }

            val loginUrl = "$serverUrl/api/auth/login"
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )

            val requestBody = LoginRequest(username, password, deviceId)
            val json = moshi.adapter(LoginRequest::class.java).toJson(requestBody)

            val request = Request.Builder()
                .url(loginUrl)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val loginResponse = moshi.adapter(LoginResponse::class.java).fromJson(responseBody)
                accessToken = loginResponse?.accessToken
                refreshToken = loginResponse?.refreshToken
                Timber.d("Authentication successful")
                return true
            } else {
                Timber.w("Authentication failed: ${response.code} - $responseBody")
                return false
            }
        } catch (e: Exception) {
            Timber.e(e, "Authentication error")
            return false
        }
    }

    private fun syncMessageBatch(
        conversations: List<Conversation>,
        messages: List<Message>,
        batchNumber: Int,
        totalBatches: Int
    ) {
        try {
            val url = "${getServerUrl()}/api/sync/initial"

            val conversationDtos = conversations.map { conv ->
                val convId = sanitizeThreadId(conv.id)
                Timber.d("Conv ID: ${conv.id} -> $convId")
                ConversationDto(
                    id = convId,
                    recipients = conv.recipients.map { recipient ->
                        RecipientDto(
                            address = recipient.address,
                            contactName = recipient.contact?.name
                        )
                    },
                    name = conv.name,
                    archived = conv.archived,
                    blocked = conv.blocked,
                    pinned = conv.pinned
                )
            }

            val messageDtos = messages.mapNotNull { msg ->
                val msgId = msg.id.toString()
                val threadId = sanitizeThreadId(msg.threadId)
                val date = msg.date.toString()
                val dateSent = msg.dateSent?.toString() ?: ""

                // Validate all numeric fields
                if (threadId.isEmpty() || !threadId.matches(Regex("^\\d+$"))) {
                    Timber.w("Skipping message ${msg.id}: invalid threadId: ${msg.threadId} -> $threadId")
                    return@mapNotNull null
                }
                if (msgId.isEmpty() || !msgId.matches(Regex("^\\d+$"))) {
                    Timber.w("Skipping message ${msg.id}: invalid msgId: $msgId")
                    return@mapNotNull null
                }
                if (date.isEmpty() || !date.matches(Regex("^-?\\d+$"))) {
                    Timber.w("Skipping message ${msg.id}: invalid date: $date")
                    return@mapNotNull null
                }
                if (dateSent.isNotEmpty() && !dateSent.matches(Regex("^-?\\d+$"))) {
                    Timber.w("Skipping message ${msg.id}: invalid dateSent: $dateSent")
                    return@mapNotNull null
                }

                MessageDto(
                    id = msgId,
                    threadId = threadId,
                    address = msg.address,
                    body = msg.getText(), // Use getText() to get text from both SMS and MMS
                    type = msg.type,
                    date = date,
                    dateSent = if (dateSent.isNotEmpty()) dateSent else null,
                    read = msg.read,
                    seen = msg.seen,
                    isMe = msg.isMe(),
                    attachments = msg.parts.map { part ->
                        AttachmentDto(
                            type = part.type ?: "",
                            data = encodeAttachment(part.text)
                        )
                    }
                )
            }

            val syncRequest = InitialSyncRequest(
                conversations = if (batchNumber == 1) conversationDtos else emptyList(),
                messages = messageDtos,
                batchNumber = batchNumber,
                totalBatches = totalBatches
            )

            val json = moshi.adapter(InitialSyncRequest::class.java).toJson(syncRequest)

            // Log first message for debugging
            if (messageDtos.isNotEmpty()) {
                Timber.d("Sample message - ID: ${messageDtos[0].id}, ThreadID: ${messageDtos[0].threadId}")
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val syncResponse = moshi.adapter(SyncResponse::class.java).fromJson(responseBody)
                syncResponse?.syncToken?.let {
                    preferences.webSyncToken.set(it)
                }
                Timber.d("Batch $batchNumber/$totalBatches synced successfully")
            } else {
                Timber.w("Batch sync failed: ${response.code} - $responseBody")
                throw Exception("Batch sync failed: ${response.code}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error syncing batch $batchNumber")
            throw e
        }
    }

    private fun syncIncrementalMessages(conversations: List<Conversation>, messages: List<Message>) {
        try {
            val url = "${getServerUrl()}/api/sync/incremental"

            val messageDtos = messages.map { msg ->
                MessageDto(
                    id = msg.id.toString(),
                    threadId = sanitizeThreadId(msg.threadId),
                    address = msg.address,
                    body = msg.getText(), // Use getText() to get text from both SMS and MMS
                    type = msg.type,
                    date = msg.date.toString(),
                    dateSent = msg.dateSent?.toString(),
                    read = msg.read,
                    seen = msg.seen,
                    isMe = msg.isMe(),
                    attachments = emptyList() // Attachments handled separately
                )
            }

            val syncRequest = IncrementalSyncRequest(
                syncToken = preferences.webSyncToken.get(),
                newMessages = messageDtos,
                updatedMessages = emptyList(),
                deletedMessageIds = emptyList()
            )

            val json = moshi.adapter(IncrementalSyncRequest::class.java).toJson(syncRequest)

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val syncResponse = moshi.adapter(SyncResponse::class.java).fromJson(responseBody)
                syncResponse?.syncToken?.let {
                    preferences.webSyncToken.set(it)
                }
                Timber.d("Incremental sync successful")
            } else {
                Timber.w("Incremental sync failed: ${response.code} - $responseBody")
                throw Exception("Incremental sync failed: ${response.code}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in incremental sync")
            throw e
        }
    }

    private fun sanitizeThreadId(threadId: Long): String {
        // Extract only numeric digits from threadId to handle cases like "921_UNKNOWN_SENDER!"
        // which can occur for system messages or unknown senders
        val threadIdStr = threadId.toString()
        return threadIdStr.replace(Regex("[^0-9]"), "")
    }

    private fun encodeAttachment(data: String?): String {
        return if (data != null && data.isNotEmpty()) {
            try {
                Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                Timber.w(e, "Failed to encode attachment")
                ""
            }
        } else {
            ""
        }
    }

    private fun getServerUrl(): String {
        return preferences.webSyncServerUrl.get().trimEnd('/')
    }

    // DTOs for API communication
    private data class LoginRequest(
        @Json(name = "username") val username: String,
        @Json(name = "password") val password: String,
        @Json(name = "deviceId") val deviceId: String
    )

    private data class LoginResponse(
        val success: Boolean,
        val accessToken: String,
        val refreshToken: String
    )

    private data class RegisterRequest(
        @Json(name = "username") val username: String,
        @Json(name = "password") val password: String,
        @Json(name = "deviceId") val deviceId: String
    )

    private data class RegisterResponse(
        val success: Boolean,
        val userId: String?,
        val message: String
    )

    private data class RecipientDto(
        val address: String,
        val contactName: String?
    )

    private data class ConversationDto(
        val id: String,
        val recipients: List<RecipientDto>,
        val name: String?,
        val archived: Boolean,
        val blocked: Boolean,
        val pinned: Boolean
    )

    private data class MessageDto(
        val id: String,
        val threadId: String,
        val address: String,
        val body: String?,
        val type: String,
        val date: String,
        val dateSent: String?,
        val read: Boolean,
        val seen: Boolean,
        val isMe: Boolean,
        val attachments: List<AttachmentDto>
    )

    private data class AttachmentDto(
        val type: String,
        val data: String
    )

    private data class InitialSyncRequest(
        val conversations: List<ConversationDto>,
        val messages: List<MessageDto>,
        val batchNumber: Int,
        val totalBatches: Int
    )

    private data class IncrementalSyncRequest(
        val syncToken: String,
        val newMessages: List<MessageDto>,
        val updatedMessages: List<MessageUpdateDto>,
        val deletedMessageIds: List<String>
    )

    private data class MessageUpdateDto(
        val id: String,
        val read: Boolean,
        val seen: Boolean,
        val timestamp: Long
    )

    private data class SyncResponse(
        val success: Boolean,
        val syncToken: String?,
        val processedCount: Int?
    )

    private data class QueueResponse(
        val queuedMessages: List<QueuedMessageDto>
    )

    private data class QueuedMessageDto(
        val id: String,
        val conversationId: String?,
        val addresses: List<String>,
        val body: String
    )

    private data class ConfirmRequest(
        val queueId: String,
        val androidMessageId: String
    )
}
