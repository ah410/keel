package com.arceusvault.keel.ui.system

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import com.arceusvault.keel.ui.components.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.UsageBar
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.components.rememberRefresher
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.theme.StatusColors
import java.util.Locale

@Composable
fun UpdateScreen(padding: PaddingValues) {
    val server = LocalServer.current
    val api = server.api
    val actions = LocalActions.current
    val running = rememberRunning(server)
    val ctx = LocalContext.current
    val refresher = rememberRefresher()
    val status = rememberPolled(api, 30_000, refresher) { api.updateStatus() }
    val hostname = rememberPolled(api, 600_000) { api.systemInfo().hostname }.valueOrNull ?: server.profile.host
    var installed by remember { mutableStateOf(false) }

    LoadableContent(status) { st ->
        val current = st.status?.current
        val next = st.status?.newVersion
        val download = st.download
        val install = Actions.updateApply(next?.version, hostname)
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TnCard(title = "Current") {
                    current?.let {
                        KeyValue("Train", it.train)
                        KeyValue("Profile", it.profile)
                        if (!it.matchesProfile) Text("The running version doesn't match the configured update profile.", color = StatusColors.warn, style = MaterialTheme.typography.bodySmall)
                    }
                    st.error?.let { Text("${it.errname}: ${it.reason}", color = StatusColors.bad, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                TnCard(title = if (next != null) "Update available" else "Up to date") {
                    if (next == null) {
                        Text("No newer version for this profile.", color = StatusColors.muted)
                        TextButton(refresher::refresh) { Text("Check again") }
                        return@TnCard
                    }
                    KeyValue("Version", next.version)
                    download?.let {
                        Text("${it.description} ${String.format(Locale.ROOT, "%.0f%%", it.percent)}", style = MaterialTheme.typography.bodySmall)
                        UsageBar(it.percent / 100)
                    }
                    next.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                        Text("Release notes", style = MaterialTheme.typography.titleSmall)
                        Text(notes.take(4000), style = MaterialTheme.typography.bodySmall)
                    }
                    next.releaseNotesUrl?.let { url ->
                        TextButton({
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            } catch (_: ActivityNotFoundException) {
                            }
                        }) { Text("Open release notes") }
                    }
                    if (actions.can(install)) {
                        Button({ actions.launch(install) { installed = true; refresher.refresh() } }, enabled = install.key !in running && !installed) {
                            Text(if (install.key in running) "Installing..." else "Download and install")
                        }
                        Text(
                            "Installs into a new boot environment. Nothing changes until the server reboots, and the old version stays bootable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.muted,
                        )
                    }
                }
            }
            if (installed) {
                val reboot = Actions.reboot(hostname)
                item {
                    TnCard(title = "Reboot to finish") {
                        Text("The update is installed and becomes active after a reboot.")
                        if (actions.can(reboot)) Button({ actions.launch(reboot) }) { Text("Reboot now") }
                    }
                }
            }
        }
    }
}
