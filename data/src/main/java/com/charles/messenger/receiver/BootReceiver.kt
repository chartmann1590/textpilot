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
package com.charles.messenger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.charles.messenger.interactor.UpdateScheduledMessageAlarms
import com.charles.messenger.service.WebSyncService
import com.charles.messenger.util.Preferences
import dagger.android.AndroidInjection
import timber.log.Timber
import javax.inject.Inject

class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var updateScheduledMessageAlarms: UpdateScheduledMessageAlarms
    @Inject lateinit var prefs: Preferences

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.LOCKED_BOOT_COMPLETED") return

        AndroidInjection.inject(this, context)

        val result = goAsync()

        // Reschedule web sync job if enabled
        if (prefs.webSyncEnabled.get()) {
            WebSyncService.scheduleJob(context)
            Timber.i("Web Sync job rescheduled on boot")
        }

        updateScheduledMessageAlarms.execute(Unit) { result.finish() }
    }

}