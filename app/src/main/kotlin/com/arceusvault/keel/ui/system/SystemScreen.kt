package com.arceusvault.keel.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.Route
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatUptime
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.theme.StatusColors

@Composable
fun SystemScreen(padding: PaddingValues) {
    val server = LocalServer.current
    val nav = LocalNavigator.current
    val actions = LocalActions.current
    val info = rememberPolled(server.api, 60_000) { server.api.systemInfo() }.valueOrNull
    val hostname = info?.hostname ?: server.profile.host
    val reboot = Actions.reboot(hostname)
    val shutdown = Actions.shutdown(hostname)

    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        info?.let { i ->
            item {
                TnCard(title = i.hostname) {
                    KeyValue("Version", i.version)
                    KeyValue("Uptime", formatUptime(i.uptimeSeconds))
                }
            }
        }
        item {
            TnCard(padded = false) {
                ListRow("Services", subtitle = "SMB, NFS, SSH and others", onClick = { nav.open(Route("services")) })
                ListRow("Update", subtitle = "Check for and install TrueNAS updates", onClick = { nav.open(Route("update")) })
                ListRow("Boot Environments", subtitle = "Installed versions you can boot into", onClick = { nav.open(Route("bootenvs")) })
                ListRow("Network", subtitle = "Interfaces and addresses", onClick = { nav.open(Route("network")) })
                ListRow("Audit Log", subtitle = "Who changed what on the server", onClick = { nav.open(Route("audit")) }, divider = false)
            }
        }
        if (actions.can(reboot) || actions.can(shutdown)) {
            item {
                TnCard(title = "Power", padded = false) {
                    if (actions.can(reboot)) {
                        ListRow(
                            "Reboot", subtitle = "Asks you to type the hostname and unlock first",
                            leading = { Icon(Icons.Default.RestartAlt, null, tint = StatusColors.warn) },
                            onClick = { actions.launch(reboot) },
                        )
                    }
                    if (actions.can(shutdown)) {
                        ListRow(
                            "Shut down", subtitle = "Turning it back on needs physical access or IPMI",
                            leading = { Icon(Icons.Default.PowerSettingsNew, null, tint = StatusColors.bad) },
                            onClick = { actions.launch(shutdown) },
                            divider = false,
                        )
                    }
                }
            }
        } else {
            item {
                TnCard(title = "Power") {
                    Text("Reboot and shut down need a full admin API key. This key can't do either, which is the safer setup.", color = StatusColors.muted)
                }
            }
        }
    }
}
