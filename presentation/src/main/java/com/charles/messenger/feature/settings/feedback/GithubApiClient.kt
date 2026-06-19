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

class GithubApiClient(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val assetsDir: String
) {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .addHeader("User-Agent", "TextPilot-Android/1.0")
            if (token.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer $token")
            }
            chain.proceed(builder.build())
        }
        .build()

    val isConfigured: Boolean
        get() = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    suspend fun createIssue(title: String, body: String): Result<GithubIssue> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBody = moshi.adapter(CreateIssueRequest::class.java)
                .toJson(CreateIssueRequest(title, body))
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/issues")
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
                .url("https://api.github.com/repos/$owner/$repo/issues/$number")
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
                .url("https://api.github.com/repos/$owner/$repo/issues/$number/comments")
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
                .url("https://api.github.com/repos/$owner/$repo/issues/$number/comments")
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
                .toJson(UploadAssetRequest("Add feedback asset $fileName", base64Content))
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/$assetsDir/$fileName")
                .put(reqBody)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("Upload error ${response.code}: $responseBody")
            val uploadResponse = moshi.adapter(UploadAssetResponse::class.java).fromJson(responseBody)
            uploadResponse?.content?.downloadUrl ?: throw IOException("No download URL in upload response")
        }
    }
}
