package com.tazihad

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context

object NagordolaSettingsManager {
    private const val SETTINGS_PREF = "dhakaflix_settings"
    private const val API_KEY = "tmdb_api_key"
    private const val TMDB_ENABLED = "tmdb_enabled"
    private const val BASE_URL_KEY = "nagordola_base_url"
    private const val TAG = "NagordolaSettings"

    const val DEFAULT_BASE_URL = "https://cdn.nagordola.com.bd"

    private var cachedApiKey: String? = null
    private var cachedTmdbEnabled: Boolean? = null
    private var cachedBaseUrl: String? = null

    private fun getPrefs(): SharedPreferences? {
        return try {
            context?.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SharedPreferences: ${e.message}")
            null
        }
    }

    fun getBaseUrl(): String {
        if (!cachedBaseUrl.isNullOrEmpty()) {
            return cachedBaseUrl!!
        }

        return try {
            val url = getPrefs()?.getString(BASE_URL_KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
            cachedBaseUrl = url.trimEnd('/')
            cachedBaseUrl!!
        } catch (e: Exception) {
            Log.e(TAG, "Error getting base URL: ${e.message}")
            DEFAULT_BASE_URL
        }
    }

    fun setBaseUrl(url: String): Boolean {
        return try {
            val cleanUrl = (if (url.isBlank()) DEFAULT_BASE_URL else url).trimEnd('/')
            val prefs = getPrefs() ?: return false
            val success = prefs.edit().putString(BASE_URL_KEY, cleanUrl).commit()
            if (success) {
                cachedBaseUrl = cleanUrl
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save base URL: ${e.message}")
            false
        }
    }

    fun getApiKey(): String? {
        if (!cachedApiKey.isNullOrEmpty()) {
            return cachedApiKey
        }

        return try {
            val key = getPrefs()?.getString(API_KEY, null)
            if (!key.isNullOrEmpty()) {
                cachedApiKey = key
            }
            key
        } catch (e: Exception) {
            Log.e(TAG, "Error getting API key: ${e.message}")
            null
        }
    }

    fun setApiKey(apiKey: String): Boolean {
        return try {
            if (apiKey.isEmpty()) {
                return false
            }

            val prefs = getPrefs() ?: return false

            val success = prefs.edit()
                .putString(API_KEY, apiKey)
                .commit()

            if (success) {
                cachedApiKey = apiKey
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key: ${e.message}")
            false
        }
    }

    fun clearApiKey() {
        try {
            getPrefs()?.edit()?.remove(API_KEY)?.apply()
            cachedApiKey = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear API key: ${e.message}")
        }
    }

    fun isTmdbEnabled(): Boolean {
        if (cachedTmdbEnabled != null) {
            return cachedTmdbEnabled!!
        }

        return try {
            val enabled = getPrefs()?.getBoolean(TMDB_ENABLED, true) ?: true
            cachedTmdbEnabled = enabled
            enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error getting TMDB enabled state: ${e.message}")
            true
        }
    }

    fun setTmdbEnabled(enabled: Boolean): Boolean {
        return try {
            val prefs = getPrefs() ?: return false

            val success = prefs.edit()
                .putBoolean(TMDB_ENABLED, enabled)
                .commit()

            if (success) {
                cachedTmdbEnabled = enabled
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save TMDB enabled state: ${e.message}")
            false
        }
    }
}
