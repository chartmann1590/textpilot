package com.charles.messenger.feature.compose

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val PREFS_NAME = "review_prompt_prefs"
private const val KEY_SEND_COUNT = "review_prompt_send_count"
private const val KEY_REQUESTED = "review_prompt_requested"

/** Messages sent before we ever ask for a review. Early asks convert worse. */
private const val SENDS_BEFORE_FIRST_ASK = 4

/**
 * Prompts the official Play In-App Review dialog after a handful of successfully sent messages.
 * Google's own quota caps how often the dialog can appear regardless of what we request, so this
 * only needs to avoid asking too early and never ask twice. Deliberately independent of the
 * ViewModel/MVI reducer — just observes the same send-button clicks the ViewModel already does.
 */
object ReviewPrompter {
    fun onMessageSent(activity: Activity) {
        val prefs = activity.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val alreadyRequested = prefs.getBoolean(KEY_REQUESTED, false)
        val count = prefs.getInt(KEY_SEND_COUNT, 0) + 1
        prefs.edit().putInt(KEY_SEND_COUNT, count).apply()
        if (alreadyRequested || count < SENDS_BEFORE_FIRST_ASK) return
        prefs.edit().putBoolean(KEY_REQUESTED, true).apply()

        CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                val manager = ReviewManagerFactory.create(activity)
                val reviewInfo = manager.requestReviewFlow().await()
                manager.launchReviewFlow(activity, reviewInfo).await()
            }
        }
    }
}
