package com.arceusvault.keel.domain

import kotlinx.serialization.Serializable

/** How the phone reaches the server. Secure is the default; Direct is an explicit, warned opt-in. */
@Serializable
enum class ConnectionMode { SECURE, DIRECT }

/** How the server's TLS identity is trusted, decided on first connect and enforced after. */
@Serializable
sealed interface CertTrust {
    /** Chain validates against system CAs and the hostname matches. */
    @Serializable
    data object CaVerified : CertTrust

    /** Self-signed cert the user approved; its SPKI hash must match on every connect. */
    @Serializable
    data class Pinned(val spkiSha256: String, val certSha256: String) : CertTrust
}

@Serializable
data class ServerProfile(
    val host: String,
    val port: Int,
    val mode: ConnectionMode,
    val trust: CertTrust,
    val username: String,
    val apiKeyId: Long,
) {
    val url: String get() = if (port == 443) "wss://$host/api/current" else "wss://$host:$port/api/current"
    val displayAddress: String get() = if (port == 443) host else "$host:$port"
}
