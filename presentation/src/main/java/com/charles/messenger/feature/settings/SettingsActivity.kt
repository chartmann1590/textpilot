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
package com.charles.messenger.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.charles.messenger.R
import com.charles.messenger.common.base.QkThemedActivity
import com.charles.messenger.feature.settings.ai.AiSettingsController
import com.charles.messenger.feature.settings.ai.tutorial.AiTutorialController
import com.charles.messenger.feature.settings.websync.WebSyncSettingsController
import dagger.android.AndroidInjection

class SettingsActivity : QkThemedActivity() {

    private lateinit var router: Router

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.container_activity)

        val container = findViewById<ViewGroup>(R.id.container)
        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            val screen = intent.getStringExtra("screen")
            val controller = when (screen) {
                "ai_settings" -> AiSettingsController()
                "ai_tutorial" -> AiTutorialController()
                "web_sync" -> WebSyncSettingsController()
                else -> SettingsController()
            }
            router.setRoot(RouterTransaction.with(controller))
        }
    }

    override fun onBackPressed() {
        if (!router.handleBack()) {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        router.onActivityResult(requestCode, resultCode, data)
    }

}