package com.arceusvault.keel.ui.apps

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import com.arceusvault.keel.ui.components.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.arceusvault.keel.ui.components.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.arceusvault.keel.data.Action
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.App
import com.arceusvault.keel.data.AppUpgradeSummary
import com.arceusvault.keel.ui.LOG_SEP
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.Route
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.Loadable
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LoadableInline
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.describeError
import com.arceusvault.keel.ui.components.formatBitRate
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.rememberLive
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.theme.PlexMono
import com.arceusvault.keel.ui.theme.StatusColors
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDetailScreen(name: String, padding: PaddingValues) {
    val server = LocalServer.current
    val api = server.api
    val nav = LocalNavigator.current
    val actions = LocalActions.current
    val running = rememberRunning(server)
    val apps = rememberLive(api) { api.appsLive(server.profile.host) }
    val stats = rememberAppStats(api)[name]
    var upgrade by remember { mutableStateOf(false) }
    var rollback by remember { mutableStateOf(false) }

    LoadableContent(apps) { list ->
        val app = list.find { it.name == name }
        if (app == null) {
            CenteredMessage("App not found", "$name may have been deleted.")
            return@LoadableContent
        }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TnCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppBadge(app.name, 48)
                        Column(Modifier.weight(1f)) {
                            Text(app.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                listOfNotNull(app.humanVersion ?: app.version, app.train, if (app.customApp) "custom" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.muted,
                            )
                        }
                        Pill(app.state, statusColor(app.state))
                    }
                    app.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted, maxLines = 3) }
                    val controls = buildList {
                        if (app.state == "RUNNING" || app.state == "DEPLOYING") add(Actions.appStop(name)) else add(Actions.appStart(name))
                        add(Actions.appRedeploy(name))
                    }.filter(actions::can)
                    if (controls.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            controls.forEach { a -> ActionButton(a, a.key in running) { actions.launch(a) } }
                            if (actions.can(Actions.appUpgrade(name, "latest", false))) {
                                if (app.upgradeAvailable) Button({ upgrade = true }) { Text("Upgrade") }
                                OutlinedButton({ rollback = true }) { Text("Roll back") }
                            }
                        }
                    } else {
                        Text("This API key can only view apps.", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                    }
                    OutlinedButton({ nav.open(Route("app-config", name)) }) { Text(if (actions.can(Actions.appStart(name))) "Edit settings" else "View settings") }
                    if (app.upgradeAvailable) {
                        Text("Version ${app.latestVersion ?: "update"} is available.", style = MaterialTheme.typography.bodySmall, color = StatusColors.info)
                    } else if (app.imageUpdatesAvailable) {
                        Text("Newer container images are available. Redeploy pulls them.", style = MaterialTheme.typography.bodySmall, color = StatusColors.info)
                    }
                }
            }
            if (app.state == "RUNNING" && stats != null) item {
                TnCard(title = "Usage") {
                    KeyValue("CPU", String.format(Locale.ROOT, "%.1f%%", stats.cpuPercent))
                    KeyValue("Memory", formatBytes(stats.memory))
                    KeyValue("Network", "↓ ${formatBitRate(stats.rxRate.toDouble())}   ↑ ${formatBitRate(stats.txRate.toDouble())}")
                }
            }
            app.portalLinks.takeIf { it.isNotEmpty() }?.let { links -> item { PortalsCard(links, server.profile.host) } }
            app.workloads?.containerDetails?.takeIf { it.isNotEmpty() }?.let { containers ->
                item {
                    TnCard(title = "Containers", subtitle = "Tap one to follow its logs", padded = false) {
                        containers.forEachIndexed { i, c ->
                            ListRow(
                                c.serviceName.ifBlank { c.id.take(12) },
                                subtitle = c.image,
                                trailing = { Pill(c.state, statusColor(c.state)) },
                                onClick = { nav.open(Route("app-logs", "$name$LOG_SEP${c.id}")) },
                                divider = i != containers.lastIndex,
                            )
                        }
                    }
                }
            }
            app.workloads?.usedPorts?.takeIf { it.isNotEmpty() }?.let { ports ->
                item {
                    TnCard(title = "Ports") {
                        ports.forEach { port ->
                            val host = port.hostPorts.joinToString { "${it.hostIp.ifBlank { "*" }}:${it.hostPort}" }
                            KeyValue("${port.containerPort}/${port.protocol}", host.ifBlank { "not published" }, mono = true)
                        }
                    }
                }
            }
            app.workloads?.volumes?.takeIf { it.isNotEmpty() }?.let { vols ->
                item {
                    TnCard(title = "Storage") {
                        vols.distinctBy { it.source to it.destination }.forEach { v ->
                            Column {
                                Text(v.destination, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PlexMono))
                                Text("${v.type} ${v.source} (${v.mode})".trim(), style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                            }
                        }
                    }
                }
            }
            app.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                item { TnCard(title = "Notes") { Text(notes, style = MaterialTheme.typography.bodySmall) } }
            }
        }
        if (upgrade) UpgradeDialog(app, onDismiss = { upgrade = false })
        if (rollback) RollbackDialog(app, onDismiss = { rollback = false })
    }
}

@Composable
private fun ActionButton(action: Action, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, enabled = !busy) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Text(action.confirmLabel)
    }
}

@Composable
private fun PortalsCard(links: List<Pair<String, String>>, host: String) {
    val ctx = LocalContext.current
    TnCard(title = "Web portals", padded = false) {
        links.forEachIndexed { i, (label, raw) ->
            // Portals bound to all interfaces come back as 0.0.0.0; point them at the server we use.
            val url = raw.replace("://0.0.0.0", "://$host")
            ListRow(
                label,
                subtitle = url,
                trailing = { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open") },
                onClick = {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (_: ActivityNotFoundException) {
                    }
                },
                divider = i != links.lastIndex,
            )
        }
    }
}

@Composable
private fun UpgradeDialog(app: App, onDismiss: () -> Unit) {
    val server = LocalServer.current
    val actions = LocalActions.current
    var summary by remember { mutableStateOf<Loadable<AppUpgradeSummary>>(Loadable.Loading) }
    var version by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf(true) }
    LaunchedEffect(app.name) {
        summary = try {
            Loadable.Ready(server.api.upgradeSummary(app.name)).also { version = it.value.latestVersion }
        } catch (e: Exception) {
            Loadable.Error(describeError(e))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upgrade ${app.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadableInline(summary) { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Installed: ${app.humanVersion ?: app.version}", style = MaterialTheme.typography.bodyMedium)
                        val choices = s.available.ifEmpty { listOf(com.arceusvault.keel.data.AppVersionInfo(s.latestVersion, s.latestHumanVersion)) }
                        choices.forEach { v ->
                            Row(
                                Modifier.fillMaxWidth().selectable(version == v.version) { version = v.version },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(version == v.version, null)
                                Text(v.humanVersion.ifBlank { v.version })
                            }
                        }
                        choices.find { it.version == version }?.changelog?.takeIf { it.isNotBlank() }?.let { log ->
                            Text("Changelog", style = MaterialTheme.typography.titleSmall)
                            Text(stripTags(log), style = MaterialTheme.typography.bodySmall, maxLines = 12)
                        }
                        Row(Modifier.selectable(snapshot) { snapshot = !snapshot }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(snapshot, null)
                            Text("Snapshot host path volumes first", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = version != null,
                onClick = {
                    onDismiss()
                    actions.launch(Actions.appUpgrade(app.name, version ?: "latest", snapshot))
                },
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RollbackDialog(app: App, onDismiss: () -> Unit) {
    val server = LocalServer.current
    val actions = LocalActions.current
    var versions by remember { mutableStateOf<Loadable<List<String>>>(Loadable.Loading) }
    var version by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(app.name) {
        versions = try {
            Loadable.Ready(server.api.rollbackVersions(app.name))
        } catch (e: Exception) {
            Loadable.Error(describeError(e))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Roll back ${app.title}") },
        text = {
            LoadableInline(versions) { list ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (list.isEmpty()) Text("There are no earlier versions to roll back to.")
                    list.forEach { v ->
                        Row(
                            Modifier.fillMaxWidth().selectable(version == v) { version = v },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(version == v, null)
                            Text(v)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = version != null, onClick = {
                onDismiss()
                version?.let { actions.launch(Actions.appRollback(app.name, it)) }
            }) { Text("Continue") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&").trim()
