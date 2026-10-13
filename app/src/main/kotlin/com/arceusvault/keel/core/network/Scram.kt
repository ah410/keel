package com.arceusvault.keel.core.network

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SCRAM-SHA-512 (RFC 5802) client for TrueNAS API keys. The raw key never crosses the wire, and
 * the server's final signature proves it also holds the key, so a fake server can't complete login.
 */
object Scram {
    private const val HMAC = "HmacSHA512"
    const val NONCE_BYTES = 32
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    /** The PBKDF2 output split into the two keys SCRAM actually uses. Password-equivalent: keep secret. */
    class DerivedKeys(val salt: String, val iterations: Int, val clientKey: ByteArray, val serverKey: ByteArray)

    /** State carried between the client-first and client-final messages. */
    class Session internal constructor(val clientFirstBare: String, val clientNonce: String) {
        val clientFirst: String get() = "n,,$clientFirstBare"
    }

    class ServerFirst(val raw: String, val nonce: String, val salt: String, val iterations: Int)

    class ClientFinal(val message: String, val expectedServerSignature: ByteArray)

    class ScramException(message: String) : Exception(message)

    fun start(username: String, apiKeyId: Long, nonce: String = newNonce()): Session {
        val bare = "n=${saslName("$username:$apiKeyId")},r=$nonce"
        return Session(bare, nonce)
    }

    fun parseServerFirst(session: Session, serverFirst: String): ServerFirst {
        val attrs = parseAttrs(serverFirst)
        attrs["e"]?.let { throw ScramException("Server rejected login: $it") }
        val nonce = attrs["r"] ?: throw ScramException("Server response missing nonce")
        val salt = attrs["s"] ?: throw ScramException("Server response missing salt")
        val iterations = attrs["i"]?.toIntOrNull() ?: throw ScramException("Server response missing iterations")
        // The combined nonce must extend ours, otherwise this is a replayed or forged exchange.
        if (!extendsNonce(nonce, session.clientNonce)) throw ScramException("Server nonce does not match")
        // TrueNAS allows 50k..5M; refuse anything weaker or absurd enough to hang the phone.
        if (iterations !in 50_000..5_000_000) throw ScramException("Unexpected iteration count $iterations")
        return ServerFirst(serverFirst, nonce, salt, iterations)
    }

    /** PBKDF2-SHA512 over the secret half of an `id-secret` API key. Slow on purpose: run off the main thread. */
    fun deriveKeys(secret: CharArray, salt: String, iterations: Int): DerivedKeys {
        val spec = PBEKeySpec(secret, b64d.decode(salt), iterations, 512)
        val salted = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            return DerivedKeys(salt, iterations, hmac(salted, "Client Key"), hmac(salted, "Server Key"))
        } finally {
            salted.fill(0)
        }
    }

    fun clientFinal(session: Session, serverFirst: ServerFirst, keys: DerivedKeys): ClientFinal {
        if (keys.salt != serverFirst.salt || keys.iterations != serverFirst.iterations) {
            throw ScramException("Stored key material no longer matches the server (was the API key regenerated?)")
        }
        val withoutProof = "c=biws,r=${serverFirst.nonce}"
        val authMessage = "${session.clientFirstBare},${serverFirst.raw},$withoutProof".toByteArray()
        val storedKey = MessageDigest.getInstance("SHA-512").digest(keys.clientKey)
        val clientSignature = hmac(storedKey, authMessage)
        val proof = ByteArray(keys.clientKey.size) { (keys.clientKey[it].toInt() xor clientSignature[it].toInt()).toByte() }
        val serverSignature = hmac(keys.serverKey, authMessage)
        return ClientFinal("$withoutProof,p=${b64.encodeToString(proof)}", serverSignature)
    }

    /** Mutual authentication: throws unless the server proved it knows the key too. */
    fun verifyServerFinal(clientFinal: ClientFinal, serverFinal: String) {
        val attrs = parseAttrs(serverFinal)
        attrs["e"]?.let { throw ScramException("Server rejected login: $it") }
        val v = attrs["v"] ?: throw ScramException("Server did not send a signature")
        val got = try { b64d.decode(v) } catch (_: IllegalArgumentException) { throw ScramException("Malformed server signature") }
        if (!MessageDigest.isEqual(got, clientFinal.expectedServerSignature)) {
            throw ScramException("Server signature invalid: this is not the server that holds your key")
        }
    }

    // truenas_scram rejects any client nonce that isn't exactly 32 random bytes.
    fun newNonce(): String = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }.let { b64.encodeToString(it) }

    // RFC 5802 appends text, but truenas_scram re-encodes base64(clientBytes || serverBytes), so compare bytes too.
    private fun extendsNonce(combined: String, client: String): Boolean {
        if (combined.length > client.length && combined.startsWith(client)) return true
        val c = runCatching { b64d.decode(client) }.getOrNull() ?: return false
        val s = runCatching { b64d.decode(combined) }.getOrNull() ?: return false
        return s.size > c.size && MessageDigest.isEqual(s.copyOf(c.size), c)
    }

    private fun saslName(s: String) = s.replace("=", "=3D").replace(",", "=2C")

    private fun hmac(key: ByteArray, data: String) = hmac(key, data.toByteArray())

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run { init(SecretKeySpec(key, HMAC)); doFinal(data) }

    private fun parseAttrs(msg: String): Map<String, String> = msg.split(',').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i == 1) part.substring(0, 1) to part.substring(2) else null
    }.toMap()
}

/** A TrueNAS raw API key is `<numeric id>-<64 char secret>`. */
class RawApiKey private constructor(val id: Long, val secret: CharArray) {
    companion object {
        fun parse(raw: String): RawApiKey? {
            val trimmed = raw.trim()
            val dash = trimmed.indexOf('-')
            if (dash <= 0) return null
            val id = trimmed.substring(0, dash).toLongOrNull() ?: return null
            val secret = trimmed.substring(dash + 1)
            if (secret.length != 64 || !secret.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return null
            return RawApiKey(id, secret.toCharArray())
        }
    }

    fun wipe() = secret.fill('\u0000')
}
