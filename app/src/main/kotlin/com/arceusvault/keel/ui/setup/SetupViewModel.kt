package com.arceusvault.keel.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arceusvault.keel.AppContainer
import com.arceusvault.keel.core.network.RawApiKey
import com.arceusvault.keel.core.network.ServerCertificate
import com.arceusvault.keel.core.network.UntrustedCertificateException
import com.arceusvault.keel.data.KeyMaterial
import com.arceusvault.keel.data.LoginResult
import com.arceusvault.keel.core.security.VaultData
import com.arceusvault.keel.data.vaultContents
import com.arceusvault.keel.data.withServer
import com.arceusvault.keel.domain.CertTrust
import com.arceusvault.keel.domain.ConnectionMode
import com.arceusvault.keel.domain.ServerProfile
import com.arceusvault.keel.ui.Authenticator
import com.arceusvault.keel.ui.components.describeError
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** [adding] is true when setting up an extra server from Settings, which skips the welcome screen. */
class SetupViewModel(private val c: AppContainer, val adding: Boolean = false) : ViewModel() {
    enum class Step { WELCOME, MODE, DIRECT_WARNING, DETAILS, CONNECTING, CERT_CONFIRM, REVIEW }

    var step by mutableStateOf(if (adding) Step.MODE else Step.WELCOME); private set
    var mode by mutableStateOf(ConnectionMode.SECURE); private set
    var host by mutableStateOf("")
    var port by mutableStateOf("443")
    var username by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var error by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(""); private set
    var pendingCert by mutableStateOf<ServerCertificate?>(null); private set
    var fullAdmin by mutableStateOf(false); private set
    var roles by mutableStateOf(com.arceusvault.keel.data.Roles.NONE); private set

    private var trust: CertTrust? = null
    private var login: LoginResult? = null
    private var profile: ServerProfile? = null

    fun start() { step = Step.MODE }

    fun chooseMode(m: ConnectionMode) {
        mode = m
        step = if (m == ConnectionMode.DIRECT) Step.DIRECT_WARNING else Step.DETAILS
    }

    fun back() {
        error = null
        if (adding && step == Step.MODE) {
            cancel()
            return
        }
        step = when (step) {
            Step.MODE -> Step.WELCOME
            Step.DIRECT_WARNING -> Step.MODE
            Step.DETAILS -> Step.MODE
            Step.CERT_CONFIRM, Step.REVIEW -> { reset(); Step.DETAILS }
            else -> step
        }
    }

    fun acceptDirectRisk() { step = Step.DETAILS }

    fun connect() {
        error = null
        val h = host.trim().removePrefix("https://").removePrefix("wss://").substringBefore('/').substringBefore(':')
        val p = port.trim().toIntOrNull()
        val key = RawApiKey.parse(apiKey)
        when {
            h.isEmpty() -> { error = "Enter your server's hostname or IP address."; return }
            p == null || p !in 1..65535 -> { error = "Port must be between 1 and 65535."; return }
            username.isBlank() -> { error = "Enter the TrueNAS user that owns the API key."; return }
            key == null -> { error = "That doesn't look like a TrueNAS API key (expected <id>-<64 characters>)."; return }
        }
        host = h
        step = Step.CONNECTING
        viewModelScope.launch {
            try {
                progress = "Connecting to $h…"
                val url = ServerProfile(h, p, mode, CertTrust.CaVerified, "", 0).url
                val rpc = try {
                    c.connector.open(url, trust, mode)
                } catch (e: UntrustedCertificateException) {
                    if (e.reason != UntrustedCertificateException.Reason.FIRST_USE) throw e
                    pendingCert = e.certificate
                    step = Step.CERT_CONFIRM
                    return@launch
                }
                val decided = trust ?: CertTrust.CaVerified
                progress = "Signing in (deriving key, this can take a few seconds)…"
                val result = c.connector.login(rpc, username.trim(), key.id, KeyMaterial.Raw(key))
                login = result
                profile = ServerProfile(h, p, mode, decided, username.trim(), key.id)
                fullAdmin = result.fullAdmin
                roles = result.roles
                apiKey = ""
                step = Step.REVIEW
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = describeError(e)
                step = Step.DETAILS
            } finally {
                key.wipe()
            }
        }
    }

    fun trustCertificate() {
        val cert = pendingCert ?: return
        trust = CertTrust.Pinned(cert.spkiSha256, cert.certSha256)
        connect()
    }

    fun rejectCertificate() {
        reset()
        step = Step.DETAILS
        error = "Certificate not trusted. Nothing was saved."
    }

    /** Saving needs a fresh device unlock because the vault key is auth-bound. */
    fun finish(auth: Authenticator) {
        val result = login ?: return
        val prof = profile ?: return
        viewModelScope.launch {
            if (!auth.authenticate("Save server credentials")) return@launch
            try {
                val (servers, index) = withServer(c.session.storedServers(), vaultContents(prof, result.keys))
                val data = VaultData(servers)
                c.vault.write(data)
                login = null
                c.session.adopt(data, index, result)
            } catch (e: Exception) {
                error = "Couldn't save credentials: ${describeError(e)}"
            }
        }
    }

    /** Leaves add-server setup without saving anything. */
    fun cancel() {
        reset()
        c.session.cancelAdding()
    }

    private fun reset() {
        login?.rpc?.close()
        login = null
        profile = null
        trust = null
        pendingCert = null
    }

    override fun onCleared() {
        login?.rpc?.close()
    }
}
