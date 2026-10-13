package com.arceusvault.keel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.arceusvault.keel.ui.components.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.AppContainer
import com.arceusvault.keel.BuildConfig
import com.arceusvault.keel.data.Roles
import com.arceusvault.keel.data.Settings
import com.arceusvault.keel.domain.CertTrust
import com.arceusvault.keel.domain.ConnectionMode
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.domain.ServerProfile
import com.arceusvault.keel.ui.LocalAuthenticator
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.theme.PlexMono
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.Palette
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.coroutines.launch

private val lockOptions = listOf(0 to "Immediately", 60 to "After 1 minute", 300 to "After 5 minutes")

@Composable
fun SettingsScreen(container: AppContainer, padding: PaddingValues) {
    val server = LocalServer.current
    val profile = server.profile
    val timeout by container.settings.lockTimeoutSeconds.collectAsState()
    val theme by container.settings.theme.collectAsState()
    var confirmForget by remember { mutableStateOf(false) }
    val servers by container.session.servers.collectAsState()
    val active by container.settings.activeServer.collectAsState()
    val auth = LocalAuthenticator.current
    val scope = rememberCoroutineScope()
    var licenses by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TnCard(title = "Server") {
            KeyValue("Address", profile.displayAddress)
            KeyValue("User", profile.username)
            KeyValue("API key ID", profile.apiKeyId.toString())
            KeyValue(
                "Connection",
                if (profile.mode == ConnectionMode.SECURE) "Secure (LAN/VPN only)" else "Direct (internet-exposed)",
            )
            when (val t = profile.trust) {
                CertTrust.CaVerified -> KeyValue("Certificate", "Verified by a public CA")
                is CertTrust.Pinned -> {
                    KeyValue("Certificate", "Pinned (self-signed)")
                    Text(t.certSha256, fontFamily = PlexMono, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ServersCard(servers, active.coerceIn(0, (servers.size - 1).coerceAtLeast(0)), container.session::switchTo, container.session::startAdding)

        PermissionsCard(server.roles)

        AlertNotificationsCard(container.alertWatch, profile)

        TnCard(title = "Theme") {
            val systemDark = isSystemInDarkTheme()
            ThemeOption(
                "Follow system",
                "Light or dark, matching your phone",
                Palette.resolve(Settings.SYSTEM_THEME, systemDark),
                theme == Settings.SYSTEM_THEME || Palette.all.none { it.name == theme },
            ) { container.settings.setTheme(Settings.SYSTEM_THEME) }
            Palette.all.forEach { p ->
                ThemeOption(p.label, null, p, theme == p.name) { container.settings.setTheme(p.name) }
            }
        }

        TnCard(title = "Lock app when in background") {
            lockOptions.forEach { (seconds, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(timeout == seconds) { container.settings.setLockTimeout(seconds) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(timeout == seconds, null)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }

        TnCard(title = "Privacy") {
            Text(
                "No analytics, crash reporting, ads or third-party services. The only network traffic is to your " +
                    "server. Screenshots and the recent-apps preview are blocked.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedButton({ licenses = true }, Modifier.fillMaxWidth()) { Text("Open source licenses") }
        OutlinedButton({ confirmForget = true }, Modifier.fillMaxWidth()) {
            Text("Forget this server", color = StatusColors.bad)
        }
        Text(
            "Keel ${BuildConfig.VERSION_NAME}. Not affiliated with TrueNAS or iXsystems.",
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.muted,
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this server?") },
            text = { Text("Stored key material and the pinned certificate for ${profile.displayAddress} are deleted from this phone. The API key on the server is not revoked; do that in TrueNAS if you no longer need it.") },
            confirmButton = {
                TextButton({
                    confirmForget = false
                    // With other servers left the vault is rewritten, which needs a fresh unlock.
                    if (servers.size <= 1) container.session.forget()
                    else scope.launch { if (auth.authenticate("Forget ${profile.displayAddress}")) container.session.forget() }
                }) { Text("Forget", color = StatusColors.bad) }
            },
            dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } },
        )
    }
    if (licenses) LicensesDialog { licenses = false }
}

@Composable
private fun ServersCard(servers: List<ServerProfile>, active: Int, onSwitch: (Int) -> Unit, onAdd: () -> Unit) {
    TnCard(title = "Servers", padded = false) {
        servers.forEachIndexed { i, p ->
            ListRow(
                p.displayAddress,
                subtitle = "${p.username} · ${if (p.mode == ConnectionMode.SECURE) "secure" else "direct"}",
                trailing = { if (i == active) Icon(Icons.Default.Check, "Active", tint = StatusColors.ok) },
                onClick = if (i == active) null else ({ onSwitch(i) }),
            )
        }
        TextButton(onAdd, Modifier.padding(horizontal = 8.dp)) { Text("Add server") }
    }
}

@Composable
private fun PermissionsCard(roles: Roles) {
    TnCard(title = "What this key can do", subtitle = if (roles.fullAdmin) "Full admin: everything" else null) {
        CapabilityList(roles)
        if (!roles.fullAdmin) {
            Text(
                "To allow more, add roles to this user's privilege in TrueNAS (Credentials > Groups > Privileges). " +
                    "Changes apply the next time the app unlocks.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
        }
    }
}

@Composable
private fun ThemeOption(label: String, detail: String?, palette: Palette, selected: Boolean, onSelect: () -> Unit) {
    val current = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected, null)
        // Swatch: background, card, accent and primary.
        Row(Modifier.clip(MaterialTheme.shapes.extraSmall).border(1.dp, current.lines, MaterialTheme.shapes.extraSmall)) {
            Box(Modifier.size(14.dp, 24.dp).background(palette.bg1))
            Box(Modifier.size(14.dp, 24.dp).background(palette.bg2))
            Box(Modifier.size(14.dp, 24.dp).background(palette.accent))
            Box(Modifier.size(14.dp, 24.dp).background(palette.primary))
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted) }
        }
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember {
        listOf("OFL-IBMPlexSans.txt", "OFL-IBMPlexMono.txt").joinToString("\n\n") { f ->
            "== ${f.removePrefix("OFL-").removeSuffix(".txt")} ==\n" +
                runCatching { ctx.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Keel is open source under the Apache License 2.0. Source: github.com/ah410/keel\n\n" +
                        "Fonts: IBM Plex Sans and IBM Plex Mono, under the SIL Open Font License 1.1. " +
                        "Libraries: AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, kotlinx.serialization and OkHttp, under the Apache License 2.0.\n",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(text, fontFamily = PlexMono, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}
