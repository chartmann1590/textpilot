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
package com.charles.messenger.injection

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.lifecycle.ViewModelProvider
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.charles.messenger.blocking.BlockingClient
import com.charles.messenger.blocking.BlockingManager
import com.charles.messenger.common.ViewModelFactory
import com.charles.messenger.common.util.BillingManagerImpl
import com.charles.messenger.common.util.NotificationManagerImpl
import com.charles.messenger.common.util.ShortcutManagerImpl
import com.charles.messenger.feature.conversationinfo.injection.ConversationInfoComponent
import com.charles.messenger.feature.themepicker.injection.ThemePickerComponent
import com.charles.messenger.listener.ContactAddedListener
import com.charles.messenger.listener.ContactAddedListenerImpl
import com.charles.messenger.manager.ActiveConversationManager
import com.charles.messenger.manager.ActiveConversationManagerImpl
import com.charles.messenger.manager.AlarmManager
import com.charles.messenger.manager.AlarmManagerImpl
import com.charles.messenger.manager.AnalyticsManager
import com.charles.messenger.manager.AnalyticsManagerImpl
import com.charles.messenger.manager.BillingManager
import com.charles.messenger.manager.ChangelogManager
import com.charles.messenger.manager.ChangelogManagerImpl
import com.charles.messenger.manager.KeyManager
import com.charles.messenger.manager.KeyManagerImpl
import com.charles.messenger.manager.NotificationManager
import com.charles.messenger.manager.PermissionManager
import com.charles.messenger.manager.PermissionManagerImpl
import com.charles.messenger.manager.RatingManager
import com.charles.messenger.manager.ReferralManager
import com.charles.messenger.manager.ReferralManagerImpl
import com.charles.messenger.manager.ShortcutManager
import com.charles.messenger.manager.WidgetManager
import com.charles.messenger.manager.WidgetManagerImpl
import com.charles.messenger.mapper.CursorToContact
import com.charles.messenger.mapper.CursorToContactGroup
import com.charles.messenger.mapper.CursorToContactGroupImpl
import com.charles.messenger.mapper.CursorToContactGroupMember
import com.charles.messenger.mapper.CursorToContactGroupMemberImpl
import com.charles.messenger.mapper.CursorToContactImpl
import com.charles.messenger.mapper.CursorToConversation
import com.charles.messenger.mapper.CursorToConversationImpl
import com.charles.messenger.mapper.CursorToMessage
import com.charles.messenger.mapper.CursorToMessageImpl
import com.charles.messenger.mapper.CursorToPart
import com.charles.messenger.mapper.CursorToPartImpl
import com.charles.messenger.mapper.CursorToRecipient
import com.charles.messenger.mapper.CursorToRecipientImpl
import com.charles.messenger.mapper.RatingManagerImpl
import com.charles.messenger.repository.BackupRepository
import com.charles.messenger.repository.BackupRepositoryImpl
import com.charles.messenger.repository.BlockingRepository
import com.charles.messenger.repository.BlockingRepositoryImpl
import com.charles.messenger.repository.ContactRepository
import com.charles.messenger.repository.ContactRepositoryImpl
import com.charles.messenger.repository.ConversationRepository
import com.charles.messenger.repository.ConversationRepositoryImpl
import com.charles.messenger.repository.MessageRepository
import com.charles.messenger.repository.MessageRepositoryImpl
import com.charles.messenger.repository.ScheduledMessageRepository
import com.charles.messenger.repository.ScheduledMessageRepositoryImpl
import com.charles.messenger.repository.SyncRepository
import com.charles.messenger.repository.SyncRepositoryImpl
import com.charles.messenger.repository.WebSyncRepository
import com.charles.messenger.repository.WebSyncRepositoryImpl
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module(subcomponents = [
    ConversationInfoComponent::class,
    ThemePickerComponent::class])
class AppModule(private var application: Application) {

    @Provides
    @Singleton
    fun provideContext(): Context = application

    @Provides
    fun provideContentResolver(context: Context): ContentResolver = context.contentResolver

    @Provides
    @Singleton
    fun provideSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Provides
    @Singleton
    fun provideRxPreferences(preferences: SharedPreferences): RxSharedPreferences {
        return RxSharedPreferences.create(preferences)
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
    }

    @Provides
    fun provideViewModelFactory(factory: ViewModelFactory): ViewModelProvider.Factory = factory

    // Listener

    @Provides
    fun provideContactAddedListener(listener: ContactAddedListenerImpl): ContactAddedListener = listener

    // Manager

    @Provides
    fun provideBillingManager(manager: BillingManagerImpl): BillingManager = manager

    @Provides
    fun provideActiveConversationManager(manager: ActiveConversationManagerImpl): ActiveConversationManager = manager

    @Provides
    fun provideAlarmManager(manager: AlarmManagerImpl): AlarmManager = manager

    @Provides
    fun provideAnalyticsManager(manager: AnalyticsManagerImpl): AnalyticsManager = manager

    @Provides
    fun blockingClient(manager: BlockingManager): BlockingClient = manager

    @Provides
    fun changelogManager(manager: ChangelogManagerImpl): ChangelogManager = manager

    @Provides
    fun provideKeyManager(manager: KeyManagerImpl): KeyManager = manager

    @Provides
    fun provideNotificationsManager(manager: NotificationManagerImpl): NotificationManager = manager

    @Provides
    fun providePermissionsManager(manager: PermissionManagerImpl): PermissionManager = manager

    @Provides
    fun provideRatingManager(manager: RatingManagerImpl): RatingManager = manager

    @Provides
    fun provideShortcutManager(manager: ShortcutManagerImpl): ShortcutManager = manager

    @Provides
    fun provideReferralManager(manager: ReferralManagerImpl): ReferralManager = manager

    @Provides
    fun provideWidgetManager(manager: WidgetManagerImpl): WidgetManager = manager

    // Mapper

    @Provides
    fun provideCursorToContact(mapper: CursorToContactImpl): CursorToContact = mapper

    @Provides
    fun provideCursorToContactGroup(mapper: CursorToContactGroupImpl): CursorToContactGroup = mapper

    @Provides
    fun provideCursorToContactGroupMember(mapper: CursorToContactGroupMemberImpl): CursorToContactGroupMember = mapper

    @Provides
    fun provideCursorToConversation(mapper: CursorToConversationImpl): CursorToConversation = mapper

    @Provides
    fun provideCursorToMessage(mapper: CursorToMessageImpl): CursorToMessage = mapper

    @Provides
    fun provideCursorToPart(mapper: CursorToPartImpl): CursorToPart = mapper

    @Provides
    fun provideCursorToRecipient(mapper: CursorToRecipientImpl): CursorToRecipient = mapper

    // Repository

    @Provides
    fun provideBackupRepository(repository: BackupRepositoryImpl): BackupRepository = repository

    @Provides
    fun provideBlockingRepository(repository: BlockingRepositoryImpl): BlockingRepository = repository

    @Provides
    fun provideContactRepository(repository: ContactRepositoryImpl): ContactRepository = repository

    @Provides
    fun provideConversationRepository(repository: ConversationRepositoryImpl): ConversationRepository = repository

    @Provides
    fun provideMessageRepository(repository: MessageRepositoryImpl): MessageRepository = repository

    @Provides
    fun provideScheduledMessagesRepository(repository: ScheduledMessageRepositoryImpl): ScheduledMessageRepository = repository

    @Provides
    fun provideSyncRepository(repository: SyncRepositoryImpl): SyncRepository = repository

    @Provides
    fun provideWebSyncRepository(repository: WebSyncRepositoryImpl): WebSyncRepository = repository

    @Provides
    @Singleton
    fun provideOkHttpClient(): okhttp3.OkHttpClient {
        return okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Provides
    fun provideOllamaRepository(repository: com.charles.messenger.repository.OllamaRepositoryImpl): com.charles.messenger.repository.OllamaRepository = repository

    @Provides
    fun provideOnDeviceLlmRepository(repository: com.charles.messenger.repository.OnDeviceLlmRepositoryImpl): com.charles.messenger.repository.OnDeviceLlmRepository = repository

}
