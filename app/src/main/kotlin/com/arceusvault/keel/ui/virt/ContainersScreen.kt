package com.arceusvault.keel.ui.virt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.ContainerMetrics
import com.arceusvault.keel.data.LxcContainer
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.formatPercent
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.components.rememberRefresher
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.components.statusColor

/** LXC containers (TrueNAS 25.10+ "Containers"); Docker apps live under Apps. */
@Composable
fun ContainersScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val refresher = rememberRefresher()
    val containers = rememberPolled(api, 15_000, refresher) { api.containers() }
    var metrics by remember(api) { mutableStateOf(emptyMap<String, ContainerMetrics>()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runCatching { api.containerMetrics().collect { metrics = metrics + it } }
        }
    }
    LoadableContent(containers) { list ->
        if (list.isEmpty()) {
            CenteredMessage("No containers", "LXC containers created under Containers in the web UI show up here.", icon = Icons.Default.ViewInAr)
            return@LoadableContent
        }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.sortedBy { it.name }, key = { it.id }) { c ->
                ContainerCard(c, metrics[c.name] ?: metrics[c.id.toString()], refresher::refresh)
            }
        }
    }
}

@Composable
private fun ContainerCard(c: LxcContainer, m: ContainerMetrics?, onChanged: () -> Unit) {
    val server = LocalServer.current
    val actions = LocalActions.current
    val running = rememberRunning(server)
    val on = c.state == "RUNNING"
    val action = if (on) Actions.containerStop(c.id, c.name) else Actions.containerStart(c.id, c.name)
    TnCard(title = c.name, subtitle = c.description.ifBlank { null }, actions = { Pill(c.state, statusColor(c.state)) }) {
        if (on && m != null) {
            m.cpuPercent?.let { KeyValue("CPU", formatPercent(it)) }
            m.memory?.let { KeyValue("Memory", formatBytes(it)) }
        }
        KeyValue("Dataset", c.dataset, mono = true)
        KeyValue("Autostart", if (c.autostart) "yes" else "no")
        if (actions.can(action)) {
            TextButton({ actions.launch(action, onChanged) }, enabled = action.key !in running) { Text(action.confirmLabel) }
        }
    }
}
