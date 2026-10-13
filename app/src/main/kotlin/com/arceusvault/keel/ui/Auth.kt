package com.arceusvault.keel.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Shows the device-auth prompt; true on success. Provided by MainActivity. */
fun interface Authenticator {
    suspend fun authenticate(title: String): Boolean
}

val LocalAuthenticator = staticCompositionLocalOf<Authenticator> { error("No Authenticator provided") }
