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
package com.charles.messenger.feature.themepicker

import android.animation.ObjectAnimator
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.charles.messenger.R
import com.charles.messenger.common.base.QkController
import com.charles.messenger.common.util.Colors
import com.charles.messenger.common.util.extensions.dpToPx
import com.charles.messenger.common.util.extensions.setBackgroundTint
import com.charles.messenger.common.util.extensions.setVisible
import com.charles.messenger.feature.themepicker.injection.ThemePickerModule
import com.charles.messenger.injection.appComponent
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import android.widget.ImageView
import android.widget.EditText
import android.view.ViewGroup
import androidx.constraintlayout.widget.Group
import com.charles.messenger.common.widget.PagerTitleView
import com.charles.messenger.common.widget.QkTextView
import com.charles.messenger.feature.themepicker.HSVPickerView
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import android.util.Log

class ThemePickerController(
    val recipientId: Long = 0L
) : QkController<ThemePickerView, ThemePickerState, ThemePickerPresenter>(), ThemePickerView {

    @Inject override lateinit var presenter: ThemePickerPresenter

    @Inject lateinit var colors: Colors
    @Inject lateinit var themeAdapter: ThemeAdapter
    @Inject lateinit var themePagerAdapter: ThemePagerAdapter

    private val viewQksmsPlusSubject: Subject<Unit> = PublishSubject.create()

    private lateinit var pager: ViewPager
    private lateinit var tabs: PagerTitleView
    private lateinit var materialColors: RecyclerView
    private lateinit var picker: HSVPickerView
    private lateinit var clear: ImageView
    private lateinit var apply: QkTextView
    private lateinit var hex: EditText
    private lateinit var applyGroup: Group
    private lateinit var contentView: View

    init {
        appComponent
                .themePickerBuilder()
                .themePickerModule(ThemePickerModule(this))
                .build()
                .inject(this)

        layoutRes = R.layout.theme_picker_controller
    }

    override fun onViewCreated(view: View) {
        pager = view.findViewById(R.id.pager)
        tabs = view.findViewById(R.id.tabs)
        materialColors = view.findViewById(R.id.materialColors)
        picker = view.findViewById(R.id.picker)
        clear = view.findViewById(R.id.clear)
        apply = view.findViewById(R.id.apply)
        hex = view.findViewById(R.id.hex)
        
        // applyGroup is in the included hsvPicker layout, find it from there to avoid ClassCastException
        val hsvPickerView = view.findViewById<View>(R.id.hsvPicker)
        val foundApplyGroup = hsvPickerView?.findViewById<Group>(R.id.applyGroup) ?: view.findViewById<Group>(R.id.applyGroup)
        if (foundApplyGroup !is Group) {
            throw ClassCastException("View with id applyGroup is not a Group: ${foundApplyGroup?.javaClass?.name}")
        }
        applyGroup = foundApplyGroup
        contentView = view.findViewById(R.id.contentView)
 
        pager.offscreenPageLimit = 1
        pager.adapter = themePagerAdapter
        tabs.pager = pager
 
        themeAdapter.data = colors.materialColors
 
        activity?.let {
            materialColors.layoutManager = LinearLayoutManager(it)
            materialColors.adapter = themeAdapter
        } ?: run {
            Timber.w("Activity is null, cannot set layout manager for theme picker")
        }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.title_theme)
        showBackButton(true)

        themedActivity?.supportActionBar?.let { toolbar ->
            ObjectAnimator.ofFloat(toolbar, "elevation", toolbar.elevation, 0f).start()
        }
    }

    override fun onDetach(view: View) {
        super.onDetach(view)

        themedActivity?.supportActionBar?.let { toolbar ->
            ObjectAnimator.ofFloat(toolbar, "elevation", toolbar.elevation, 8.dpToPx(toolbar.themedContext).toFloat()).start()
        }
    }

    override fun showQksmsPlusSnackbar() {
        Snackbar.make(contentView, R.string.toast_qksms_plus, Snackbar.LENGTH_LONG).run {
            setAction(R.string.button_more) { viewQksmsPlusSubject.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun themeSelected(): Observable<Int> = themeAdapter.colorSelected

    override fun hsvThemeSelected(): Observable<Int> = picker.selectedColor

    override fun clearHsvThemeClicks(): Observable<*> = clear.clicks()

    override fun applyHsvThemeClicks(): Observable<*> = apply.clicks()

    override fun viewQksmsPlusClicks(): Observable<*> = viewQksmsPlusSubject

    override fun render(state: ThemePickerState) {
        tabs.setRecipientId(state.recipientId)

        hex.setText(Integer.toHexString(state.newColor).takeLast(6))

        applyGroup.setVisible(state.applyThemeVisible)
        apply.setBackgroundTint(state.newColor)
        apply.setTextColor(state.newTextColor)
    }

    override fun setCurrentTheme(color: Int) {
        picker.setColor(color)
        themeAdapter.selectedColor = color
    }

}