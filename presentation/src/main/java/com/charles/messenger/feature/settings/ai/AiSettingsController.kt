package com.charles.messenger.feature.settings.ai

import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bluelinelabs.conductor.RouterTransaction
import com.charles.messenger.R
import com.charles.messenger.common.QkChangeHandler
import com.charles.messenger.common.base.QkController
import com.charles.messenger.common.widget.PreferenceView
import com.charles.messenger.common.widget.QkSwitch
import com.charles.messenger.feature.settings.ai.tutorial.AiTutorialController
import com.charles.messenger.injection.appComponent
import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.AiProvider
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.checkedChanges
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class AiSettingsController : QkController<AiSettingsView, AiSettingsState, AiSettingsPresenter>(), AiSettingsView {

    @Inject override lateinit var presenter: AiSettingsPresenter

    private val providerSelectedSubject: Subject<AiProvider> = PublishSubject.create()
    private val urlChangedSubject: Subject<String> = PublishSubject.create()
    private val modelSelectedSubject: Subject<String> = PublishSubject.create()
    private val personaChangedSubject: Subject<String> = PublishSubject.create()
    private val signatureTextChangedSubject: Subject<String> = PublishSubject.create()

    private lateinit var preferences: LinearLayout
    private lateinit var aiEnabled: PreferenceView
    private lateinit var aiTutorial: PreferenceView
    private lateinit var providerSelection: PreferenceView
    private lateinit var ollamaUrl: PreferenceView
    private lateinit var modelSelection: PreferenceView
    private lateinit var onDeviceModelName: PreferenceView
    private lateinit var onDeviceModelPath: PreferenceView
    private lateinit var testConnection: Button
    private lateinit var connectionStatus: TextView
    private lateinit var onDeviceHelp: TextView
    private lateinit var aiAutoReplyToAll: PreferenceView
    private lateinit var autoReplyWarning: TextView
    private lateinit var aiPersona: PreferenceView
    private lateinit var aiSignatureEnabled: PreferenceView
    private lateinit var aiSignatureText: PreferenceView
    private lateinit var signaturePreview: TextView

    init {
        appComponent.inject(this)
        layoutRes = R.layout.ai_settings_controller
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        preferences = view.findViewById(R.id.preferences)
        aiEnabled = view.findViewById(R.id.aiEnabled)
        aiTutorial = view.findViewById(R.id.aiTutorial)
        providerSelection = view.findViewById(R.id.providerSelection)
        ollamaUrl = view.findViewById(R.id.ollamaUrl)
        modelSelection = view.findViewById(R.id.modelSelection)
        onDeviceModelName = view.findViewById(R.id.onDeviceModelName)
        onDeviceModelPath = view.findViewById(R.id.onDeviceModelPath)
        testConnection = view.findViewById(R.id.testConnection)
        connectionStatus = view.findViewById(R.id.connectionStatus)
        onDeviceHelp = view.findViewById(R.id.onDeviceHelp)
        aiAutoReplyToAll = view.findViewById(R.id.aiAutoReplyToAll)
        autoReplyWarning = view.findViewById(R.id.autoReplyWarning)
        aiPersona = view.findViewById(R.id.aiPersona)
        aiSignatureEnabled = view.findViewById(R.id.aiSignatureEnabled)
        aiSignatureText = view.findViewById(R.id.aiSignatureText)
        signaturePreview = view.findViewById(R.id.signaturePreview)

        presenter.bindIntents(this)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        setTitle(R.string.ai_settings_title)
        showBackButton(true)

        aiEnabled.setOnClickListener {
            val switch = aiEnabled.widget?.findViewById<QkSwitch>(R.id.checkbox)
            switch?.isChecked = !(switch?.isChecked ?: false)
        }

        aiAutoReplyToAll.setOnClickListener {
            val switch = aiAutoReplyToAll.widget?.findViewById<QkSwitch>(R.id.checkbox)
            switch?.isChecked = !(switch?.isChecked ?: false)
        }

        aiSignatureEnabled.setOnClickListener {
            val switch = aiSignatureEnabled.widget?.findViewById<QkSwitch>(R.id.checkbox)
            switch?.isChecked = !(switch?.isChecked ?: false)
        }

        aiTutorial.setOnClickListener {
            router.pushController(
                RouterTransaction.with(AiTutorialController())
                    .pushChangeHandler(QkChangeHandler())
                    .popChangeHandler(QkChangeHandler())
            )
        }
    }

    override fun preferenceClicks(): Observable<PreferenceView> = Observable.empty()

    override fun testConnectionClicks(): Observable<Unit> = testConnection.clicks()

    override fun aiEnabledChanged(): Observable<Boolean> {
        return aiEnabled.widget?.let { widget ->
            (widget.findViewById<View>(R.id.checkbox) as? QkSwitch)
                ?.checkedChanges()
                ?.skipInitialValue()
        } ?: Observable.empty()
    }

    override fun providerSelected(): Observable<AiProvider> = providerSelectedSubject

    override fun ollamaUrlChanged(): Observable<String> = urlChangedSubject

    override fun modelSelected(): Observable<String> = modelSelectedSubject

    override fun autoReplyToAllChanged(): Observable<Boolean> {
        return aiAutoReplyToAll.widget?.let { widget ->
            (widget.findViewById<View>(R.id.checkbox) as? QkSwitch)
                ?.checkedChanges()
                ?.skipInitialValue()
        } ?: Observable.empty()
    }

    override fun personaChanged(): Observable<String> = personaChangedSubject

    override fun signatureEnabledChanged(): Observable<Boolean> {
        return aiSignatureEnabled.widget?.let { widget ->
            (widget.findViewById<View>(R.id.checkbox) as? QkSwitch)
                ?.checkedChanges()
                ?.skipInitialValue()
        } ?: Observable.empty()
    }

    override fun signatureTextChanged(): Observable<String> = signatureTextChangedSubject

    override fun showToast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    override fun showProviderPicker(selected: AiProvider) {
        val options = arrayOf(
            activity!!.getString(R.string.ai_provider_ollama),
            activity!!.getString(R.string.ai_provider_on_device)
        )
        val selectedIndex = if (selected == AiProvider.OLLAMA) 0 else 1

        AlertDialog.Builder(activity)
            .setTitle(R.string.ai_settings_provider)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                providerSelectedSubject.onNext(if (which == 0) AiProvider.OLLAMA else AiProvider.ON_DEVICE)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun showModelPicker(models: List<AiModelOption>, selected: String) {
        if (models.isEmpty()) {
            showToast("No models available yet")
            return
        }

        val labels = models.map { model ->
            when {
                model.installed -> "${model.displayName} (Installed)"
                model.summary.isNotBlank() -> "${model.displayName} (${model.summary})"
                else -> model.displayName
            }
        }

        val selectedIndex = models.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(activity)
            .setTitle(R.string.ai_settings_model)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                modelSelectedSubject.onNext(models[which].id)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun showUrlInputDialog(currentUrl: String) {
        val editText = EditText(activity).apply {
            setText(currentUrl)
            hint = "http://10.0.2.2:11434"
            setSingleLine()
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.ai_settings_ollama_url)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) {
                    urlChangedSubject.onNext(url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun showPersonaInputDialog(currentPersona: String) {
        val editText = EditText(activity!!).apply {
            setText(currentPersona)
            hint = activity!!.getString(R.string.ai_settings_persona_dialog_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 5
        }

        AlertDialog.Builder(activity!!)
            .setTitle(R.string.ai_settings_persona_dialog_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                personaChangedSubject.onNext(editText.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun showSignatureInputDialog(currentSignature: String) {
        val editText = EditText(activity!!).apply {
            setText(currentSignature)
            hint = activity!!.getString(R.string.ai_settings_signature_dialog_hint)
            setSingleLine()
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.ai_settings_signature_dialog_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                signatureTextChangedSubject.onNext(editText.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun render(state: AiSettingsState) {
        aiEnabled.widget?.let { widget ->
            (widget.findViewById<View>(R.id.checkbox) as? QkSwitch)?.isChecked = state.aiEnabled
        }

        providerSelection.summary = when (state.provider) {
            AiProvider.OLLAMA -> activity!!.getString(R.string.ai_provider_ollama)
            AiProvider.ON_DEVICE -> activity!!.getString(R.string.ai_provider_on_device)
        }
        providerSelection.setOnClickListener {
            showProviderPicker(state.provider)
        }

        ollamaUrl.summary = state.ollamaUrl.ifEmpty { "Not configured" }
        ollamaUrl.setOnClickListener {
            showUrlInputDialog(state.ollamaUrl)
        }

        modelSelection.summary = when (state.provider) {
            AiProvider.OLLAMA -> state.ollamaModel.ifEmpty { activity!!.getString(R.string.ai_settings_model_summary) }
            AiProvider.ON_DEVICE -> state.onDeviceModelName.ifEmpty { activity!!.getString(R.string.ai_settings_on_device_model_name_summary) }
        }
        modelSelection.setOnClickListener {
            showModelPicker(
                state.availableModels,
                when (state.provider) {
                    AiProvider.OLLAMA -> state.ollamaModel
                    AiProvider.ON_DEVICE -> state.availableModels.firstOrNull {
                        it.displayName == state.onDeviceModelName
                    }?.id.orEmpty()
                }
            )
        }

        onDeviceModelName.visibility = View.GONE
        onDeviceModelPath.summary = state.onDeviceModelPath.ifEmpty {
            activity!!.getString(R.string.ai_settings_on_device_model_path_summary)
        }
        onDeviceModelPath.isClickable = false

        connectionStatus.text = when {
            state.installStatus.isNotBlank() -> state.installStatus
            state.connectionStatus == ConnectionStatus.Unknown -> ""
            state.connectionStatus == ConnectionStatus.Testing -> activity!!.getString(R.string.ai_settings_status_testing)
            state.connectionStatus == ConnectionStatus.Connected && state.provider == AiProvider.OLLAMA ->
                activity!!.getString(R.string.ai_settings_status_connected, state.availableModels.count { it.installed })
            state.connectionStatus == ConnectionStatus.Connected ->
                activity!!.getString(R.string.ai_settings_status_on_device_ready)
            else -> activity!!.getString(R.string.ai_settings_status_failed)
        }
        connectionStatus.visibility = if (connectionStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val ollamaVisible = state.provider == AiProvider.OLLAMA
        ollamaUrl.visibility = if (ollamaVisible) View.VISIBLE else View.GONE
        modelSelection.visibility = View.VISIBLE
        onDeviceModelPath.visibility = if (ollamaVisible) View.GONE else View.VISIBLE
        onDeviceHelp.visibility = if (ollamaVisible) View.GONE else View.VISIBLE
        testConnection.text = if (ollamaVisible) {
            activity!!.getString(R.string.ai_settings_test_connection)
        } else {
            activity!!.getString(R.string.ai_settings_validate_on_device)
        }

        testConnection.isEnabled = !state.loadingModels
        testConnection.alpha = if (state.loadingModels) 0.5f else 1.0f
        modelSelection.isEnabled = !state.loadingModels
        modelSelection.alpha = if (state.loadingModels) 0.5f else 1.0f

        aiAutoReplyToAll.widget?.let { widget ->
            (widget.findViewById<View>(R.id.checkbox) as? QkSwitch)?.isChecked = state.autoReplyToAll
        }
        autoReplyWarning.visibility = if (state.autoReplyToAll) View.VISIBLE else View.GONE

        aiPersona.summary = if (state.persona.isNotEmpty()) {
            if (state.persona.length > 50) state.persona.take(50) + "..." else state.persona
        } else {
            activity!!.getString(R.string.ai_settings_persona_not_set)
        }
        aiPersona.setOnClickListener {
            showPersonaInputDialog(state.persona)
        }

        aiSignatureEnabled.widget?.let { widget ->
            (widget.findViewById<View>(R.id.checkbox) as? QkSwitch)?.isChecked = state.signatureEnabled
        }

        aiSignatureText.summary = state.signatureText
        aiSignatureText.setOnClickListener {
            showSignatureInputDialog(state.signatureText)
        }

        if (state.signatureEnabled && state.signatureText.isNotEmpty()) {
            val exampleText = activity!!.getString(R.string.ai_settings_signature_example)
            signaturePreview.text = activity!!.getString(R.string.ai_settings_signature_preview) + "\n" +
                exampleText + "\n\n" + state.signatureText
            signaturePreview.visibility = View.VISIBLE
        } else {
            signaturePreview.visibility = View.GONE
        }
    }
}
