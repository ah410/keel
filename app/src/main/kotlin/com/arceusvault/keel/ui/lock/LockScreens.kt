package com.arceusvault.keel.ui.lock

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import com.arceusvault.keel.ui.components.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.arceusvault.keel.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.core.network.UntrustedCertificateException
import com.arceusvault.keel.data.SessionManager
import com.arceusvault.keel.domain.CertTrust
import com.arceusvault.keel.ui.LocalAuthenticator
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.SectionCard
import com.arceusvault.keel.ui.components.describeError
import com.arceusvault.keel.ui.components.KeelMark
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.coroutines.launch
import java.text.DateFormat

@Composable
private fun FullScreen(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
fun LockScreen(session: SessionManager) {
    val auth = LocalAuthenticator.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var invalidated by remember { mutableStateOf(false) }

    val unlock: () -> Unit = {
        scope.launch {
            error = null
            if (!auth.authenticate("Unlock Keel")) return@launch
            try {
                session.unlock()
            } catch (e: KeyPermanentlyInvalidatedException) {
                // Android destroys auth-bound keys when the screen lock is removed or reset.
                invalidated = true
            } catch (e: Exception) {
                error = "Couldn't open stored credentials: ${e.message}"
            }
        }
    }
    LaunchedEffect(Unit) { unlock() }

    FullScreen {
        KeelMark(Modifier.padding(bottom = 8.dp).size(64.dp))
        Text("Keel is locked", style = MaterialTheme.typography.headlineSmall)
        if (invalidated) {
            Text("Your screen lock changed, so Android destroyed the key protecting your server credentials. Set the server up again.")
            Button(session::forget) { Text("Set up again") }
        } else {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(unlock) { Text("Unlock") }
        }
    }
}

@Composable
fun ConnectingScreen(state: SessionManager.State.Connecting) {
    FullScreen {
        CircularProgressIndicator()
        Text("Connecting to ${state.profile.displayAddress}…")
        if (state.attempt > 1) Text("Attempt ${state.attempt}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FailedScreen(state: SessionManager.State.Failed, session: SessionManager) {
    val e = state.error
    if (e is UntrustedCertificateException && e.reason == UntrustedCertificateException.Reason.CHANGED) {
        CertificateChanged(e, state, session)
        return
    }
    FullScreen {
        Text("Can't connect", style = MaterialTheme.typography.headlineSmall)
        Text(describeError(e))
        Button(session::connect) { Text("Try again") }
        OtherServers(session)
        OutlinedButton(session::lock) { Text("Lock") }
    }
}

/** With several servers stored, one being down shouldn't lock you out of the rest. */
@Composable
private fun OtherServers(session: SessionManager) {
    val servers = session.servers.collectAsState().value
    if (servers.size < 2) return
    val active = session.activeIndex()
    servers.forEachIndexed { i, p ->
        if (i != active) TextButton({ session.switchTo(i) }) { Text("Switch to ${p.displayAddress} (${p.username})") }
    }
}

@Composable
private fun CertificateChanged(e: UntrustedCertificateException, state: SessionManager.State.Failed, session: SessionManager) {
    val auth = LocalAuthenticator.current
    val scope = rememberCoroutineScope()
    val cert = e.certificate
    var error by remember { mutableStateOf<String?>(null) }
    FullScreen {
        Icon(Icons.Default.Warning, null, tint = StatusColors.bad)
        Text("Server certificate changed", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${state.profile.displayAddress} presented a different certificate than the one you trusted. This happens " +
                "when TrueNAS renews or replaces its certificate, but it's also exactly what interception looks like. " +
                "Nothing was sent to the server.",
        )
        if (state.profile.trust == CertTrust.CaVerified) {
            Text(
                "This server used to have a publicly trusted certificate and now doesn't. Be very careful.",
                color = StatusColors.bad,
            )
        }
        SectionCard(title = "New certificate SHA-256") {
            Text(cert.certSha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            KeyValue("Issuer", cert.issuer)
            KeyValue("Expires", DateFormat.getDateInstance().format(cert.notAfter))
        }
        Text("Only continue if you changed the certificate yourself and this fingerprint matches what TrueNAS shows.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(session::lock, Modifier.fillMaxWidth()) { Text("Don't connect") }
        OtherServers(session)
        OutlinedButton(
            {
                scope.launch {
                    if (!auth.authenticate("Trust new certificate")) return@launch
                    runCatching { session.retrust(cert) }.onFailure { error = describeError(it) }
                }
            },
            Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusColors.bad),
        ) { Text("I verified it, trust the new certificate") }
    }
}
