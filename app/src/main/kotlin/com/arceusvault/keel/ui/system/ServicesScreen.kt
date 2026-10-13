package com.arceusvault.keel.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.Service
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.rememberLive
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.theme.StatusColors

@Composable
fun ServicesScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val services = rememberLive(api) { api.servicesLive() }
    LoadableContent(services) { list ->
        // Running first, then enabled-but-stopped (likely a problem), then the rest.
        val sorted = list.sortedWith(compareBy({ it.state != "RUNNING" }, { !it.enable }, { it.service }))
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { it.id }) { ServiceCard(it) }
        }
    }
}

@Composable
private fun ServiceCard(s: Service) {
    val server = LocalServer.current
    val actions = LocalActions.current
    val running = rememberRunning(server)
    val isRunning = s.state == "RUNNING"
    val toggle = Actions.serviceControl(s.service, if (isRunning) "STOP" else "START")
    val restart = Actions.serviceControl(s.service, "RESTART")
    val boot = Actions.serviceSetEnabled(s.service, !s.enable)
    TnCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(s.displayName, style = MaterialTheme.typography.titleSmall)
                if (s.enable && !isRunning) Text("Set to start on boot but not running", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
            }
            Pill(s.state, statusColor(s.state))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start automatically", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = StatusColors.muted)
            Switch(s.enable, onCheckedChange = { actions.launch(boot) }, enabled = actions.can(boot) && boot.key !in running)
        }
        if (actions.can(toggle)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ actions.launch(toggle) }, enabled = toggle.key !in running) { Text(if (isRunning) "Stop" else "Start") }
                if (isRunning) TextButton({ actions.launch(restart) }, enabled = restart.key !in running) { Text("Restart") }
            }
        }
    }
}
