package com.charles.messenger.feature.settings.feedback

import com.charles.messenger.common.base.QkPresenter
import javax.inject.Inject

class FeedbackPresenter @Inject constructor() : QkPresenter<FeedbackView, FeedbackState>(FeedbackState()) {

    override fun bindIntents(view: FeedbackView) {
        super.bindIntents(view)
    }
}
