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
package com.charles.messenger.feature.qkreply

import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.RecyclerView
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.charles.messenger.R
import com.charles.messenger.common.base.QkThemedActivity
import com.charles.messenger.common.util.InterstitialAdManager
import com.charles.messenger.common.util.extensions.autoScrollToStart
import com.charles.messenger.common.util.extensions.resolveThemeColor
import com.charles.messenger.common.util.extensions.setBackgroundTint
import com.charles.messenger.common.util.extensions.setVisible
import com.charles.messenger.common.widget.QkEditText
import com.charles.messenger.feature.compose.MessagesAdapter
import com.charles.messenger.feature.compose.SuggestionChipsAdapter
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class QkReplyActivity : QkThemedActivity(), QkReplyView {

    @Inject lateinit var adapter: MessagesAdapter
    @Inject lateinit var interstitialAdManager: InterstitialAdManager
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var toolbar: Toolbar
    private lateinit var background: View
    private lateinit var messageBackground: View
    private lateinit var composeBackgroundGradient: View
    private lateinit var composeBackgroundSolid: View
    private lateinit var messages: RecyclerView
    private lateinit var counter: TextView
    private lateinit var sim: ImageView
    private lateinit var simIndex: TextView
    private lateinit var message: QkEditText
    private lateinit var send: ImageView
    private lateinit var smartReply: ImageView
    private lateinit var suggestionsChips: RecyclerView

    override val menuItemIntent: Subject<Int> = PublishSubject.create()
    override val textChangedIntent by lazy { message.textChanges() }
    override val changeSimIntent by lazy { sim.clicks() }
    override val sendIntent by lazy { send.clicks() }
    override val smartReplyIntent by lazy { smartReply.clicks() }
    override val selectSuggestionIntent: Subject<String> = PublishSubject.create()

    private val suggestionsAdapter by lazy {
        SuggestionChipsAdapter { suggestion ->
            selectSuggestionIntent.onNext(suggestion)
        }
    }

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[QkReplyViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        setFinishOnTouchOutside(prefs.qkreplyTapDismiss.get())
        setContentView(R.layout.qkreply_activity)
        window.setBackgroundDrawable(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        toolbar = findViewById(R.id.toolbar)
        background = findViewById(R.id.background)
        messageBackground = findViewById(R.id.messageBackground)
        composeBackgroundGradient = findViewById(R.id.composeBackgroundGradient)
        composeBackgroundSolid = findViewById(R.id.composeBackgroundSolid)
        messages = findViewById(R.id.messages)
        counter = findViewById(R.id.counter)
        sim = findViewById(R.id.sim)
        simIndex = findViewById(R.id.simIndex)
        message = findViewById(R.id.message)
        send = findViewById(R.id.send)
        smartReply = findViewById(R.id.smartReply)
        suggestionsChips = findViewById(R.id.suggestionsChips)

        suggestionsChips.adapter = suggestionsAdapter

        // Subscribe to theme changes and update suggestion chips
        theme
                .doOnNext { suggestionsAdapter.theme = it }
                .autoDisposable(scope())
                .subscribe()

        viewModel.bindView(this)

        // Auto-trigger smart reply if opened from notification action
        val triggerSmartReply = intent.getBooleanExtra("triggerSmartReply", false)
        if (triggerSmartReply && prefs.aiReplyEnabled.get() && prefs.hasConfiguredAiBackend()) {
            // Delay slightly to ensure view is fully initialized
            smartReply.postDelayed({
                smartReply.performClick()
            }, 300)
        }

        // Preload interstitial ad
        interstitialAdManager.loadAd(this)

        toolbar.clipToOutline = true

        messages.adapter = adapter
        messages.adapter?.autoScrollToStart(messages)
        messages.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = messages.scrollToPosition(adapter.itemCount - 1)
        })

        // These theme attributes don't apply themselves on API 21
        if (Build.VERSION.SDK_INT <= 22) {
            toolbar.setBackgroundTint(resolveThemeColor(R.attr.colorPrimary))
            background.setBackgroundTint(resolveThemeColor(android.R.attr.windowBackground))
            messageBackground.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
            composeBackgroundGradient.setBackgroundTint(resolveThemeColor(android.R.attr.windowBackground))
            composeBackgroundSolid.setBackgroundTint(resolveThemeColor(android.R.attr.windowBackground))
        }
    }

    override fun render(state: QkReplyState) {
        if (state.hasError) {
            finish()
        }

        threadId.onNext(state.threadId)

        title = state.title

        toolbar.menu.findItem(R.id.expand)?.isVisible = !state.expanded
        toolbar.menu.findItem(R.id.collapse)?.isVisible = state.expanded

        adapter.messagesData = state.data

        counter.text = state.remaining
        counter.setVisible(counter.text.isNotBlank())

        sim.setVisible(state.subscription != null)
        sim.contentDescription = getString(R.string.compose_sim_cd, state.subscription?.displayName)
        simIndex.text = "${state.subscription?.simSlotIndex?.plus(1)}"

        send.isEnabled = state.canSend
        send.imageAlpha = if (state.canSend) 255 else 128

        // Smart Reply UI
        smartReply.setVisible(prefs.aiReplyEnabled.get())
        smartReply.alpha = if (state.loadingSuggestions) 0.5f else 1.0f
        smartReply.isEnabled = !state.loadingSuggestions

        suggestionsChips.setVisible(state.showingSuggestions && state.suggestedReplies.isNotEmpty())
        suggestionsAdapter.suggestions = state.suggestedReplies
    }

    override fun setDraft(draft: String) {
        message.setText(draft)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.qkreply, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        menuItemIntent.onNext(item.itemId)
        return true
    }

    override fun getActivityThemeRes(black: Boolean) = when {
        black -> R.style.AppThemeDialog_Black
        else -> R.style.AppThemeDialog
    }

    override fun onDestroy() {
        // Show interstitial ad when closing quick reply
        interstitialAdManager.maybeShowAd(this)
        super.onDestroy()
    }

}
