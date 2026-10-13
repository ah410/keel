package com.arceusvault.keel.core.network

import android.annotation.SuppressLint
import com.arceusvault.keel.domain.CertTrust
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** What the user is shown before trusting (or re-trusting) a certificate. */
data class ServerCertificate(
    val spkiSha256: String,
    val certSha256: String,
    val subject: String,
    val issuer: String,
    val notAfter: java.util.Date,
) {
    companion object {
        fun from(cert: X509Certificate) = ServerCertificate(
            spkiSha256 = spkiSha256(cert),
            certSha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) },
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notAfter = cert.notAfter,
        )

        fun spkiSha256(cert: X509Certificate): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))
    }
}

/** Thrown during the TLS handshake when the user has to make a trust decision. */
class UntrustedCertificateException(
    val certificate: ServerCertificate,
    val reason: Reason,
) : CertificateException("Untrusted server certificate ($reason)") {
    enum class Reason {
        /** Not CA-signed and never seen before: ask the user to verify the fingerprint. */
        FIRST_USE,
        /** Differs from what was trusted before. Possible interception: hard fail. */
        CHANGED,
    }
}

/**
 * Trust policy for one profile. With no prior decision, a CA-valid chain is accepted and anything else
 * is surfaced as FIRST_USE. After that the decision is sticky: a pinned key must match exactly, and a
 * CA-verified server that suddenly presents a non-CA cert is treated as CHANGED, not as a fresh prompt.
 */
@SuppressLint("CustomX509TrustManager") // Delegates to the platform validator; only adds pinning on top.
class TofuTrustManager(
    private val system: X509TrustManager,
    private val trust: CertTrust?,
) : X509TrustManager {

    /** Last rejection, kept because Android's TLS stack may not preserve our exception as the cause. */
    @Volatile
    var lastRejection: UntrustedCertificateException? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            check(chain, authType)
        } catch (e: UntrustedCertificateException) {
            lastRejection = e
            throw e
        }
    }

    private fun check(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
        val leaf = chain[0]
        when (trust) {
            is CertTrust.Pinned -> {
                if (ServerCertificate.spkiSha256(leaf) != trust.spkiSha256) {
                    throw UntrustedCertificateException(ServerCertificate.from(leaf), UntrustedCertificateException.Reason.CHANGED)
                }
                // The pin replaces the CA chain check, not the expiry check.
                leaf.checkValidity()
            }
            CertTrust.CaVerified -> try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                throw UntrustedCertificateException(ServerCertificate.from(leaf), UntrustedCertificateException.Reason.CHANGED)
            }
            null -> try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                throw UntrustedCertificateException(ServerCertificate.from(leaf), UntrustedCertificateException.Reason.FIRST_USE)
            }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        fun systemDefault(): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/**
 * For a pinned self-signed cert the pin is the identity (TrueNAS's default cert is usually issued to
 * "localhost"), so hostname matching is skipped. Everything else gets normal hostname verification.
 */
class PinAwareHostnameVerifier(
    private val trust: CertTrust?,
    private val fallback: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (trust is CertTrust.Pinned) {
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
            return ServerCertificate.spkiSha256(leaf) == trust.spkiSha256
        }
        return fallback.verify(hostname, session)
    }
}
