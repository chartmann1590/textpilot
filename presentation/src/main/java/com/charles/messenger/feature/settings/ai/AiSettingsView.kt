/*
 * Copyright (C) 2024 Charles Hartmann
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.charles.messenger.feature.settings.ai

import com.charles.messenger.common.base.QkViewContract
import com.charles.messenger.common.widget.PreferenceView
import com.charles.messenger.model.AiProvider
import io.reactivex.Observable

interface AiSettingsView : QkViewContract<AiSettingsState> {

    fun preferenceClicks(): Observable<PreferenceView>
    fun testConnectionClicks(): Observable<Unit>
    fun aiEnabledChanged(): Observable<Boolean>
    fun providerSelected(): Observable<AiProvider>
    fun ollamaUrlChanged(): Observable<String>
    fun modelSelected(): Observable<String>
    fun autoReplyToAllChanged(): Observable<Boolean>
    fun personaChanged(): Observable<String>
    fun signatureEnabledChanged(): Observable<Boolean>
    fun signatureTextChanged(): Observable<String>

    fun showToast(message: String)
    fun showProviderPicker(selected: AiProvider)
    fun showModelPicker(models: List<com.charles.messenger.model.AiModelOption>, selected: String)
    fun showUrlInputDialog(currentUrl: String)
    fun showPersonaInputDialog(currentPersona: String)
    fun showSignatureInputDialog(currentSignature: String)
}
