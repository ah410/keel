package com.arceusvault.keel.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.components.statusColor

@Composable
fun NetworkScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val interfaces = rememberPolled(api, 60_000) { api.interfaces() }
    LoadableContent(interfaces) { list ->
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.sortedBy { it.name }, key = { it.id }) { nic ->
                val link = nic.linkState?.removePrefix("LINK_STATE_")
                TnCard(
                    title = nic.name,
                    subtitle = listOfNotNull(nic.type.lowercase().ifBlank { null }, nic.description.ifBlank { null }).joinToString(" · "),
                    actions = { link?.let { Pill(it, statusColor(it)) } },
                ) {
                    nic.addresses.forEach { KeyValue("Address", it, mono = true) }
                    if (nic.addresses.isEmpty()) KeyValue("Address", "none")
                    KeyValue("DHCP", if (nic.dhcp) "yes" else "no")
                    nic.activeMedia?.let { KeyValue("Media", it) }
                    nic.mtu?.let { KeyValue("MTU", it.toString()) }
                    nic.macAddress?.let { KeyValue("MAC", it, mono = true) }
                }
            }
        }
    }
}
