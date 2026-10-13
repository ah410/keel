package com.arceusvault.keel

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arceusvault.keel.data.Settings
import androidx.fragment.app.FragmentActivity
import com.arceusvault.keel.core.security.DeviceAuth
import com.arceusvault.keel.ui.AppRoot
import com.arceusvault.keel.ui.Authenticator
import com.arceusvault.keel.ui.LocalAuthenticator
import com.arceusvault.keel.ui.theme.Palette
import com.arceusvault.keel.ui.theme.KeelTheme

// FragmentActivity because BiometricPrompt hosts itself in a fragment.
class MainActivity : FragmentActivity() {
    private val container get() = (application as KeelApp).container
    private var backgroundedAt = 0L
    private var authInProgress = false

    private val authenticator = Authenticator { title ->
        authInProgress = true
        try {
            DeviceAuth.authenticate(this, title) == DeviceAuth.Result.Success
        } finally {
            authInProgress = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording, or recent-apps thumbnails of server data.
        if (!BuildConfig.ALLOW_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            val choice by container.settings.theme.collectAsState()
            val palette = Palette.resolve(choice, isSystemInDarkTheme())
            // The top bar matches the background, so system bar icons follow the palette.
            LaunchedEffect(palette) {
                val bars = if (palette.dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            KeelTheme(palette) {
                Surface(color = palette.bg1) {
                    CompositionLocalProvider(LocalAuthenticator provides authenticator) {
                        AppRoot(container)
                        // The top bar color behind the status bar on every screen.
                        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(palette.topbar))
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (backgroundedAt != 0L) {
            val away = SystemClock.elapsedRealtime() - backgroundedAt
            if (away >= container.settings.lockTimeoutSeconds.value * 1000L) container.session.lock()
            backgroundedAt = 0L
        }
    }

    override fun onStop() {
        super.onStop()
        // The PIN/password screen is a separate activity, so it stops us; that isn't leaving the app.
        if (isChangingConfigurations || authInProgress) return
        if (container.settings.lockTimeoutSeconds.value == 0) container.session.lock()
        else backgroundedAt = SystemClock.elapsedRealtime()
    }
}
