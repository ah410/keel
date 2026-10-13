package com.arceusvault.keel.data

import com.arceusvault.keel.core.network.ConnectionClosedException
import com.arceusvault.keel.core.network.JsonRpcClient
import com.arceusvault.keel.core.network.PublicAddressException
import com.arceusvault.keel.core.network.Scram
import com.arceusvault.keel.core.network.ServerCertificate
import com.arceusvault.keel.core.security.Vault
import com.arceusvault.keel.core.security.VaultContents
import com.arceusvault.keel.core.security.VaultData
import com.arceusvault.keel.domain.CertTrust
import com.arceusvault.keel.domain.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64

/**
 * Owns the one live connection. Secrets exist in memory only between unlock and lock; nothing about
 * an authenticated session survives the app being locked, killed or the phone restarting.
 */
class SessionManager(
    private val vault: Vault<VaultData>,
    private val connector: Connector,
    private val json: Json,
    private val scope: CoroutineScope,
    private val settings: Settings,
) {
    sealed interface State {
        data object NotConfigured : State
        data object Locked : State
        data class Connecting(val profile: ServerProfile, val attempt: Int) : State
        data class Connected(val profile: ServerProfile, val api: TrueNasApi, val roles: Roles) : State {
            val fullAdmin: Boolean get() = roles.fullAdmin
        }
        data class Failed(val profile: ServerProfile, val error: Throwable) : State

        /** Running setup for an extra server while unlocked; [id] keys a fresh setup screen. */
        data class AddingServer(val id: Long) : State
    }

    private val _state = MutableStateFlow<State>(if (vault.exists()) State.Locked else State.NotConfigured)
    val state: StateFlow<State> = _state

    private var data: VaultData? = null
    private var rpc: JsonRpcClient? = null
    private var job: Job? = null
    private var connectionScope: CoroutineScope? = null

    private val _servers = MutableStateFlow<List<ServerProfile>>(emptyList())
    /** Profiles of every stored server while unlocked; empty while locked. */
    val servers: StateFlow<List<ServerProfile>> = _servers

    /** Told when a stored profile changes (re-trust) or is removed, so the alert watch can follow. */
    var onProfileChanged: (ServerProfile) -> Unit = {}
    var onProfileRemoved: (ServerProfile) -> Unit = {}

    private val contents: VaultContents?
        get() = data?.servers?.let { it.getOrNull(settings.activeServer.value.coerceIn(0, (it.size - 1).coerceAtLeast(0))) }

    /** Call right after a successful device-auth prompt; reading the vault needs that fresh auth. */
    fun unlock() {
        val d = vault.read()?.takeIf { it.servers.isNotEmpty() } ?: run { _state.value = State.NotConfigured; return }
        setData(d)
        connect()
    }

    /** Setup stored [d] (with the new server active) and already has a live, logged-in connection. */
    fun adopt(d: VaultData, active: Int, result: LoginResult) {
        settings.setActiveServer(active)
        setData(d)
        job?.cancel()
        closeConnection()
        attach(d.servers[active].profile, result)
    }

    /** Opens setup for another server. [cancelAdding] returns to the current one. */
    fun startAdding() {
        job?.cancel()
        closeConnection()
        _state.value = State.AddingServer(System.nanoTime())
    }

    fun cancelAdding() {
        if (data == null) lock() else connect()
    }

    fun switchTo(index: Int) {
        val d = data ?: return
        if (index !in d.servers.indices) return
        settings.setActiveServer(index)
        connect()
    }

    fun connect() {
        val c = contents ?: return
        job?.cancel()
        closeConnection()
        job = scope.launch {
            var attempt = 0
            while (true) {
                attempt++
                _state.value = State.Connecting(c.profile, attempt)
                try {
                    val client = connector.open(c.profile.url, c.profile.trust, c.profile.mode)
                    val result = connector.login(client, c.profile.username, c.profile.apiKeyId, KeyMaterial.Derived(c.derivedKeys()))
                    attach(c.profile, result)
                    return@launch
                } catch (e: Throwable) {
                    // Trust and auth failures need the user; only plain network errors are retried.
                    if (!isTransient(e) || attempt >= MAX_ATTEMPTS) {
                        _state.value = State.Failed(c.profile, e)
                        return@launch
                    }
                    delay(1_000L shl attempt.coerceAtMost(4))
                }
            }
        }
    }

    private fun attach(profile: ServerProfile, result: LoginResult) {
        rpc = result.rpc
        // Job watchers and other per-connection work die with the connection.
        val conn = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        connectionScope = conn
        _state.value = State.Connected(profile, TrueNasApi(result.rpc, json, JobTracker(result.rpc, conn)), result.roles)
        job = scope.launch {
            result.rpc.state.first { it == JsonRpcClient.State.CLOSED }
            if (_state.value is State.Connected && data != null) connect()
        }
    }

    /** Re-pin after the user reviewed a changed certificate. Needs a fresh device unlock (vault write). */
    fun retrust(cert: ServerCertificate) {
        val d = data ?: return
        val index = activeIndex()
        val c = d.servers[index]
        val updated = c.copy(profile = c.profile.copy(trust = CertTrust.Pinned(cert.spkiSha256, cert.certSha256)))
        val next = VaultData(d.servers.toMutableList().also { it[index] = updated })
        vault.write(next)
        setData(next)
        onProfileChanged(updated.profile)
        connect()
    }

    private fun closeConnection() {
        connectionScope?.cancel()
        connectionScope = null
        rpc?.close()
        rpc = null
    }

    fun lock() {
        job?.cancel()
        closeConnection()
        data = null
        _servers.value = emptyList()
        _state.value = if (vault.exists()) State.Locked else State.NotConfigured
    }

    /**
     * Removes the active server. With others left this rewrites the vault, which needs a fresh device
     * unlock; with none left the file is simply deleted.
     */
    fun forget() {
        val d = data
        val index = activeIndex()
        val removed = d?.servers?.getOrNull(index)
        val rest = d?.servers.orEmpty().filterIndexed { i, _ -> i != index }
        if (rest.isEmpty()) {
            lock()
            vault.delete()
            settings.setActiveServer(0)
            _state.value = State.NotConfigured
        } else {
            vault.write(VaultData(rest))
            settings.setActiveServer(0)
            setData(VaultData(rest))
            connect()
        }
        removed?.let { onProfileRemoved(it.profile) }
    }

    val profile: ServerProfile? get() = contents?.profile

    /** Index of the active server in [servers]. */
    fun activeIndex(): Int = settings.activeServer.value.coerceIn(0, ((data?.servers?.size ?: 1) - 1).coerceAtLeast(0))

    /** Current vault contents for setup to extend. Only valid while unlocked. */
    fun storedServers(): List<VaultContents> = data?.servers.orEmpty()

    private fun setData(d: VaultData) {
        data = d
        _servers.value = d.servers.map { it.profile }
    }

    private fun VaultContents.derivedKeys() = Scram.DerivedKeys(
        scramSalt, scramIterations,
        Base64.getDecoder().decode(clientKey), Base64.getDecoder().decode(serverKey),
    )

    private fun isTransient(e: Throwable) =
        e is IOException && e !is PublicAddressException ||
            e is ConnectionClosedException ||
            e is TimeoutCancellationException

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}

fun vaultContents(profile: ServerProfile, keys: Scram.DerivedKeys) = VaultContents(
    profile = profile,
    scramSalt = keys.salt,
    scramIterations = keys.iterations,
    clientKey = Base64.getEncoder().encodeToString(keys.clientKey),
    serverKey = Base64.getEncoder().encodeToString(keys.serverKey),
)

/** Adds or replaces (same address, user and key id) [entry]; returns the new list and its index. */
fun withServer(existing: List<VaultContents>, entry: VaultContents): Pair<List<VaultContents>, Int> {
    val same = existing.indexOfFirst {
        it.profile.url == entry.profile.url && it.profile.username == entry.profile.username && it.profile.apiKeyId == entry.profile.apiKeyId
    }
    return if (same >= 0) existing.toMutableList().also { it[same] = entry } to same
    else (existing + entry) to existing.size
}
