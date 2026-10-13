package com.arceusvault.keel.core.network

import com.arceusvault.keel.domain.CertTrust
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class TofuTrustManagerTest {
    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
    private val caSigned = HeldCertificate.Builder().commonName("nas.example").signedBy(root).build()
    private val selfSigned = HeldCertificate.Builder().commonName("localhost").build()
    private val otherSelfSigned = HeldCertificate.Builder().commonName("localhost").build()

    // Stands in for the platform trust store: trusts only our test root.
    private val system: X509TrustManager = HandshakeCertificates.Builder()
        .addTrustedCertificate(root.certificate).build().trustManager

    private fun chain(vararg c: HeldCertificate) = c.map { it.certificate }.toTypedArray<X509Certificate>()
    private fun pin(c: HeldCertificate) = ServerCertificate.from(c.certificate).let { CertTrust.Pinned(it.spkiSha256, it.certSha256) }

    private fun rejection(tm: TofuTrustManager, vararg c: HeldCertificate): UntrustedCertificateException.Reason? = try {
        tm.checkServerTrusted(chain(*c), "ECDHE_ECDSA")
        null
    } catch (e: UntrustedCertificateException) {
        assertEquals(e, tm.lastRejection)
        e.reason
    }

    @Test
    fun `first connect accepts a CA chain`() {
        assertEquals(null, rejection(TofuTrustManager(system, null), caSigned, root))
    }

    @Test
    fun `first connect asks about a self-signed cert`() {
        assertEquals(UntrustedCertificateException.Reason.FIRST_USE, rejection(TofuTrustManager(system, null), selfSigned))
    }

    @Test
    fun `pinned key must match exactly`() {
        val tm = TofuTrustManager(system, pin(selfSigned))
        assertEquals(null, rejection(tm, selfSigned))
        assertEquals(UntrustedCertificateException.Reason.CHANGED, rejection(tm, otherSelfSigned))
        // Even a CA-valid cert doesn't override a pin.
        assertEquals(UntrustedCertificateException.Reason.CHANGED, rejection(tm, caSigned, root))
    }

    @Test
    fun `CA-verified server downgrading to self-signed is CHANGED, not a fresh prompt`() {
        assertEquals(UntrustedCertificateException.Reason.CHANGED, rejection(TofuTrustManager(system, CertTrust.CaVerified), selfSigned))
    }

    @Test
    fun `expired pinned cert is rejected`() {
        val expired = HeldCertificate.Builder().commonName("localhost").validityInterval(0, 1).build()
        val tm = TofuTrustManager(system, pin(expired))
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(chain(expired), "RSA") }
    }

    @Test
    fun `client certificates are never trusted`() {
        assertThrows(CertificateException::class.java) {
            TofuTrustManager(system, null).checkClientTrusted(chain(selfSigned), "RSA")
        }
    }
}

/** End to end over real TLS: pinning, the pin-aware hostname check, and JSON-RPC framing. */
class JsonRpcClientTest {
    // Issued to a name that doesn't match "localhost", like TrueNAS's default cert vs. your hostname.
    private val cert = HeldCertificate.Builder().commonName("truenas").addSubjectAlternativeName("truenas").build()
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun url() = server.url("/api/current").toString().replace("https://", "wss://")
    private fun pinned() = ServerCertificate.from(cert.certificate).let { CertTrust.Pinned(it.spkiSha256, it.certSha256) }

    private fun enqueueRpcServer() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"jsonrpc":"2.0","method":"collection_update","params":{"collection":"reporting.realtime","msg":"added","fields":{}}}""")
            }

            // Answer the client's close right away so MockWebServer.close() doesn't wait on a half-closed socket.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val req = json.parseToJsonElement(text).jsonObject
                val id = req["id"]!!.jsonPrimitive.content
                when (req["method"]!!.jsonPrimitive.content) {
                    "core.ping" -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":"pong"}""")
                    else -> webSocket.send(
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32001,"message":"Method call error",""" +
                            """"data":{"error":207,"errname":"ENOTAUTHENTICATED","reason":"[ENOTAUTHENTICATED] Not authenticated"}}}""",
                    )
                }
            }
        }).build())
    }

    @Test
    fun `unknown self-signed cert surfaces FIRST_USE with its fingerprint`() = runBlocking {
        val client = JsonRpcClient(trustedHttp(null, privateOnly = true), url(), json)
        try {
            client.connect()
            fail("should not connect")
        } catch (e: UntrustedCertificateException) {
            assertEquals(UntrustedCertificateException.Reason.FIRST_USE, e.reason)
            assertEquals(pinned().certSha256, e.certificate.certSha256)
        }
    }

    @Test
    fun `pinned cert connects despite hostname mismatch and calls round-trip`() = runBlocking {
        enqueueRpcServer()
        val client = JsonRpcClient(trustedHttp(pinned(), privateOnly = true), url(), json)
        client.connect()
        assertEquals(JsonPrimitive("pong"), client.call("core.ping"))
        val err = runCatching { client.call("system.info") }.exceptionOrNull()
        assertTrue(err is RpcException && err.errname == "ENOTAUTHENTICATED")
        client.close()
    }

    @Test
    fun `a different cert with the same name is CHANGED`() = runBlocking {
        val other = HeldCertificate.Builder().commonName("truenas").build()
        val stale = ServerCertificate.from(other.certificate).let { CertTrust.Pinned(it.spkiSha256, it.certSha256) }
        val client = JsonRpcClient(trustedHttp(stale, privateOnly = true), url(), json)
        val e = runCatching { client.connect() }.exceptionOrNull()
        assertTrue("got $e", e is UntrustedCertificateException && e.reason == UntrustedCertificateException.Reason.CHANGED)
    }

    @Test
    fun `CA-verified profile still enforces hostname`() = runBlocking {
        // Self-signed here, so it fails as CHANGED before hostname is even considered.
        val client = JsonRpcClient(trustedHttp(CertTrust.CaVerified, privateOnly = true), url(), json)
        val e = runCatching { client.connect() }.exceptionOrNull()
        assertTrue("got $e", e is UntrustedCertificateException)
    }
}
