package com.arceusvault.keel.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.arceusvault.keel.data.Roles
import com.arceusvault.keel.data.TrueNasApi
import com.arceusvault.keel.domain.ServerProfile

/** Everything a screen needs about the connected server, provided once by the main scaffold. */
class ServerContext(
    val api: TrueNasApi,
    val roles: Roles,
    val profile: ServerProfile,
    val operations: Operations,
    /** True when running against the built-in demo server. */
    val demo: Boolean = false,
)

val LocalServer = staticCompositionLocalOf<ServerContext> { error("No server connected") }
