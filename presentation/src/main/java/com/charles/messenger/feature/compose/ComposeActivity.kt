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
package com.charles.messenger.feature.compose

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.charles.messenger.R
import com.charles.messenger.common.Navigator
import com.charles.messenger.common.base.QkThemedActivity
import com.charles.messenger.common.util.DateFormatter
import com.charles.messenger.common.util.InterstitialAdManager
import com.charles.messenger.common.util.extensions.*
import com.charles.messenger.common.widget.QkEditText
import com.charles.messenger.feature.compose.editing.ChipsAdapter
import com.charles.messenger.feature.contacts.ContactsActivity
import com.charles.messenger.model.Attachment
import com.charles.messenger.model.Recipient
import com.jakewharton.rxbinding2.widget.textChanges
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.collections.HashMap

class ComposeActivity : QkThemedActivity(), ComposeView {

    companion object {
        private const val SelectContactRequestCode = 0
        private const val TakePhotoRequestCode = 1
        private const val AttachPhotoRequestCode = 2
        private const val AttachContactRequestCode = 3

        private const val CameraDestinationKey = "camera_destination"
    }

    @Inject lateinit var attachmentAdapter: AttachmentAdapter
    @Inject lateinit var chipsAdapter: ChipsAdapter
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var interstitialAdManager: InterstitialAdManager
    @Inject lateinit var messageAdapter: MessagesAdapter
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var contentView: ViewGroup
    private lateinit var toolbar: Toolbar
    private lateinit var toolbarTitle: TextView
    private lateinit var toolbarSubtitle: TextView
    private lateinit var chips: RecyclerView
    private lateinit var composeBar: View
    private lateinit var sendAsGroup: View
    private lateinit var sendAsGroupBackground: View
    private lateinit var sendAsGroupSwitch: SwitchCompat
    private lateinit var messageList: RecyclerView
    private lateinit var messagesEmpty: View
    private lateinit var loading: android.widget.ProgressBar
    private lateinit var messageBackground: View
    private lateinit var scheduledGroup: View
    private lateinit var scheduledTime: TextView
    private lateinit var scheduledCancel: View
    private lateinit var attachments: RecyclerView
    private lateinit var attaching: View
    private lateinit var attachingBackground: View
    private lateinit var camera: View
    private lateinit var cameraLabel: View
    private lateinit var gallery: View
    private lateinit var galleryLabel: View
    private lateinit var schedule: View
    private lateinit var scheduleLabel: View
    private lateinit var contact: View
    private lateinit var contactLabel: View
    private lateinit var message: QkEditText
    private lateinit var attach: ImageView
    private lateinit var counter: TextView
    private lateinit var sim: View
    private lateinit var simIndex: TextView
    private lateinit var send: ImageView
    private lateinit var smartReply: ImageView
    private lateinit var suggestionsChips: RecyclerView

    override val activityVisibleIntent: Subject<Boolean> = PublishSubject.create()
    override val chipsSelectedIntent: Subject<HashMap<String, String?>> = PublishSubject.create()
    override val chipDeletedIntent: Subject<Recipient> by lazy { chipsAdapter.chipDeleted }
    override val menuReadyIntent: Observable<Unit> = menu.map { Unit }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val sendAsGroupIntent by lazy { sendAsGroupBackground.clicks() }
    override val messageClickIntent: Subject<Long> by lazy { messageAdapter.clicks }
    override val messagePartClickIntent: Subject<Long> by lazy { messageAdapter.partClicks }
    override val messagesSelectedIntent by lazy { messageAdapter.selectionChanges }
    override val cancelSendingIntent: Subject<Long> by lazy { messageAdapter.cancelSending }
    override val attachmentDeletedIntent: Subject<Attachment> by lazy { attachmentAdapter.attachmentDeleted }
    override val textChangedIntent by lazy { message.textChanges() }
    override val attachIntent by lazy { Observable.merge(attach.clicks(), attachingBackground.clicks()) }
    override val cameraIntent by lazy { Observable.merge(camera.clicks(), cameraLabel.clicks()) }
    override val galleryIntent by lazy { Observable.merge(gallery.clicks(), galleryLabel.clicks()) }
    override val scheduleIntent by lazy { Observable.merge(schedule.clicks(), scheduleLabel.clicks()) }
    override val attachContactIntent by lazy { Observable.merge(contact.clicks(), contactLabel.clicks()) }
    override val attachmentSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val contactSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val inputContentIntent by lazy { message.inputContentSelected }
    override val scheduleSelectedIntent: Subject<Long> = PublishSubject.create()
    override val changeSimIntent by lazy { sim.clicks() }
    override val scheduleCancelIntent by lazy { scheduledCancel.clicks() }
    override val sendIntent by lazy { send.clicks() }
    override val viewQksmsPlusIntent: Subject<Unit> = PublishSubject.create()
    override val backPressedIntent: Subject<Unit> = PublishSubject.create()
    override val smartReplyIntent by lazy { smartReply.clicks() }
    override val selectSuggestionIntent: Subject<String> = PublishSubject.create()

    private val suggestionsAdapter by lazy {
        SuggestionChipsAdapter { suggestion ->
            selectSuggestionIntent.onNext(suggestion)
        }
    }

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[ComposeViewModel::class.java] }

    private var cameraDestination: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        
        // Ensure window soft input mode is set for proper keyboard handling
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        
        setContentView(R.layout.compose_activity)

        contentView = findViewById(R.id.contentView)
        toolbar = findViewById(R.id.toolbar)
        toolbarTitle = findViewById(R.id.toolbarTitle)
        toolbarSubtitle = findViewById(R.id.toolbarSubtitle)
        chips = findViewById(R.id.chips)
        composeBar = findViewById(R.id.composeBar)
        sendAsGroup = findViewById(R.id.sendAsGroup)
        sendAsGroupBackground = findViewById(R.id.sendAsGroupBackground)
        sendAsGroupSwitch = findViewById(R.id.sendAsGroupSwitch)
        messageList = findViewById(R.id.messageList)
        messagesEmpty = findViewById(R.id.messagesEmpty)
        loading = findViewById(R.id.loading)
        messageBackground = findViewById(R.id.messageBackground)
        scheduledGroup = findViewById(R.id.scheduledGroup)
        scheduledTime = findViewById(R.id.scheduledTime)
        scheduledCancel = findViewById(R.id.scheduledCancel)
        attachments = findViewById(R.id.attachments)
        attaching = findViewById(R.id.attaching)
        attachingBackground = findViewById(R.id.attachingBackground)
        camera = findViewById(R.id.camera)
        cameraLabel = findViewById(R.id.cameraLabel)
        gallery = findViewById(R.id.gallery)
        galleryLabel = findViewById(R.id.galleryLabel)
        schedule = findViewById(R.id.schedule)
        scheduleLabel = findViewById(R.id.scheduleLabel)
        contact = findViewById(R.id.contact)
        contactLabel = findViewById(R.id.contactLabel)
        message = findViewById(R.id.message)
        attach = findViewById(R.id.attach)
        counter = findViewById(R.id.counter)
        sim = findViewById(R.id.sim)
        simIndex = findViewById(R.id.simIndex)
        send = findViewById(R.id.send)
        smartReply = findViewById(R.id.smartReply)
        suggestionsChips = findViewById(R.id.suggestionsChips)

        showBackButton(true)
        viewModel.bindView(this)

        // Preload interstitial ad
        interstitialAdManager.loadAd(this)

        contentView.layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.CHANGING)
        }

        chipsAdapter.view = chips

        chips.itemAnimator = null
        chips.layoutManager = FlexboxLayoutManager(this)

        messageAdapter.autoScrollToStart(messageList)
        messageAdapter.emptyView = messagesEmpty

        messageList.setHasFixedSize(true)
        messageList.adapter = messageAdapter
        
        // Auto-scroll to bottom when new messages arrive (if user is already near the bottom)
        messageAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val layoutManager = messageList.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.stackFromEnd) {
                    val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                    val totalItemCount = messageAdapter.itemCount
                    // Scroll if new items were added at the end and user is already near the bottom (within 3 items)
                    if (totalItemCount > 0 && positionStart + itemCount - 1 >= totalItemCount - itemCount && 
                        lastVisiblePosition >= totalItemCount - itemCount - 3) {
                        val targetPosition = totalItemCount - 1
                        if (targetPosition >= 0 && targetPosition < totalItemCount) {
                            messageList.post {
                                messageList.smoothScrollToPosition(targetPosition)
                            }
                        }
                    }
                }
            }
            
            override fun onChanged() {
                val layoutManager = messageList.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.stackFromEnd) {
                    val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                    val totalItemCount = messageAdapter.itemCount
                    // Scroll if user is already near the bottom (within 3 items) and there are items
                    if (totalItemCount > 0 && lastVisiblePosition >= totalItemCount - 3) {
                        val targetPosition = totalItemCount - 1
                        if (targetPosition >= 0 && targetPosition < totalItemCount) {
                            messageList.post {
                                messageList.smoothScrollToPosition(targetPosition)
                            }
                        }
                    }
                }
            }
        })

        attachments.adapter = attachmentAdapter

        suggestionsChips.adapter = suggestionsAdapter

        message.supportsInputContent = true

        theme
                .doOnNext { loading.setTint(it.theme) }
                .doOnNext { attach.setBackgroundTint(it.theme) }
                .doOnNext { attach.setTint(it.textPrimary) }
                .doOnNext { messageAdapter.theme = it }
                .doOnNext { suggestionsAdapter.theme = it }
                .autoDisposable(scope())
                .subscribe()

        window.callback = ComposeWindowCallback(window.callback, this)

        // Handle keyboard visibility to ensure proper resizing and scrolling
        setupKeyboardListener()

        // These theme attributes don't apply themselves on API 21
        if (Build.VERSION.SDK_INT <= 22) {
            messageBackground.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
        }
    }
    
    override fun setupWindowInsets() {
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        rootView?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                
                // Apply system bar padding (top, left, right only)
                // Bottom padding is adjusted dynamically by the keyboard listener.
                contentView.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    navigationBar.bottom
                )
                
                // Position adView above navigation bar by setting bottom margin
                val adView = findViewById<com.google.android.gms.ads.AdView>(R.id.adView)
                adView?.let { ad ->
                    val layoutParams = ad.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    layoutParams?.let { params ->
                        params.bottomMargin = navigationBar.bottom
                        ad.layoutParams = params
                    }
                }
                
                insets
            }
        }
    }
    
    private fun setupKeyboardListener() {
        val rootView = window.decorView.rootView
        var lastKeyboardHeight = 0
        
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val rect = Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootView.height
                val keypadHeight = screenHeight - rect.bottom
                
                // Get navigation bar height
                val navigationBarHeight = ViewCompat.getRootWindowInsets(rootView)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                
                // Consider keyboard visible if it takes up more than 15% of the screen
                val isKeyboardVisible = keypadHeight > screenHeight * 0.15
                
                // Update adView margin to account for navigation bar (always, not just when keyboard is visible)
                val adView = findViewById<com.google.android.gms.ads.AdView>(R.id.adView)
                adView?.let { ad ->
                    val layoutParams = ad.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    layoutParams?.let { params ->
                        if (params.bottomMargin != navigationBarHeight) {
                            params.bottomMargin = navigationBarHeight
                            ad.layoutParams = params
                        }
                    }
                }
                
                if (isKeyboardVisible && keypadHeight != lastKeyboardHeight) {
                    lastKeyboardHeight = keypadHeight
                    
                    // Keyboard is visible - add bottom padding to contentView to push content up
                    val currentPadding = contentView.paddingBottom
                    if (currentPadding != keypadHeight) {
                        contentView.setPadding(
                            contentView.paddingLeft,
                            contentView.paddingTop,
                            contentView.paddingRight,
                            keypadHeight
                        )

                        // Don't auto-scroll when keyboard appears - it interferes with text selection
                        // messageList.postDelayed({
                        //     val layoutManager = messageList.layoutManager as? LinearLayoutManager
                        //     if (layoutManager != null && messageAdapter.itemCount > 0) {
                        //         val lastPosition = messageAdapter.itemCount - 1
                        //         if (lastPosition >= 0) {
                        //             messageList.smoothScrollToPosition(lastPosition)
                        //         }
                        //     }
                        // }, 100)
                    }
                } else if (!isKeyboardVisible && lastKeyboardHeight > 0) {
                    // Keyboard is hidden - remove padding
                    lastKeyboardHeight = 0
                    val navigationBarHeight = ViewCompat.getRootWindowInsets(rootView)
                        ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                    contentView.setPadding(
                        contentView.paddingLeft,
                        contentView.paddingTop,
                        contentView.paddingRight,
                        navigationBarHeight
                    )
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        activityVisibleIntent.onNext(true)
    }

    override fun onPause() {
        super.onPause()
        activityVisibleIntent.onNext(false)

        // Show interstitial ad when leaving the compose screen
        interstitialAdManager.maybeShowAd(this)
    }

    override fun render(state: ComposeState) {
        if (state.hasError) {
            finish()
            return
        }

        threadId.onNext(state.threadId)

        title = when {
            state.selectedMessages > 0 -> getString(R.string.compose_title_selected, state.selectedMessages)
            state.query.isNotEmpty() -> state.query
            else -> state.conversationtitle
        }

        toolbarSubtitle.setVisible(state.query.isNotEmpty())
        toolbarSubtitle.text = getString(R.string.compose_subtitle_results, state.searchSelectionPosition,
                state.searchResults)

        toolbarTitle.setVisible(!state.editingMode)
        chips.setVisible(state.editingMode)
        composeBar.setVisible(!state.loading)

        // Don't set the adapters unless needed
        if (state.editingMode && chips.adapter == null) chips.adapter = chipsAdapter

        toolbar.menu.findItem(R.id.add)?.isVisible = state.editingMode
        toolbar.menu.findItem(R.id.call)?.isVisible = !state.editingMode && state.selectedMessages == 0
                && state.query.isEmpty()
        toolbar.menu.findItem(R.id.info)?.isVisible = !state.editingMode && state.selectedMessages == 0
                && state.query.isEmpty()
        toolbar.menu.findItem(R.id.copy)?.isVisible = !state.editingMode && state.selectedMessages > 0
        toolbar.menu.findItem(R.id.details)?.isVisible = !state.editingMode && state.selectedMessages == 1
        toolbar.menu.findItem(R.id.delete)?.isVisible = !state.editingMode && state.selectedMessages > 0
        toolbar.menu.findItem(R.id.forward)?.isVisible = !state.editingMode && state.selectedMessages == 1
        toolbar.menu.findItem(R.id.previous)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()
        toolbar.menu.findItem(R.id.next)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()
        toolbar.menu.findItem(R.id.clear)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()

        chipsAdapter.data = state.selectedChips

        loading.setVisible(state.loading)

        sendAsGroup.setVisible(state.editingMode && state.selectedChips.size >= 2)
        sendAsGroupSwitch.isChecked = state.sendAsGroup

        messageList.setVisible(!state.editingMode || state.sendAsGroup || state.selectedChips.size == 1)
        messageAdapter.messagesData = state.messages
        messageAdapter.highlight = state.searchSelectionId

        scheduledGroup.isVisible = state.scheduled != 0L
        scheduledTime.text = dateFormatter.getScheduledTimestamp(state.scheduled)

        attachments.setVisible(state.attachments.isNotEmpty())
        attachmentAdapter.data = state.attachments

        attach.animate().rotation(if (state.attaching) 135f else 0f).start()
        attaching.isVisible = state.attaching

        counter.text = state.remaining
        counter.setVisible(counter.text.isNotBlank())

        sim.setVisible(state.subscription != null)
        sim.contentDescription = getString(R.string.compose_sim_cd, state.subscription?.displayName)
        simIndex.text = state.subscription?.simSlotIndex?.plus(1)?.toString()

        send.isEnabled = state.canSend
        send.imageAlpha = if (state.canSend) 255 else 128

        // Smart Reply UI
        smartReply.setVisible(prefs.aiReplyEnabled.get() && !state.editingMode)
        smartReply.alpha = if (state.loadingSuggestions) 0.5f else 1.0f
        smartReply.isEnabled = !state.loadingSuggestions

        suggestionsChips.setVisible(state.showingSuggestions && state.suggestedReplies.isNotEmpty())
        suggestionsAdapter.suggestions = state.suggestedReplies
    }

    override fun clearSelection() = messageAdapter.clearSelection()

    override fun showDetails(details: String) {
        AlertDialog.Builder(this)
                .setTitle(R.string.compose_details_title)
                .setMessage(details)
                .setCancelable(true)
                .show()
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    override fun requestStoragePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
    }

    override fun requestSmsPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS), 0)
    }

    override fun requestDatePicker() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, DatePickerDialog.OnDateSetListener { _, year, month, day ->
            TimePickerDialog(this, TimePickerDialog.OnTimeSetListener { _, hour, minute ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                scheduleSelectedIntent.onNext(calendar.timeInMillis)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), DateFormat.is24HourFormat(this))
                    .show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()

        // On some devices, the keyboard can cover the date picker
        message.hideKeyboard()
    }

    override fun requestContact() {
        val intent = Intent(Intent.ACTION_PICK)
                .setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

        startActivityForResult(Intent.createChooser(intent, null), AttachContactRequestCode)
    }

    override fun showContacts(sharing: Boolean, chips: List<Recipient>) {
        message.hideKeyboard()
        val serialized = HashMap(chips.associate { chip -> chip.address to chip.contact?.lookupKey })
        val intent = Intent(this, ContactsActivity::class.java)
                .putExtra(ContactsActivity.SharingKey, sharing)
                .putExtra(ContactsActivity.ChipsKey, serialized)
        startActivityForResult(intent, SelectContactRequestCode)
    }

    override fun themeChanged() {
        messageList.scrapViews()
    }

    override fun showKeyboard() {
        message.postDelayed({
            message.showKeyboard()
        }, 200)
    }

    override fun requestCamera() {
        cameraDestination = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                .let { timestamp -> ContentValues().apply { put(MediaStore.Images.Media.TITLE, timestamp) } }
                .let { cv -> contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, cameraDestination)
        startActivityForResult(Intent.createChooser(intent, null), TakePhotoRequestCode)
    }

    override fun requestGallery() {
        val intent = Intent(Intent.ACTION_PICK)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType("image/*")
        startActivityForResult(Intent.createChooser(intent, null), AttachPhotoRequestCode)
    }

    override fun setDraft(draft: String) {
        message.setText(draft)
        message.setSelection(draft.length)
    }

    override fun scrollToMessage(id: Long) {
        messageAdapter.messagesData?.second
                ?.indexOfLast { message -> message.id == id }
                ?.takeIf { position -> position != -1 }
                ?.let(messageList::scrollToPosition)
    }

    override fun showQksmsPlusSnackbar(message: Int) {
        Snackbar.make(contentView, message, Snackbar.LENGTH_LONG).run {
            setAction(R.string.button_more) { viewQksmsPlusIntent.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.compose, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun getColoredMenuItems(): List<Int> {
        return super.getColoredMenuItems() + R.id.call
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == SelectContactRequestCode -> {
                chipsSelectedIntent.onNext(data?.getSerializableExtra(ContactsActivity.ChipsKey)
                        ?.let { serializable -> serializable as? HashMap<String, String?> }
                        ?: hashMapOf())
            }
            requestCode == TakePhotoRequestCode && resultCode == Activity.RESULT_OK -> {
                cameraDestination?.let(attachmentSelectedIntent::onNext)
            }
            requestCode == AttachPhotoRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount
                        ?.let { count -> 0 until count }
                        ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                        ?.forEach(attachmentSelectedIntent::onNext)
                        ?: data?.data?.let(attachmentSelectedIntent::onNext)
            }
            requestCode == AttachContactRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.data?.let(contactSelectedIntent::onNext)
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(CameraDestinationKey, cameraDestination)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        cameraDestination = savedInstanceState?.getParcelable(CameraDestinationKey)
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() = backPressedIntent.onNext(Unit)

}
