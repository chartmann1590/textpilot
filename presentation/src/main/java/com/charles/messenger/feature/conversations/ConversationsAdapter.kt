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
package com.charles.messenger.feature.conversations

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.isVisible
import com.charles.messenger.R
import com.charles.messenger.common.Navigator
import com.charles.messenger.common.base.QkRealmAdapter
import com.charles.messenger.common.base.QkViewHolder
import com.charles.messenger.common.util.Colors
import com.charles.messenger.common.util.DateFormatter
import com.charles.messenger.common.util.extensions.resolveThemeColor
import com.charles.messenger.common.util.extensions.setTint
import com.charles.messenger.common.widget.GroupAvatarView
import com.charles.messenger.common.widget.QkTextView
import com.charles.messenger.model.Conversation
import com.charles.messenger.util.PhoneNumberUtils
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import javax.inject.Inject

class ConversationsAdapter @Inject constructor(
    private val colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val navigator: Navigator,
    private val phoneNumberUtils: PhoneNumberUtils
) : QkRealmAdapter<Conversation>() {
    companion object {
        const val VIEW_TYPE_CONVERSATION_READ = 0
        const val VIEW_TYPE_CONVERSATION_UNREAD = 1
        const val VIEW_TYPE_INLINE_AD = 2
        private const val INLINE_AD_FREQUENCY = 5
    }

    private var inlineNativeAd: NativeAd? = null

    init {
        // This is how we access the threadId for the swipe actions
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        if (viewType == VIEW_TYPE_INLINE_AD) {
            val view = layoutInflater.inflate(R.layout.conversation_inline_native_ad_item, parent, false)
            return QkViewHolder(view)
        }

        val view = layoutInflater.inflate(R.layout.conversation_list_item, parent, false)

        if (viewType == VIEW_TYPE_CONVERSATION_UNREAD) {
            val textColorPrimary = parent.context.resolveThemeColor(android.R.attr.textColorPrimary)

            val title = view.findViewById<QkTextView>(R.id.title)
            val snippet = view.findViewById<QkTextView>(R.id.snippet)
            val unread = view.findViewById<ImageView>(R.id.unread)
            val date = view.findViewById<QkTextView>(R.id.date)

            title.setTypeface(title.typeface, Typeface.BOLD)

            snippet.setTypeface(snippet.typeface, Typeface.BOLD)
            snippet.setTextColor(textColorPrimary)
            snippet.maxLines = 5

            unread.isVisible = true

            date.setTypeface(date.typeface, Typeface.BOLD)
            date.setTextColor(textColorPrimary)
        }

        return QkViewHolder(view).apply {
            view.setOnClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnClickListener
                when (toggleSelection(conversation.id, false)) {
                    true -> view.isActivated = isSelected(conversation.id)
                    false -> navigator.showConversation(conversation.id)
                }
            }
            view.setOnLongClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnLongClickListener true
                toggleSelection(conversation.id)
                view.isActivated = isSelected(conversation.id)
                true
            }
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        if (getItemViewType(position) == VIEW_TYPE_INLINE_AD) {
            val adSlot = holder.itemView.findViewById<FrameLayout>(R.id.inlineAdSlot)
            val nativeAd = inlineNativeAd
            adSlot.removeAllViews()
            if (nativeAd != null) {
                val adView = LayoutInflater.from(holder.itemView.context)
                    .inflate(R.layout.native_ad_layout, adSlot, false) as NativeAdView
                com.charles.messenger.common.util.NativeAdController.bind(adView, nativeAd)
                adSlot.addView(adView)
            }
            return
        }

        val conversation = getItem(position) ?: return

        // If the last message wasn't incoming, then the colour doesn't really matter anyway
        val lastMessage = conversation.lastMessage
        val recipient = when {
            conversation.recipients.size == 1 || lastMessage == null -> conversation.recipients.firstOrNull()
            else -> conversation.recipients.find { recipient ->
                phoneNumberUtils.compare(recipient.address, lastMessage.address)
            }
        }
        val theme = colors.theme(recipient).theme

        holder.itemView.isActivated = isSelected(conversation.id)

        val avatars = holder.itemView.findViewById<GroupAvatarView>(R.id.avatars)
        val title = holder.itemView.findViewById<QkTextView>(R.id.title)
        val date = holder.itemView.findViewById<QkTextView>(R.id.date)
        val snippet = holder.itemView.findViewById<QkTextView>(R.id.snippet)
        val pinned = holder.itemView.findViewById<ImageView>(R.id.pinned)
        val unread = holder.itemView.findViewById<ImageView>(R.id.unread)

        avatars.recipients = conversation.recipients
        title.collapseEnabled = conversation.recipients.size > 1
        title.text = buildSpannedString {
            append(conversation.getTitle())
            if (conversation.draft.isNotEmpty()) {
                color(theme) { append(" " + context.getString(R.string.main_draft)) }
            }
        }
        date.text = conversation.date.takeIf { it > 0 }?.let(dateFormatter::getConversationTimestamp)
        snippet.text = when {
            conversation.draft.isNotEmpty() -> conversation.draft
            conversation.me -> context.getString(R.string.main_sender_you, conversation.snippet)
            else -> conversation.snippet
        }
        pinned.isVisible = conversation.pinned
        unread.setTint(theme)
    }

    override fun getItemId(position: Int): Long {
        if (isInlineAdPosition(position)) return Long.MIN_VALUE + position
        return getItem(position)?.id ?: -1
    }

    override fun getItemViewType(position: Int): Int {
        if (isInlineAdPosition(position)) return VIEW_TYPE_INLINE_AD
        return if (getItem(position)?.unread == false) VIEW_TYPE_CONVERSATION_READ else VIEW_TYPE_CONVERSATION_UNREAD
    }

    override fun getItemCount(): Int {
        val baseCount = super.getItemCount()
        return baseCount + inlineAdCount(baseCount)
    }

    override fun getItem(index: Int): Conversation? {
        if (isInlineAdPosition(index)) return null
        return super.getItem(dataIndexForAdapterPosition(index))
    }

    fun setInlineNativeAd(ad: NativeAd?) {
        if (inlineNativeAd === ad) return
        inlineNativeAd = ad
        notifyDataSetChanged()
    }

    fun hasInlineAd(): Boolean = inlineNativeAd != null

    private fun shouldShowInlineAd(baseCount: Int = super.getItemCount()): Boolean {
        return inlineNativeAd != null && baseCount >= INLINE_AD_FREQUENCY
    }

    private fun isInlineAdPosition(position: Int): Boolean {
        if (!shouldShowInlineAd()) return false
        return (position + 1) % (INLINE_AD_FREQUENCY + 1) == 0
    }

    private fun dataIndexForAdapterPosition(position: Int): Int {
        if (!shouldShowInlineAd()) return position
        val adsBeforePosition = (position + 1) / (INLINE_AD_FREQUENCY + 1)
        return position - adsBeforePosition
    }

    private fun inlineAdCount(baseCount: Int): Int {
        if (!shouldShowInlineAd(baseCount)) return 0
        return baseCount / INLINE_AD_FREQUENCY
    }
}
