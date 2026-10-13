package com.arceusvault.keel.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import com.arceusvault.keel.ui.components.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.arceusvault.keel.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.core.security.DeviceAuth
import com.arceusvault.keel.domain.ConnectionMode
import com.arceusvault.keel.ui.LocalAuthenticator
import com.arceusvault.keel.ui.components.KeelMark
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.SectionCard
import com.arceusvault.keel.ui.theme.StatusColors
import java.text.DateFormat

@Composable
fun SetupFlow(vm: SetupViewModel) {
    BackHandler(enabled = vm.step != SetupViewModel.Step.WELCOME && vm.step != SetupViewModel.Step.CONNECTING) { vm.back() }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (vm.step) {
            SetupViewModel.Step.WELCOME -> Welcome(vm)
            SetupViewModel.Step.MODE -> ModeChoice(vm)
            SetupViewModel.Step.DIRECT_WARNING -> DirectWarning(vm)
            SetupViewModel.Step.DETAILS -> Details(vm)
            SetupViewModel.Step.CONNECTING -> Connecting(vm.progress)
            SetupViewModel.Step.CERT_CONFIRM -> CertConfirm(vm)
            SetupViewModel.Step.REVIEW -> Review(vm)
        }
    }
}

@Composable
private fun Welcome(vm: SetupViewModel) {
    val secure = DeviceAuth.isDeviceSecure(LocalContext.current)
    Spacer(Modifier.height(32.dp))
    KeelMark(Modifier.size(64.dp))
    Text("Keel", style = MaterialTheme.typography.headlineMedium)
    Text(
        "A mobile client for TrueNAS. Not affiliated with TrueNAS or iXsystems.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Monitor and manage your TrueNAS SCALE server from your phone. The app can only do what your API " +
            "key's roles allow, so a read-only key gives you a read-only app.",
    )
    Text(
        "Your API key is never sent over the network. The app signs in with SCRAM, stores only derived key " +
            "material in hardware-backed storage, and talks to no one but your server.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (secure) {
        Button(vm::start, Modifier.fillMaxWidth()) { Text("Get started") }
    } else {
        SectionCard(title = "Screen lock required") {
            Text("Set a PIN, pattern or password in Android settings first. Your credentials are protected by it.")
        }
    }
}

@Composable
private fun ModeChoice(vm: SetupViewModel) {
    if (vm.adding) Text("Add a server", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text("How do you reach your server?", style = MaterialTheme.typography.headlineSmall)
    ModeCard(
        icon = { Icon(Icons.Default.Lock, null, tint = StatusColors.ok) },
        title = "Secure connection (recommended)",
        body = "Over your home network or a VPN like Tailscale or WireGuard. The app refuses to connect if the " +
            "address turns out to be public, so your server is never reached over the open internet.",
        onClick = { vm.chooseMode(ConnectionMode.SECURE) },
    )
    ModeCard(
        icon = { Icon(Icons.Default.Public, null, tint = StatusColors.warn) },
        title = "Direct connection (advanced)",
        body = "To a server you've exposed to the internet through port forwarding or a reverse proxy. TLS is " +
            "still required, but your server's login is reachable by anyone.",
        onClick = { vm.chooseMode(ConnectionMode.DIRECT) },
    )
    if (vm.adding) TextButton(vm::cancel) { Text("Cancel") }
}

@Composable
private fun ModeCard(icon: @Composable () -> Unit, title: String, body: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            icon()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DirectWarning(vm: SetupViewModel) {
    var understood by remember { mutableStateOf(false) }
    Icon(Icons.Default.Warning, null, tint = StatusColors.warn, modifier = Modifier.padding(top = 16.dp))
    Text("You're about to weaken a security default", style = MaterialTheme.typography.headlineSmall)
    Card(colors = CardDefaults.cardColors(containerColor = StatusColors.warn.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Exposing TrueNAS to the internet means:")
            Text("• Anyone can reach its login and API, and probe it for vulnerabilities.")
            Text("• A single unpatched TrueNAS bug could expose all of your data.")
            Text("• This app can't tell a hostile network from your own.")
            Text("A VPN such as Tailscale avoids all of this and takes a few minutes to set up.")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(understood, { understood = it })
        Text("I understand the risk and my server is already exposed on purpose.")
    }
    Button(
        vm::acceptDirectRisk,
        enabled = understood,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Use direct connection anyway") }
    OutlinedButton({ vm.chooseMode(ConnectionMode.SECURE) }, Modifier.fillMaxWidth()) { Text("Use secure connection instead") }
}

@Composable
private fun Details(vm: SetupViewModel) {
    Text("Server details", style = MaterialTheme.typography.headlineSmall)
    if (vm.mode == ConnectionMode.DIRECT) Text("Direct connection", color = StatusColors.warn)
    OutlinedTextField(
        vm.host, { vm.host = it },
        label = { Text("Hostname or IP") },
        placeholder = { Text("truenas.tailnet.ts.net") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        vm.port, { vm.port = it.filter(Char::isDigit).take(5) },
        label = { Text("HTTPS port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        vm.username, { vm.username = it },
        label = { Text("Username") },
        placeholder = { Text("truenas_admin") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        vm.apiKey, { vm.apiKey = it.trim() },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // Password type keeps keyboards from learning or suggesting the key.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Create one in TrueNAS under your user menu → API Keys. For least privilege, create it for a user whose " +
            "privilege has Read-Only Administrator plus only the write roles you want, such as APPS_WRITE.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(vm::connect, Modifier.fillMaxWidth()) { Text("Connect") }
    TextButton(vm::back) { Text("Back") }
}

@Composable
private fun Connecting(progress: String) {
    Spacer(Modifier.height(64.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(progress)
    }
}

@Composable
private fun CertConfirm(vm: SetupViewModel) {
    val cert = vm.pendingCert ?: return
    Text("Verify this certificate", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Your server uses a certificate that isn't signed by a public authority, which is normal for a home " +
            "server. Before trusting it, compare the SHA-256 fingerprint below with the one shown in TrueNAS " +
            "under Credentials → Certificates, or in your desktop browser's certificate viewer.",
    )
    SectionCard(title = "SHA-256 fingerprint") {
        Text(cert.certSha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    SectionCard(title = "Details") {
        KeyValue("Subject", cert.subject)
        KeyValue("Issuer", cert.issuer)
        KeyValue("Expires", DateFormat.getDateInstance().format(cert.notAfter))
    }
    Text(
        "Once trusted, the app pins this certificate's key. If it ever changes, the app stops and warns you.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(vm::trustCertificate, Modifier.fillMaxWidth()) { Text("Fingerprint matches, trust it") }
    OutlinedButton(vm::rejectCertificate, Modifier.fillMaxWidth()) { Text("Don't trust") }
}

@Composable
private fun Review(vm: SetupViewModel) {
    val auth = LocalAuthenticator.current
    Text("Signed in", style = MaterialTheme.typography.headlineSmall)
    Text("The server proved it holds your key, and the key works.")
    if (vm.fullAdmin) {
        Card(colors = CardDefaults.cardColors(containerColor = StatusColors.warn.copy(alpha = 0.12f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This key has full admin rights", style = MaterialTheme.typography.titleSmall)
                Text(
                    "If your phone were ever compromised this key could change anything on your server, including " +
                        "deleting data. A user with Read-Only Administrator plus just the write roles you need limits that.",
                )
            }
        }
    }
    SectionCard(title = "What this key can do") {
        com.arceusvault.keel.ui.settings.CapabilityList(vm.roles)
    }
    vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button({ vm.finish(auth) }, Modifier.fillMaxWidth()) { Text("Save and continue") }
    TextButton(vm::back) { Text("Start over") }
}
