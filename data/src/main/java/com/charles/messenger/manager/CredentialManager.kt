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
package com.charles.messenger.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages encrypted storage of sensitive credentials using Android Keystore
 */
@Singleton
class CredentialManager @Inject constructor(
    private val context: Context
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: EncryptedSharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "web_sync_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    /**
     * Save web sync password securely
     */
    fun savePassword(password: String) {
        try {
            encryptedPrefs.edit()
                .putString(KEY_PASSWORD, password)
                .apply()
            Timber.d("Password saved securely")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save password")
        }
    }

    /**
     * Retrieve web sync password
     */
    fun getPassword(): String? {
        return try {
            encryptedPrefs.getString(KEY_PASSWORD, null)
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve password")
            null
        }
    }

    /**
     * Clear all stored credentials
     */
    fun clearCredentials() {
        try {
            encryptedPrefs.edit().clear().apply()
            Timber.d("Credentials cleared")
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear credentials")
        }
    }

    companion object {
        private const val KEY_PASSWORD = "password"
    }
}
