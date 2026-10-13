package com.arceusvault.keel.core.network

import com.arceusvault.keel.domain.CertTrust
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext

/** A JSON-RPC error returned by the TrueNAS middleware. */
class RpcException(val code: Int, val errname: String?, val reason: String) : Exception(reason)

class ConnectionClosedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An OkHttp client whose TLS trust follows the profile's [CertTrust] decision. */
class TrustedHttp(val client: OkHttpClient, val trustManager: TofuTrustManager)

fun trustedHttp(trust: CertTrust?, privateOnly: Boolean): TrustedHttp {
    val tm = TofuTrustManager(TofuTrustManager.systemDefault(), trust)
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    val client = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, tm)
        .hostnameVerifier(PinAwareHostnameVerifier(trust))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        // No cache, redirects or proxies: one TLS socket straight to the configured host.
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .apply { if (privateOnly) dns(PrivateNetworkDns()) }
        .build()
    return TrustedHttp(client, tm)
}

/** What the API layer needs from a connection; a fake implements it in tests. */
interface RpcChannel {
    val events: SharedFlow<JsonObject>
    val unsubscribed: SharedFlow<JsonObject>
    suspend fun call(method: String, params: JsonArray = JsonArray(emptyList()), timeoutMs: Long = 30_000): JsonElement
}

/** JSON-RPC 2.0 over one WebSocket, as served by TrueNAS at /api/current. */
class JsonRpcClient(
    private val http: TrustedHttp,
    private val url: String,
    private val json: Json,
) : RpcChannel {
    enum class State { CONNECTING, OPEN, CLOSED }

    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val opened = CompletableDeferred<Unit>()
    private var socket: WebSocket? = null

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state

    // Large enough for a 500-line log tail arriving in one burst.
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** `collection_update` notification params, e.g. for reporting.realtime. */
    override val events: SharedFlow<JsonObject> = _events

    private val _unsubscribed = MutableSharedFlow<JsonObject>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** `notify_unsubscribed` params: the server ended a subscription, usually for lack of a role. */
    override val unsubscribed: SharedFlow<JsonObject> = _unsubscribed

    suspend fun connect(timeoutMs: Long = 20_000) {
        socket = http.client.newWebSocket(Request.Builder().url(url).build(), listener)
        try {
            withTimeout(timeoutMs) { opened.await() }
        } catch (e: Throwable) {
            close()
            throw http.trustManager.lastRejection ?: unwrapTls(e)
        }
    }

    override suspend fun call(method: String, params: JsonArray, timeoutMs: Long): JsonElement {
        val ws = socket ?: throw ConnectionClosedException("Not connected")
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        if (!ws.send(body.toString())) {
            pending.remove(id)
            throw ConnectionClosedException("Connection closed")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        socket?.close(1000, null)
        fail(ConnectionClosedException("Connection closed"))
    }

    private fun fail(t: Throwable) {
        _state.value = State.CLOSED
        opened.completeExceptionally(t)
        pending.values.forEach { it.completeExceptionally(t) }
        pending.clear()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = State.OPEN
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val id = msg["id"]?.jsonPrimitive?.longOrNull
            if (id == null) {
                val params = msg["params"] as? JsonObject ?: return
                when (msg["method"]?.jsonPrimitive?.contentOrNull) {
                    "collection_update" -> _events.tryEmit(params)
                    "notify_unsubscribed" -> _unsubscribed.tryEmit(params)
                }
                return
            }
            val deferred = pending[id] ?: return
            val error = msg["error"] as? JsonObject
            if (error != null) deferred.completeExceptionally(parseError(error))
            else deferred.complete(msg["result"] ?: JsonPrimitive(null as String?))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            fail(ConnectionClosedException("Server closed the connection ($code)"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(http.trustManager.lastRejection ?: unwrapTls(t))
        }
    }

    private fun parseError(error: JsonObject): RpcException {
        val data = error["data"] as? JsonObject
        val reason = data?.get("reason")?.jsonPrimitive?.contentOrNull
            ?: error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
        return RpcException(
            code = error["code"]?.jsonPrimitive?.intOrNull ?: 0,
            errname = data?.get("errname")?.jsonPrimitive?.contentOrNull,
            reason = reason,
        )
    }

    companion object {
        /** TLS failures arrive wrapped in SSLHandshakeException; surface the trust decision underneath. */
        fun unwrapTls(t: Throwable): Throwable {
            var c: Throwable? = t
            while (c != null) {
                if (c is UntrustedCertificateException) return c
                c = c.cause
            }
            // Conscrypt sometimes drops the cause chain; report a generic cert failure rather than a raw SSL error.
            return if (t.cause is CertificateException) t.cause!! else t
        }
    }
}
