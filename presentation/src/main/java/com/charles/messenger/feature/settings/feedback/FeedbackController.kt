package com.charles.messenger.feature.settings.feedback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.charles.messenger.BuildConfig
import com.charles.messenger.R
import com.charles.messenger.common.base.QkController
import com.charles.messenger.injection.appComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

class FeedbackController : QkController<FeedbackView, FeedbackState, FeedbackPresenter>(), FeedbackView {

    @Inject override lateinit var presenter: FeedbackPresenter

    companion object {
        private const val REQUEST_IMAGE_PICK = 4001
        private const val REQUEST_COMMENT_IMAGE_PICK = 4002
    }

    private lateinit var reportsList: LinearLayout
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Form state preserved across image picker launch
    private var formTitle = ""
    private var formDescription = ""
    private var formName = ""
    private var formEmail = ""
    private var formDiagnostics = true
    private var formImageUri: Uri? = null
    private var formDialog: AlertDialog? = null

    // Comment state preserved across image picker launch
    private var commentIssueNumber = 0
    private var commentIssueReport: BugReport? = null
    private var commentText = ""
    private var commentImageUri: Uri? = null
    private var commentDialog: AlertDialog? = null

    private val githubClient by lazy {
        GithubApiClient(
            BuildConfig.GITHUB_API_TOKEN,
            BuildConfig.GITHUB_REPO_OWNER,
            BuildConfig.GITHUB_REPO_NAME,
            BuildConfig.FEEDBACK_ASSETS_DIR
        )
    }

    private val bugReportRepo by lazy {
        BugReportRepo(activity!!.applicationContext)
    }

    init {
        appComponent.inject(this)
        layoutRes = R.layout.feedback_controller
    }

    override fun onViewCreated(view: View) {
        reportsList = view.findViewById(R.id.reportsList)
        view.findViewById<Button>(R.id.btnReportProblem).setOnClickListener {
            showReportForm(fresh = true)
        }
        loadAndDisplayReports()
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        setTitle(R.string.feedback_title)
        showBackButton(true)
        presenter.bindIntents(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_IMAGE_PICK -> {
                formImageUri = uri
                showReportForm(fresh = false)
            }
            REQUEST_COMMENT_IMAGE_PICK -> {
                commentImageUri = uri
                commentIssueReport?.let { showIssueDetailsDialog(it, null, restoreComment = true) }
            }
        }
    }

    override fun render(state: FeedbackState) {}

    // region Report list

    private fun loadAndDisplayReports() {
        scope.launch {
            val reports = withContext(Dispatchers.IO) { bugReportRepo.getReports() }
            displayReports(reports)
        }
    }

    private fun displayReports(reports: List<BugReport>) {
        if (!::reportsList.isInitialized) return
        reportsList.removeAllViews()
        if (reports.isEmpty()) {
            val tv = TextView(activity).apply {
                text = activity?.getString(R.string.feedback_no_reports)
                setPadding(48, 32, 48, 32)
            }
            reportsList.addView(tv)
            return
        }
        val inflater = LayoutInflater.from(activity)
        reports.forEach { report ->
            val item = inflater.inflate(R.layout.feedback_report_item, reportsList, false)
            item.findViewById<TextView>(R.id.reportTitle).text = report.title
            item.findViewById<TextView>(R.id.reportMeta).text =
                "#${report.number} · ${report.createdAt.take(10)}"
            val statusBadge = item.findViewById<TextView>(R.id.reportStatus)
            val isOpen = report.status.lowercase(Locale.getDefault()) == "open"
            statusBadge.text = report.status.replaceFirstChar { it.uppercase(Locale.getDefault()) }
            statusBadge.setTextColor(if (isOpen) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            item.setOnClickListener { openIssueDetails(report) }
            reportsList.addView(item)
        }
    }

    // endregion

    // region Report form dialog

    private fun showReportForm(fresh: Boolean) {
        if (fresh) {
            formTitle = ""; formDescription = ""; formName = ""
            formEmail = ""; formDiagnostics = true; formImageUri = null
        }
        val ctx = activity ?: return
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_report_form, null)

        val titleInput = view.findViewById<EditText>(R.id.inputTitle).apply { setText(formTitle) }
        val descInput = view.findViewById<EditText>(R.id.inputDescription).apply { setText(formDescription) }
        val nameInput = view.findViewById<EditText>(R.id.inputName).apply { setText(formName) }
        val emailInput = view.findViewById<EditText>(R.id.inputEmail).apply { setText(formEmail) }
        val diagnosticsCheck = view.findViewById<CheckBox>(R.id.checkDiagnostics).apply { isChecked = formDiagnostics }
        val imagePreview = view.findViewById<ImageView>(R.id.imagePreview)
        val btnAttach = view.findViewById<Button>(R.id.btnAttachImage)
        val btnClear = view.findViewById<Button>(R.id.btnClearImage)
        val configError = view.findViewById<TextView>(R.id.configError)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)

        if (!githubClient.isConfigured) {
            configError.visibility = View.VISIBLE
            configError.text = ctx.getString(R.string.feedback_config_error)
        } else {
            configError.visibility = View.GONE
        }

        fun updateImagePreview() {
            val uri = formImageUri
            if (uri != null) {
                imagePreview.visibility = View.VISIBLE
                imagePreview.setImageURI(uri)
                btnClear.visibility = View.VISIBLE
            } else {
                imagePreview.visibility = View.GONE
                btnClear.visibility = View.GONE
            }
        }
        updateImagePreview()

        btnAttach.setOnClickListener {
            formTitle = titleInput.text.toString()
            formDescription = descInput.text.toString()
            formName = nameInput.text.toString()
            formEmail = emailInput.text.toString()
            formDiagnostics = diagnosticsCheck.isChecked
            formDialog?.dismiss()
            formDialog = null
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_IMAGE_PICK)
        }

        btnClear.setOnClickListener {
            formImageUri = null
            updateImagePreview()
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.feedback_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.feedback_submit, null)
            .setNegativeButton(R.string.button_cancel, null)
            .create()

        dialog.setOnShowListener {
            val submitBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submitBtn.isEnabled = githubClient.isConfigured
            submitBtn.setOnClickListener {
                val title = titleInput.text.toString().trim()
                val desc = descInput.text.toString().trim()
                if (title.isEmpty()) { titleInput.error = ctx.getString(R.string.feedback_field_required); return@setOnClickListener }
                if (desc.isEmpty()) { descInput.error = ctx.getString(R.string.feedback_field_required); return@setOnClickListener }

                formTitle = title; formDescription = desc
                formName = nameInput.text.toString().trim()
                formEmail = emailInput.text.toString().trim()
                formDiagnostics = diagnosticsCheck.isChecked

                progressBar.visibility = View.VISIBLE
                submitBtn.isEnabled = false

                scope.launch {
                    submitReport(
                        ctx, title, desc, formName, formEmail,
                        formDiagnostics, formImageUri, progressBar, submitBtn, dialog
                    )
                }
            }
        }
        formDialog = dialog
        dialog.show()
    }

    private suspend fun submitReport(
        appCtx: Context,
        title: String,
        description: String,
        name: String,
        email: String,
        includeDiagnostics: Boolean,
        imageUri: Uri?,
        progressBar: ProgressBar,
        submitBtn: Button,
        dialog: AlertDialog
    ) {
        var imageMarkdown = ""
        if (imageUri != null) {
            val uploadResult = runCatching {
                val base64 = withContext(Dispatchers.IO) { ImageHelper.uriToBase64(appCtx, imageUri) }
                githubClient.uploadAsset(base64).getOrThrow()
            }
            uploadResult.onSuccess { url ->
                if (url.isNotBlank()) imageMarkdown = "\n\n## Attachment\n\n![Screenshot]($url)"
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    submitBtn.isEnabled = true
                    Toast.makeText(appCtx, appCtx.getString(R.string.feedback_image_upload_failed, e.message), Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        val diagnosticsSection = if (includeDiagnostics) "\n\n${DiagnosticsHelper.collect(appCtx)}" else ""
        val contactSection = "\n\n## Contact Info\n\n- Name: ${name.ifBlank { "Not provided" }}\n- Email: ${email.ifBlank { "Not provided" }}"
        val body = "## Description\n\n$description$contactSection$imageMarkdown$diagnosticsSection"

        val result = githubClient.createIssue("[Feedback] $title", body)
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            result.onSuccess { issue ->
                val report = BugReport(
                    number = issue.number,
                    title = title,
                    status = issue.state,
                    createdAt = issue.createdAt,
                    htmlUrl = issue.htmlUrl
                )
                withContext(Dispatchers.IO) { bugReportRepo.saveReport(report) }
                dialog.dismiss()
                formDialog = null
                formImageUri = null
                loadAndDisplayReports()
                Toast.makeText(
                    appCtx,
                    appCtx.getString(R.string.feedback_submitted_ok, issue.number),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { e ->
                submitBtn.isEnabled = true
                Toast.makeText(appCtx, appCtx.getString(R.string.feedback_submit_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // endregion

    // region Issue details dialog

    private fun openIssueDetails(report: BugReport) {
        commentIssueReport = report
        commentIssueNumber = report.number
        commentText = ""
        commentImageUri = null

        showIssueDetailsDialog(report, null, restoreComment = false)

        scope.launch {
            val issueResult = githubClient.getIssue(report.number)
            val commentsResult = githubClient.getComments(report.number)
            withContext(Dispatchers.Main) {
                val latestReport = issueResult.getOrNull()?.let { issue ->
                    report.copy(status = issue.state).also { updated ->
                        commentIssueReport = updated
                        scope.launch(Dispatchers.IO) { bugReportRepo.saveReport(updated) }
                    }
                } ?: report
                val comments = commentsResult.getOrNull() ?: emptyList()
                commentDialog?.dismiss()
                commentDialog = null
                showIssueDetailsDialog(latestReport, comments, restoreComment = false)
            }
        }
    }

    private fun showIssueDetailsDialog(
        report: BugReport,
        comments: List<GithubComment>?,
        restoreComment: Boolean
    ) {
        val ctx = activity ?: return
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_issue_details, null)

        view.findViewById<TextView>(R.id.issueTitle).text =
            ctx.getString(R.string.feedback_issue_header, report.number, report.title)

        val statusView = view.findViewById<TextView>(R.id.issueStatus)
        val isOpen = report.status.lowercase(Locale.getDefault()) == "open"
        statusView.text = report.status.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        statusView.setTextColor(if (isOpen) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))

        val commentsContainer = view.findViewById<LinearLayout>(R.id.commentsContainer)
        commentsContainer.removeAllViews()

        if (comments == null) {
            val loading = TextView(ctx).apply {
                text = ctx.getString(R.string.feedback_loading_comments)
                setPadding(0, 16, 0, 16)
            }
            commentsContainer.addView(loading)
        } else if (comments.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = ctx.getString(R.string.feedback_no_comments)
                setPadding(0, 16, 0, 16)
            }
            commentsContainer.addView(empty)
        } else {
            comments.forEach { comment ->
                val tv = TextView(ctx).apply {
                    text = "@${comment.user.login} · ${comment.createdAt.take(10)}\n\n${comment.body}"
                    setPadding(24, 16, 24, 16)
                    setBackgroundColor(Color.parseColor("#F5F5F5"))
                }
                commentsContainer.addView(tv)
                commentsContainer.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also {
                        it.topMargin = 8; it.bottomMargin = 8
                    }
                    setBackgroundColor(Color.LTGRAY)
                })
            }
        }

        val replyInput = view.findViewById<EditText>(R.id.inputReply).apply {
            if (restoreComment) setText(commentText)
        }
        val replyPreview = view.findViewById<ImageView>(R.id.replyImagePreview)
        val btnAttachReply = view.findViewById<Button>(R.id.btnAttachReplyImage)
        val btnClearReply = view.findViewById<Button>(R.id.btnClearReplyImage)
        val replyProgress = view.findViewById<ProgressBar>(R.id.replyProgressBar)

        fun updateReplyPreview() {
            val uri = commentImageUri
            if (uri != null) {
                replyPreview.visibility = View.VISIBLE
                replyPreview.setImageURI(uri)
                btnClearReply.visibility = View.VISIBLE
            } else {
                replyPreview.visibility = View.GONE
                btnClearReply.visibility = View.GONE
            }
        }
        updateReplyPreview()

        btnAttachReply.setOnClickListener {
            commentText = replyInput.text.toString()
            commentDialog?.dismiss()
            commentDialog = null
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_COMMENT_IMAGE_PICK)
        }

        btnClearReply.setOnClickListener {
            commentImageUri = null
            updateReplyPreview()
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.feedback_issue_details_title)
            .setView(view)
            .setPositiveButton(R.string.feedback_post_reply, null)
            .setNegativeButton(R.string.feedback_close, null)
            .create()

        dialog.setOnShowListener {
            val postBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            postBtn.isEnabled = githubClient.isConfigured
            postBtn.setOnClickListener {
                val text = replyInput.text.toString().trim()
                if (text.isEmpty()) {
                    replyInput.error = ctx.getString(R.string.feedback_field_required)
                    return@setOnClickListener
                }
                commentText = text
                replyProgress.visibility = View.VISIBLE
                postBtn.isEnabled = false

                scope.launch {
                    postComment(ctx, report, text, commentImageUri, replyProgress, postBtn, dialog)
                }
            }
        }
        commentDialog = dialog
        dialog.show()
    }

    private suspend fun postComment(
        appCtx: Context,
        report: BugReport,
        text: String,
        imageUri: Uri?,
        progressBar: ProgressBar,
        postBtn: Button,
        dialog: AlertDialog
    ) {
        var imageMarkdown = ""
        if (imageUri != null) {
            val uploadResult = runCatching {
                val base64 = withContext(Dispatchers.IO) { ImageHelper.uriToBase64(appCtx, imageUri) }
                githubClient.uploadAsset(base64).getOrThrow()
            }
            uploadResult.onSuccess { url ->
                if (url.isNotBlank()) imageMarkdown = "\n\n## Attachment\n\n![Screenshot]($url)"
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    postBtn.isEnabled = true
                    Toast.makeText(appCtx, appCtx.getString(R.string.feedback_image_upload_failed, e.message), Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        val body = "## Reply\n\n$text$imageMarkdown"
        val result = githubClient.postComment(report.number, body)

        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            result.onSuccess {
                dialog.dismiss()
                commentDialog = null
                commentText = ""
                commentImageUri = null
                Toast.makeText(appCtx, appCtx.getString(R.string.feedback_comment_posted), Toast.LENGTH_SHORT).show()
                openIssueDetails(report)
            }.onFailure { e ->
                postBtn.isEnabled = true
                Toast.makeText(appCtx, appCtx.getString(R.string.feedback_comment_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // endregion
}
