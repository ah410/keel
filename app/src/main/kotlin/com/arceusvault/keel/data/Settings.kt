package com.arceusvault.keel.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Non-secret preferences only. Anything sensitive goes in the Vault. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _lockTimeout = MutableStateFlow(prefs.getInt(KEY_LOCK, 60))
    /** Seconds in the background before the app locks again. 0 locks immediately. */
    val lockTimeoutSeconds: StateFlow<Int> = _lockTimeout

    private val _theme = MutableStateFlow(prefs.getString(KEY_THEME, SYSTEM_THEME) ?: SYSTEM_THEME)
    /** A palette name, or [SYSTEM_THEME] to follow the phone's dark mode setting. */
    val theme: StateFlow<String> = _theme

    private val _activeServer = MutableStateFlow(prefs.getInt(KEY_ACTIVE, 0))
    /** Index into the vault's server list. Not secret: it's just a position. */
    val activeServer: StateFlow<Int> = _activeServer

    fun setActiveServer(index: Int) {
        prefs.edit { putInt(KEY_ACTIVE, index) }
        _activeServer.value = index
    }

    fun setLockTimeout(seconds: Int) {
        prefs.edit { putInt(KEY_LOCK, seconds) }
        _lockTimeout.value = seconds
    }

    fun setTheme(name: String) {
        prefs.edit { putString(KEY_THEME, name) }
        _theme.value = name
    }

    companion object {
        const val SYSTEM_THEME = "system"
        private const val KEY_LOCK = "lock_timeout_seconds"
        private const val KEY_THEME = "theme"
        private const val KEY_ACTIVE = "active_server"
    }
}
