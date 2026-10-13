package com.arceusvault.keel.ui.virt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.Vm
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.rememberLive
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.theme.StatusColors

@Composable
fun VmsScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val vms = rememberLive(api) { api.vmsLive() }
    LoadableContent(vms) { list ->
        if (list.isEmpty()) {
            CenteredMessage("No virtual machines", icon = Icons.Default.Computer)
            return@LoadableContent
        }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.sortedBy { it.name }, key = { it.id }) { VmCard(it) }
        }
    }
}

@Composable
private fun VmCard(vm: Vm) {
    val server = LocalServer.current
    val actions = LocalActions.current
    val running = rememberRunning(server)
    val on = vm.state == "RUNNING"
    val controls = (if (on) listOf(Actions.vmStop(vm.id, vm.name), Actions.vmPowerOff(vm.id, vm.name)) else listOf(Actions.vmStart(vm.id, vm.name)))
        .filter(actions::can)
    TnCard(title = vm.name, subtitle = vm.description.ifBlank { null }, actions = { Pill(vm.state, statusColor(vm.state)) }) {
        KeyValue("CPUs", "${vm.cpuCount}")
        KeyValue("Memory", formatBytes(vm.memory * 1024 * 1024))
        KeyValue("Autostart", if (vm.autostart) "yes" else "no")
        if (controls.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                controls.forEach { a ->
                    TextButton({ actions.launch(a) }, enabled = a.key !in running) {
                        Text(a.confirmLabel, color = if (a.tier >= com.arceusvault.keel.data.Tier.DESTRUCTIVE) StatusColors.bad else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
