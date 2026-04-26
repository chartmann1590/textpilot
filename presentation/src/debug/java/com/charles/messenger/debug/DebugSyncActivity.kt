package com.charles.messenger.debug

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import com.charles.messenger.model.Conversation
import com.charles.messenger.model.Message
import com.charles.messenger.model.SyncLog
import com.charles.messenger.service.FullSyncJobIntentService
import io.realm.Realm
import java.io.File

class DebugSyncActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread {
            val resultFile = File(cacheDir, "sync_e2e_result.txt")
            val address = intent.getStringExtra("address").orEmpty().ifBlank { "+15551234567" }
            val minMessages = intent.getIntExtra("minMessages", 1)
            val startTime = System.currentTimeMillis()

            try {
                FullSyncJobIntentService.enqueueWork(this)

                var syncedMessages = 0
                var syncedConversations = 0
                var synced = false

                for (attempt in 0 until 90) {
                    SystemClock.sleep(1000)

                    Realm.getDefaultInstance().use { realm ->
                        syncedMessages = realm.where(Message::class.java)
                            .equalTo("address", address)
                            .findAll()
                            .size
                        syncedConversations = realm.where(Conversation::class.java)
                            .findAll()
                            .size
                        val hasSyncLog = realm.where(SyncLog::class.java)
                            .greaterThanOrEqualTo("date", startTime)
                            .count() > 0

                        if (hasSyncLog && syncedMessages >= minMessages) {
                            synced = true
                        }
                    }

                    if (synced) {
                        break
                    }
                }

                val content = buildString {
                    appendLine("status=${if (synced) "ok" else "error"}")
                    appendLine("address=$address")
                    appendLine("messageCount=$syncedMessages")
                    appendLine("conversationCount=$syncedConversations")
                }
                resultFile.writeText(content)
            } catch (t: Throwable) {
                val content = buildString {
                    appendLine("status=error")
                    appendLine("message=${t.message}")
                }
                resultFile.writeText(content)
            } finally {
                runOnUiThread { finish() }
            }
        }.start()
    }
}
