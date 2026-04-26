package com.charles.messenger.service

import android.content.Context
import android.content.Intent
import androidx.core.app.JobIntentService
import com.charles.messenger.interactor.SyncMessages
import dagger.android.AndroidInjection
import timber.log.Timber
import javax.inject.Inject

class FullSyncJobIntentService : JobIntentService() {

    @Inject lateinit var syncMessages: SyncMessages

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun onHandleWork(intent: Intent) {
        syncMessages.buildObservable(Unit)
            .blockingSubscribe({}, Timber::w)
    }

    companion object {
        private const val JOB_ID = 0x534d53

        fun enqueueWork(context: Context) {
            enqueueWork(
                context,
                FullSyncJobIntentService::class.java,
                JOB_ID,
                Intent(context, FullSyncJobIntentService::class.java)
            )
        }
    }
}
