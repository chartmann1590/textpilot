package com.charles.messenger.feature.settings.feedback

import android.content.Context
import android.net.Uri
import android.util.Base64

object ImageHelper {
    fun uriToBase64(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes()
        } ?: throw IllegalStateException("Could not read image from $uri")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
