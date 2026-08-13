package com.charles.messenger.feature.settings.feedback

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Talks to the cloudflare-worker/ feedback relay, not api.github.com directly — the
 * Worker holds the GitHub token as a server-side secret and hardcodes this app's own
 * repo, so no owner/repo/credential ever needs to travel through this app. Previously
 * embedded BuildConfig.GITHUB_API_TOKEN client-side as a Bearer header, which shipped a
 * real repo-write PAT in every release build (extractable from the APK). See
 * cloudflare-worker/src/index.ts.
 */
class GithubApiClient {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://textpilot-github-feedback.charles-h-hartmann1.workers.dev"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .addHeader("User-Agent", "TextPilot-Android/1.0")
            chain.proceed(builder.build())
        }
        .build()

    // Always true now — the relay is a fixed public Worker URL, not per-install config.
    val isConfigured: Boolean = true

    suspend fun createIssue(title: String, body: String): Result<GithubIssue> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBody = moshi.adapter(CreateIssueRequest::class.java)
                .toJson(CreateIssueRequest(title, body))
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/issue")
                .post(reqBody)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("GitHub error ${response.code}: $responseBody")
            moshi.adapter(GithubIssue::class.java).fromJson(responseBody)
                ?: throw IOException("Failed to parse GitHub issue response")
        }
    }

    suspend fun getIssue(number: Int): Result<GithubIssue> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/issue/$number")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("GitHub error ${response.code}")
            moshi.adapter(GithubIssue::class.java).fromJson(responseBody)
                ?: throw IOException("Failed to parse issue")
        }
    }

    suspend fun getComments(number: Int): Result<List<GithubComment>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/issue/$number/comments")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("GitHub error ${response.code}")
            val type = Types.newParameterizedType(List::class.java, GithubComment::class.java)
            moshi.adapter<List<GithubComment>>(type).fromJson(responseBody) ?: emptyList()
        }
    }

    suspend fun postComment(number: Int, body: String): Result<GithubComment> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBody = moshi.adapter(PostCommentRequest::class.java)
                .toJson(PostCommentRequest(body))
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/issue/$number/comments")
                .post(reqBody)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("GitHub error ${response.code}: $responseBody")
            moshi.adapter(GithubComment::class.java).fromJson(responseBody)
                ?: throw IOException("Failed to parse comment")
        }
    }

    suspend fun uploadAsset(base64Content: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val random = (Math.random() * 99999).toInt()
            val fileName = "feedback-$timestamp-$random.png"
            val reqBody = moshi.adapter(UploadAssetRequest::class.java)
                .toJson(UploadAssetRequest(filename = fileName, contentBase64 = base64Content))
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/upload-image")
                .post(reqBody)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("Upload error ${response.code}: $responseBody")
            val uploadResponse = moshi.adapter(UploadAssetResponse::class.java).fromJson(responseBody)
            uploadResponse?.content?.downloadUrl ?: throw IOException("No download URL in upload response")
        }
    }
}
