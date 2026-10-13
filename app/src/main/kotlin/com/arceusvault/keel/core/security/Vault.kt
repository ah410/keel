package com.arceusvault.keel.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.core.util.AtomicFile
import com.arceusvault.keel.domain.ServerProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Everything secret the app persists. Only the SCRAM-derived keys are stored, never the raw API key:
 * they can complete a SCRAM login but cannot be used for the plaintext API key mechanism.
 */
@Serializable
data class VaultContents(
    val profile: ServerProfile,
    val scramSalt: String,
    val scramIterations: Int,
    val clientKey: String,
    val serverKey: String,
)

/** Every server the app knows, encrypted together so one unlock opens them all. */
@Serializable
data class VaultData(val servers: List<VaultContents>)

/** Supplies the AES key; split out so the envelope format is testable on the JVM. */
fun interface KeyProvider {
    fun key(): SecretKey
}

/** AES-256-GCM envelope (`v1:` + base64(iv || ciphertext)) stored in noBackupFilesDir. */
class Vault<T>(
    private val file: File,
    private val keys: KeyProvider,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
) {
    private val atomic = AtomicFile(file)

    fun exists(): Boolean = file.exists()

    /** Throws [android.security.keystore.UserNotAuthenticatedException] if the user hasn't just unlocked. */
    fun read(): T? {
        if (!file.exists()) return null
        val text = atomic.readFully().decodeToString()
        require(text.startsWith(PREFIX)) { "Unknown vault format" }
        val blob = Base64.getDecoder().decode(text.removePrefix(PREFIX))
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(128, blob, 0, IV_LEN))
        val plain = cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        try {
            return decode(plain.decodeToString())
        } finally {
            plain.fill(0)
        }
    }

    fun write(contents: T) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        val plain = encode(contents).toByteArray()
        val ct = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val out = (PREFIX + Base64.getEncoder().encodeToString(cipher.iv + ct)).toByteArray()
        val stream = atomic.startWrite()
        try {
            stream.write(out)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun delete() = atomic.delete()

    companion object {
        private const val PREFIX = "v1:"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12

        /** One server's key material, as used by the alert-notification vault. */
        fun single(file: File, keys: KeyProvider, json: Json) = Vault(
            file, keys,
            { json.encodeToString(VaultContents.serializer(), it) },
            { json.decodeFromString(VaultContents.serializer(), it) },
        )

        /** The main vault. Files from before multi-server support hold one bare entry and still open. */
        fun servers(file: File, keys: KeyProvider, json: Json) = Vault(
            file, keys,
            { json.encodeToString(VaultData.serializer(), it) },
            { text ->
                runCatching { json.decodeFromString(VaultData.serializer(), text) }
                    .getOrElse { VaultData(listOf(json.decodeFromString(VaultContents.serializer(), text))) }
            },
        )
    }
}

/**
 * Non-exportable Keystore key, StrongBox-backed when the device has a secure element. When [authBound]
 * it is usable only for [AUTH_WINDOW_SECONDS] after a strong biometric or device-credential unlock.
 * The unbound variant exists only for the opt-in alert-notification key, which can read alerts and nothing else.
 */
class KeystoreKeyProvider(
    private val context: Context,
    private val alias: String = ALIAS,
    private val authBound: Boolean = true,
) : KeyProvider {
    override fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generate(strongBox)
        } catch (e: StrongBoxUnavailableException) {
            generate(false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .apply {
                if (authBound) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(
                        AUTH_WINDOW_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    setUnlockedDeviceRequired(true)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    fun deleteKey() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "vault-key-v1"
        const val NOTIFY_ALIAS = "notify-key-v1"
        const val AUTH_WINDOW_SECONDS = 30
    }
}
