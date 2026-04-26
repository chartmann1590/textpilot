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
package com.charles.messenger.injection.android

import com.charles.messenger.feature.backup.RestoreBackupService
import com.charles.messenger.injection.scope.ActivityScope
import com.charles.messenger.service.HeadlessSmsSendService
import com.charles.messenger.receiver.SendSmsReceiver
import com.charles.messenger.service.AutoDeleteService
import com.charles.messenger.service.FullSyncJobIntentService
import com.charles.messenger.service.WebSyncService
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
abstract class ServiceBuilderModule {

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindAutoDeleteService(): AutoDeleteService

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindHeadlessSmsSendService(): HeadlessSmsSendService

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindFullSyncJobIntentService(): FullSyncJobIntentService

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindRestoreBackupService(): RestoreBackupService

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindSendSmsReceiver(): SendSmsReceiver

    @ActivityScope
    @ContributesAndroidInjector()
    abstract fun bindWebSyncService(): WebSyncService

}
