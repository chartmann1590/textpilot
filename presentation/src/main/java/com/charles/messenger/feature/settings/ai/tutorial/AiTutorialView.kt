package com.charles.messenger.feature.settings.ai.tutorial

import com.charles.messenger.common.base.QkViewContract
import io.reactivex.Observable

interface AiTutorialView : QkViewContract<AiTutorialState> {

    fun nextClicks(): Observable<Unit>
    fun backClicks(): Observable<Unit>
    fun skipClicks(): Observable<Unit>
    fun chooseOnDeviceClicks(): Observable<Unit>
    fun chooseOllamaClicks(): Observable<Unit>

    fun closeTutorial()
    fun openAiSettings()
}
