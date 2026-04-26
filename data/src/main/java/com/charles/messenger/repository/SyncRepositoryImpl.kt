/*
 * Copyright (C) 2017 Moez Bhatti <charles.bhatti@gmail.com>
 *
 * This file is part of messenger.
 *
 * messenger is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * messenger is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with messenger.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.charles.messenger.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.Telephony
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.charles.messenger.extensions.forEach
import com.charles.messenger.extensions.insertOrUpdate
import com.charles.messenger.extensions.map
import com.charles.messenger.manager.KeyManager
import com.charles.messenger.mapper.CursorToContact
import com.charles.messenger.mapper.CursorToContactGroup
import com.charles.messenger.mapper.CursorToContactGroupMember
import com.charles.messenger.mapper.CursorToConversation
import com.charles.messenger.mapper.CursorToMessage
import com.charles.messenger.mapper.CursorToPart
import com.charles.messenger.mapper.CursorToRecipient
import com.charles.messenger.model.Contact
import com.charles.messenger.model.ContactGroup
import com.charles.messenger.model.Conversation
import com.charles.messenger.model.Message
import com.charles.messenger.model.MmsPart
import com.charles.messenger.model.PhoneNumber
import com.charles.messenger.model.Recipient
import com.charles.messenger.model.SyncLog
import com.charles.messenger.util.PhoneNumberUtils
import com.charles.messenger.util.tryOrNull
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import io.realm.RealmList
import io.realm.Sort
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
    private val conversationRepo: ConversationRepository,
    private val cursorToConversation: CursorToConversation,
    private val cursorToMessage: CursorToMessage,
    private val cursorToPart: CursorToPart,
    private val cursorToRecipient: CursorToRecipient,
    private val cursorToContact: CursorToContact,
    private val cursorToContactGroup: CursorToContactGroup,
    private val cursorToContactGroupMember: CursorToContactGroupMember,
    private val keys: KeyManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val rxPrefs: RxSharedPreferences
) : SyncRepository {

    companion object {
        private const val PART_BATCH_SIZE = 250
        private const val MESSAGE_BATCH_SIZE = 100
        private const val CONVERSATION_BATCH_SIZE = 50
        private const val RECIPIENT_BATCH_SIZE = 100
        private const val PROGRESS_EMIT_STEP = 50
    }

    override val syncProgress: Subject<SyncRepository.SyncProgress> =
            BehaviorSubject.createDefault(SyncRepository.SyncProgress.Idle)

    override fun syncMessages() {
        if (syncProgress.blockingFirst() is SyncRepository.SyncProgress.Running) return
        syncProgress.onNext(SyncRepository.SyncProgress.Running(0, 0, true))

        val oldBlockedSenders = rxPrefs.getStringSet("pref_key_blocked_senders")
        var syncSucceeded = false

        try {
            Realm.getDefaultInstance().use { realm ->
                val persistedData = realm.copyFromRealm(
                    realm.where(Conversation::class.java)
                        .beginGroup()
                        .equalTo("archived", true)
                        .or()
                        .equalTo("blocked", true)
                        .or()
                        .equalTo("pinned", true)
                        .or()
                        .isNotEmpty("name")
                        .or()
                        .isNotNull("blockingClient")
                        .or()
                        .isNotEmpty("blockReason")
                        .endGroup()
                        .findAll()
                ).associateBy { conversation -> conversation.id }.toMutableMap()

                realm.executeTransaction {
                    it.delete(Contact::class.java)
                    it.delete(ContactGroup::class.java)
                    it.delete(Conversation::class.java)
                    it.delete(Message::class.java)
                    it.delete(MmsPart::class.java)
                    it.delete(Recipient::class.java)
                    keys.reset()
                }

                val partsCursor = cursorToPart.getPartsCursor()
                val messageCursor = cursorToMessage.getMessagesCursor()
                val conversationCursor = cursorToConversation.getConversationsCursor()
                val recipientCursor = cursorToRecipient.getRecipientCursor()

                val max = (partsCursor?.count ?: 0) +
                    (messageCursor?.count ?: 0) +
                    (conversationCursor?.count ?: 0) +
                    (recipientCursor?.count ?: 0)

                var progress = 0
                var lastEmittedProgress = -1

                fun emitProgress(force: Boolean = false, indeterminate: Boolean = false) {
                    if (max == 0) {
                        syncProgress.onNext(SyncRepository.SyncProgress.Running(0, 0, true))
                        return
                    }

                    if (force || progress == max || progress - lastEmittedProgress >= PROGRESS_EMIT_STEP) {
                        lastEmittedProgress = progress
                        syncProgress.onNext(SyncRepository.SyncProgress.Running(max, progress, indeterminate))
                    }
                }

                partsCursor?.use { cursor ->
                    val buffer = ArrayList<MmsPart>(PART_BATCH_SIZE)
                    cursor.forEach {
                        tryOrNull {
                            progress++
                            buffer += cursorToPart.map(cursor)
                            if (buffer.size >= PART_BATCH_SIZE) {
                                realm.executeTransaction { it.insertOrUpdate(buffer) }
                                buffer.clear()
                                emitProgress()
                            }
                        }
                    }

                    if (buffer.isNotEmpty()) {
                        realm.executeTransaction { it.insertOrUpdate(buffer) }
                        emitProgress(force = true)
                    }
                }

                messageCursor?.use { cursor ->
                    val buffer = ArrayList<Message>(MESSAGE_BATCH_SIZE)
                    val messageColumns = CursorToMessage.MessageColumns(cursor)

                    cursor.forEach { messageCursorRow ->
                        tryOrNull {
                            progress++
                            val message = cursorToMessage.map(Pair(messageCursorRow, messageColumns)).apply {
                                if (isMms()) {
                                    parts = RealmList<MmsPart>().apply {
                                        addAll(
                                            realm.copyFromRealm(
                                                realm.where(MmsPart::class.java)
                                                    .equalTo("messageId", contentId)
                                                    .findAll()
                                            )
                                        )
                                    }
                                }
                            }

                            buffer += message
                            if (buffer.size >= MESSAGE_BATCH_SIZE) {
                                realm.executeTransaction { it.insertOrUpdate(buffer) }
                                buffer.clear()
                                emitProgress()
                            }
                        }
                    }

                    if (buffer.isNotEmpty()) {
                        realm.executeTransaction { it.insertOrUpdate(buffer) }
                        emitProgress(force = true)
                    }
                }

                oldBlockedSenders.get()
                    .map { threadIdString -> threadIdString.toLong() }
                    .filter { threadId -> !persistedData.contains(threadId) }
                    .forEach { threadId -> persistedData[threadId] = Conversation(id = threadId, blocked = true) }

                conversationCursor?.use { cursor ->
                    val buffer = ArrayList<Conversation>(CONVERSATION_BATCH_SIZE)

                    cursor.forEach { conversationCursorRow ->
                        tryOrNull {
                            progress++
                            val conversation = cursorToConversation.map(conversationCursorRow).apply {
                                persistedData[id]?.let { persistedConversation ->
                                    archived = persistedConversation.archived
                                    blocked = persistedConversation.blocked
                                    pinned = persistedConversation.pinned
                                    name = persistedConversation.name
                                    blockingClient = persistedConversation.blockingClient
                                    blockReason = persistedConversation.blockReason
                                }
                                lastMessage = realm.where(Message::class.java)
                                    .sort("date", Sort.DESCENDING)
                                    .equalTo("threadId", id)
                                    .findFirst()
                                    ?.let(realm::copyFromRealm)
                            }

                            buffer += conversation
                            if (buffer.size >= CONVERSATION_BATCH_SIZE) {
                                realm.executeTransaction { it.insertOrUpdate(buffer) }
                                buffer.clear()
                                emitProgress()
                            }
                        }
                    }

                    if (buffer.isNotEmpty()) {
                        realm.executeTransaction { it.insertOrUpdate(buffer) }
                        emitProgress(force = true)
                    }
                }

                val contacts = getContacts()
                realm.executeTransaction {
                    val managedContacts = it.copyToRealmOrUpdate(contacts)
                    it.insertOrUpdate(getContactGroups(managedContacts))
                }
                val contactsSnapshot = realm.copyFromRealm(realm.where(Contact::class.java).findAll())

                recipientCursor?.use { cursor ->
                    val buffer = ArrayList<Recipient>(RECIPIENT_BATCH_SIZE)

                    cursor.forEach { recipientCursorRow ->
                        tryOrNull {
                            progress++
                            val recipient = cursorToRecipient.map(recipientCursorRow).apply {
                                contact = contactsSnapshot.firstOrNull { contact ->
                                    contact.numbers.any { phoneNumberUtils.compare(address, it.address) }
                                }
                            }

                            buffer += recipient
                            if (buffer.size >= RECIPIENT_BATCH_SIZE) {
                                realm.executeTransaction { it.insertOrUpdate(buffer) }
                                buffer.clear()
                                emitProgress()
                            }
                        }
                    }

                    if (buffer.isNotEmpty()) {
                        realm.executeTransaction { it.insertOrUpdate(buffer) }
                        emitProgress(force = true)
                    }
                }

                syncProgress.onNext(SyncRepository.SyncProgress.Running(0, 0, true))
                realm.executeTransaction { it.insert(SyncLog()) }
                syncSucceeded = true
            }
        } finally {
            if (syncSucceeded) {
                oldBlockedSenders.delete()
            }
            syncProgress.onNext(SyncRepository.SyncProgress.Idle)
        }
    }

    override fun syncMessage(uri: Uri): Message? {

        // If we don't have a valid type, return null
        val type = when {
            uri.toString().contains("mms") -> "mms"
            uri.toString().contains("sms") -> "sms"
            else -> return null
        }

        // If we don't have a valid id, return null
        val id = tryOrNull(false) { ContentUris.parseId(uri) } ?: return null

        // Check if the message already exists, so we can reuse the id
        val existingId = Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(Message::class.java)
                    .equalTo("type", type)
                    .equalTo("contentId", id)
                    .findFirst()
                    ?.id
        }

        // The uri might be something like content://mms/inbox/id
        // The box might change though, so we should just use the mms/id uri
        val stableUri = when (type) {
            "mms" -> ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            else -> ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }

        return contentResolver.query(stableUri, null, null, null, null)?.use { cursor ->

            // If there are no rows, return null. Otherwise, we've moved to the first row
            if (!cursor.moveToFirst()) return null

            val columnsMap = CursorToMessage.MessageColumns(cursor)
            cursorToMessage.map(Pair(cursor, columnsMap)).apply {
                existingId?.let { this.id = it }

                if (isMms()) {
                    parts = RealmList<MmsPart>().apply {
                        addAll(cursorToPart.getPartsCursor(contentId)?.map { cursorToPart.map(it) }.orEmpty())
                    }
                }

                conversationRepo.getOrCreateConversation(threadId)
                insertOrUpdate()
            }
        }
    }

    override fun syncContacts() {
        // Load all the contacts
        var contacts = getContacts()

        Realm.getDefaultInstance()?.use { realm ->
            val recipients = realm.where(Recipient::class.java).findAll()

            realm.executeTransaction {
                realm.delete(Contact::class.java)
                realm.delete(ContactGroup::class.java)

                contacts = realm.copyToRealmOrUpdate(contacts)
                realm.insertOrUpdate(getContactGroups(contacts))

                // Update all the recipients with the new contacts
                recipients.forEach { recipient ->
                    recipient.contact = contacts.find { contact ->
                        contact.numbers.any { phoneNumberUtils.compare(recipient.address, it.address) }
                    }
                }

                realm.insertOrUpdate(recipients)
            }

        }
    }

    private fun getContacts(): List<Contact> {
        val defaultNumberIds = Realm.getDefaultInstance().use { realm ->
            realm.where(PhoneNumber::class.java)
                    .equalTo("isDefault", true)
                    .findAll()
                    .map { number -> number.id }
        }

        return cursorToContact.getContactsCursor()
                ?.map { cursor -> cursorToContact.map(cursor) }
                ?.groupBy { contact -> contact.lookupKey }
                ?.map { contacts ->
                    // Sometimes, contacts providers on the phone will create duplicate phone number entries. This
                    // commonly happens with Whatsapp. Let's try to detect these duplicate entries and filter them out
                    val uniqueNumbers = mutableListOf<PhoneNumber>()
                    contacts.value
                            .flatMap { it.numbers }
                            .forEach { number ->
                                number.isDefault = defaultNumberIds.any { id -> id == number.id }
                                val duplicate = uniqueNumbers.find { other ->
                                    phoneNumberUtils.compare(number.address, other.address)
                                }

                                if (duplicate == null) {
                                    uniqueNumbers += number
                                } else if (!duplicate.isDefault && number.isDefault) {
                                    duplicate.isDefault = true
                                }
                            }

                    contacts.value.first().apply {
                        numbers.clear()
                        numbers.addAll(uniqueNumbers)
                    }
                } ?: listOf()
    }

    private fun getContactGroups(contacts: List<Contact>): List<ContactGroup> {
        val groupMembers = cursorToContactGroupMember.getGroupMembersCursor()
                ?.map(cursorToContactGroupMember::map)
                .orEmpty()

        val groups = cursorToContactGroup.getContactGroupsCursor()
                ?.map(cursorToContactGroup::map)
                .orEmpty()

        groups.forEach { group ->
            group.contacts.addAll(groupMembers
                    .filter { member -> member.groupId == group.id }
                    .mapNotNull { member -> contacts.find { contact -> contact.lookupKey == member.lookupKey } })
        }

        return groups
    }

}
