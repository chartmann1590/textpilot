package com.charles.messenger.feature.settings.moreapps

import android.view.View
import android.view.ViewGroup
import com.charles.messenger.R
import com.charles.messenger.common.Navigator
import com.charles.messenger.common.base.QkController
import com.charles.messenger.common.widget.PreferenceView
import com.charles.messenger.injection.appComponent
import javax.inject.Inject

data class CrossPromoApp(
    val name: String,
    val packageName: String,
    val tagline: String,
)

private val crossPromoApps: List<CrossPromoApp> = listOf(
    CrossPromoApp("NutriSnap: AI Calorie Tracker", "com.charles.nutrisnap", "Snap a meal, get instant calories & macros — 100% on-device AI, private."),
    CrossPromoApp("Aria: On-Device Assistant", "com.aria.assistant", "Private on-device voice AI with optional, source-backed web verification."),
    CrossPromoApp("ScamRadar: AI Scam Detector", "com.charles.scamradar.app", "On-device AI catches scams in texts, voicemails & notifications. Free."),
    CrossPromoApp("MeshTalk: Bluetooth Mesh Chat", "com.charles.meshtalk.app", "Chat, talk & AI over Bluetooth mesh. No internet, no accounts, fully offline."),
    CrossPromoApp("DriveVault Dashcam", "com.drivevault.dashcam", "Privacy-first dashcam: GPS overlays, dual-camera, background recording."),
    CrossPromoApp("PixelDream: Offline AI Images", "com.hartmann.pixeldream", "Private, offline AI image generator. Your prompts and pictures never leave."),
    CrossPromoApp("Pocket-Assistant", "com.charles.pocketassistant", "Local AI organizer: save bills & notes, chat, tasks, and reminders on-device."),
    CrossPromoApp("Pixel Fish Tank", "com.charles.virtualpet.fishtank", "Cozy virtual pet game — feed, clean & customize your pixel fish. Play & relax!"),
    CrossPromoApp("TrailSage AI: Road Trip Guide", "com.charles.trailsage", "Private, offline GPS audio tour guide with on-device AI storytelling."),
    CrossPromoApp("Knightfall: Chess with AI Coach", "com.chartmann.knightfall", "Play chess against Stockfish AI with Gemma 4 coaching, online, or on the web!"),
    CrossPromoApp("CaptionBurn: Video Captions", "com.charlesh.captionburn", "On-device auto-captions, burned into your video, with built-in translation."),
    CrossPromoApp("Jury Simulator: Trial Verdict", "com.charles.jurysim", "Step into jury duty with AI trials, eleven jurors, and the verdict in your hands."),
    CrossPromoApp("Photobooth Event Camera", "com.charles.photobooth", "Turn any Android device into a fun event photo booth with sharing and prints."),
    CrossPromoApp("Path - Daily Bible Study", "com.biblereadingpath.app", "Build daily Bible study habits with gentle streaks."),
    CrossPromoApp("Dreamloom: AI Dream Journal", "com.charles.app.dreamloom", "Private dream journal with on-device AI insights, symbols, and weekly patterns."),
    CrossPromoApp("SkyPulse: Live Flight Tracker", "com.charles.skypulse.app", "Track live flights overhead in real time — aircraft, airports & smart alerts."),
    CrossPromoApp("Grocy Fridge Scanner", "com.charleshartmann.grocyfridge", "Snap your fridge. On-device AI updates your Grocy stock in seconds. No cloud."),
    CrossPromoApp("CrowdTransit: Bus & Train", "com.charles.crowdtransit.app", "Find your ride — free live transit stops, schedules & reviews, nationwide."),
)

class MoreAppsController : QkController<MoreAppsView, Unit, MoreAppsPresenter>(), MoreAppsView {

    @Inject override lateinit var presenter: MoreAppsPresenter
    @Inject lateinit var navigator: Navigator

    private lateinit var preferences: ViewGroup

    init {
        appComponent.inject(this)
        layoutRes = R.layout.more_apps_controller
    }

    override fun onViewCreated(view: View) {
        preferences = view.findViewById(R.id.preferences)

        crossPromoApps.forEach { app ->
            val row = PreferenceView(view.context)
            row.title = app.name
            row.summary = app.tagline
            row.setOnClickListener { navigator.installOtherApp(app.packageName) }
            preferences.addView(row)
        }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle("More from this developer")
        showBackButton(true)
    }

    override fun render(state: Unit) {
        // No special rendering required
    }

}
