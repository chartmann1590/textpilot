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
package com.charles.messenger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.charles.messenger.common.util.AiAutoReplyNotification
import com.charles.messenger.interactor.GenerateSmartReplies
import com.charles.messenger.interactor.SendMessage
import com.charles.messenger.model.Message
import com.charles.messenger.repository.ConversationRepository
import com.charles.messenger.repository.MessageRepository
import com.charles.messenger.util.Preferences
import dagger.android.AndroidInjection
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import io.realm.Realm
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AiAutoReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: Preferences
    @Inject lateinit var generateSmartReplies: GenerateSmartReplies
    @Inject lateinit var sendMessage: SendMessage
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var autoReplyNotification: AiAutoReplyNotification

    private val disposables = CompositeDisposable()

    override fun onReceive(context: Context, intent: Intent) {
        try {
            AndroidInjection.inject(this, context)
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject dependencies")
            return
        }

        // Check if auto-reply is enabled
        if (!prefs.aiAutoReplyToAll.get()) {
            Timber.d("Auto-reply disabled, skipping")
            return
        }

        // Check if AI is configured
        if (!prefs.aiReplyEnabled.get() || !prefs.hasConfiguredAiBackend()) {
            Timber.w("AI not configured, cannot auto-reply")
            return
        }

        // Get thread ID from intent extras
        val threadId = intent.getLongExtra("thread_id", -1L)
        if (threadId == -1L) {
            Timber.w("Could not determine thread ID, skipping auto-reply")
            return
        }

        Timber.d("Auto-reply triggered for thread: $threadId")

        val pendingResult = goAsync()

        // Delay to ensure message is fully committed to Realm
        disposables += Single.timer(1500, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .flatMap {
                // Use a fresh Realm instance and refresh to get latest data
                val realm = Realm.getDefaultInstance()
                realm.refresh()

                // Get conversation on main thread (Realm requirement)
                val conversation = conversationRepo.getConversation(threadId)
                if (conversation == null) {
                    realm.close()
                    Timber.w("Conversation not found for thread: $threadId")
                    return@flatMap Single.error<Unit>(Exception("Conversation not found"))
                }

                // Get recent messages synchronously with fresh Realm
                val realmMessages = realm.where(Message::class.java)
                    .equalTo("threadId", threadId)
                    .sort("date")
                    .findAll()

                if (realmMessages.isEmpty()) {
                    realm.close()
                    Timber.w("No messages found for thread: $threadId")
                    return@flatMap Single.error<Unit>(Exception("No messages found"))
                }

                // Copy to unmanaged list to use across threads
                val messages = realm.copyFromRealm(realmMessages).takeLast(10)
                realm.close()

                Timber.d("Found ${messages.size} messages for auto-reply context")

                val recipient = conversation.recipients.firstOrNull()
                if (recipient == null) {
                    return@flatMap Single.error<Unit>(Exception("No recipient found"))
                }
                val recipientAddress = recipient.address

                // Generate smart replies on IO thread
                val persona = prefs.aiPersona.get().takeIf { it.isNotEmpty() }
                generateSmartReplies
                    .buildObservable(GenerateSmartReplies.Params(
                        provider = prefs.currentAiProvider(),
                        baseUrl = prefs.ollamaApiUrl.get(),
                        model = prefs.currentAiModel(),
                        onDeviceModelPath = prefs.onDeviceModelPath.get(),
                        messages = messages,
                        persona = persona
                    ))
                    .subscribeOn(Schedulers.io())
                    .firstOrError()
                    .flatMap { suggestions ->
                        if (suggestions.isEmpty()) {
                            Timber.w("No suggestions generated")
                            Single.error<Unit>(Exception("No suggestions available"))
                        } else {
                            var replyText = suggestions.first()

                            if (prefs.aiSignatureEnabled.get() && prefs.aiSignatureText.get().isNotEmpty()) {
                                replyText = "$replyText\n\n${prefs.aiSignatureText.get()}"
                                Timber.d("Appended signature to auto-reply")
                            }

                            Timber.d("Auto-replying with: $replyText")

                            sendMessage.buildObservable(SendMessage.Params(
                                subId = -1,
                                threadId = threadId,
                                addresses = listOf(recipientAddress),
                                body = replyText,
                                attachments = emptyList()
                            )).firstOrError().map { Unit }
                        }
                    }
            }
            .subscribe(
                {
                    Timber.d("Auto-reply sent successfully")
                    autoReplyNotification.incrementCount()
                    pendingResult.finish()
                    disposables.clear()
                },
                { error ->
                    Timber.e(error, "Auto-reply failed")
                    pendingResult.finish()
                    disposables.clear()
                }
            )
    }
}
