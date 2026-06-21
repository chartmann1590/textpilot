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
package com.charles.messenger.feature.blocking.numbers

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import com.jakewharton.rxbinding2.view.clicks
import com.charles.messenger.R
import com.charles.messenger.common.base.QkController
import com.charles.messenger.common.util.Colors
import com.charles.messenger.common.util.extensions.setBackgroundTint
import com.charles.messenger.common.util.extensions.setTint
import com.charles.messenger.injection.appComponent
import com.charles.messenger.util.PhoneNumberUtils
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class BlockedNumbersController : QkController<BlockedNumbersView, BlockedNumbersState, BlockedNumbersPresenter>(),
    BlockedNumbersView {

    @Inject override lateinit var presenter: BlockedNumbersPresenter
    @Inject lateinit var colors: Colors
    @Inject lateinit var phoneNumberUtils: PhoneNumberUtils

    private lateinit var add: ImageView
    private lateinit var empty: TextView
    private lateinit var numbers: RecyclerView

    private val adapter = BlockedNumbersAdapter()
    private val saveAddressSubject: Subject<String> = PublishSubject.create()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.blocked_numbers_controller
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.blocked_numbers_title)
        showBackButton(true)
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        add = view.findViewById(R.id.add)
        empty = view.findViewById(R.id.empty)
        numbers = view.findViewById(R.id.numbers)

        add.setBackgroundTint(colors.theme().theme)
        add.setTint(colors.theme().textPrimary)
        adapter.emptyView = empty
        numbers.adapter = adapter
    }

    override fun render(state: BlockedNumbersState) {
        adapter.updateData(state.numbers)
    }

    override fun unblockAddress(): Observable<Long> = adapter.unblockAddress
    override fun addAddress(): Observable<*> = add.clicks()
    override fun saveAddress(): Observable<String> = saveAddressSubject

    override fun showAddDialog() {
        val layout = LayoutInflater.from(activity).inflate(R.layout.blocked_numbers_add_dialog, null)
        val input = layout.findViewById<EditText>(R.id.input)
        val textWatcher = BlockedNumberTextWatcher(input, phoneNumberUtils)
        val dialog = AlertDialog.Builder(activity!!)
                .setView(layout)
                .setPositiveButton(R.string.blocked_numbers_dialog_block) { _, _ ->
                    saveAddressSubject.onNext(input.text.toString())
                }
                .setNegativeButton(R.string.button_cancel) { _, _ -> }
                .setOnDismissListener { textWatcher.dispose() }
        dialog.show()
    }

}
