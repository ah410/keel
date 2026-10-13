package com.arceusvault.keel.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.Dataset
import com.arceusvault.keel.data.Snapshot
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Meter
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.components.rememberRefresher
import com.arceusvault.keel.ui.theme.StatusColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private class DatasetData(val dataset: Dataset?, val snapshots: List<Snapshot>)

@Composable
fun DatasetScreen(name: String, padding: PaddingValues) {
    val server = LocalServer.current
    val api = server.api
    val actions = LocalActions.current
    val refresher = rememberRefresher()
    val data = rememberPolled(name, 60_000, refresher) {
        DatasetData(api.datasets().find { it.name == name }, api.snapshots(name))
    }
    var creating by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Snapshot?>(null) }
    val canCreate = actions.can(Actions.snapshotCreate(name, "x", false))

    LoadableContent(data) { d ->
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            d.dataset?.let { ds -> item { DatasetInfo(ds) } }
            item {
                TnCard(
                    title = "Snapshots",
                    subtitle = "${d.snapshots.size} on this dataset",
                    padded = false,
                    actions = { if (canCreate) IconButton({ creating = true }) { Icon(Icons.Default.Add, "Create snapshot") } },
                ) {
                    if (d.snapshots.isEmpty()) {
                        Text("No snapshots", Modifier.padding(16.dp), color = StatusColors.muted)
                    }
                    d.snapshots.forEachIndexed { i, snap ->
                        ListRow(
                            snap.snapshotName.ifBlank { snap.name.substringAfter('@') },
                            subtitle = listOfNotNull(
                                snap.created?.let(::formatAgo),
                                snap.used?.let { "${formatBytes(it)} unique" },
                                snap.referenced?.let { "${formatBytes(it)} referenced" },
                            ).joinToString(" · "),
                            trailing = { if (snap.held) Pill("Held", StatusColors.info) },
                            onClick = { selected = snap },
                            divider = i != d.snapshots.lastIndex,
                        )
                    }
                }
            }
        }
        selected?.let { snap ->
            SnapshotDialog(snap, latest = snap == d.snapshots.firstOrNull(), onDismiss = { selected = null }, onChanged = refresher::refresh)
        }
    }
    if (creating) CreateSnapshotDialog(name, onDismiss = { creating = false }, onCreated = refresher::refresh)
}

@Composable
private fun DatasetInfo(ds: Dataset) {
    TnCard(title = ds.name, subtitle = ds.type.lowercase().replaceFirstChar { it.uppercase() }) {
        val used = ds.used?.long
        val avail = ds.available?.long
        if (used != null && avail != null && used + avail > 0) {
            Meter("Used", used.toDouble() / (used + avail), "${formatBytes(used)} of ${formatBytes(used + avail)}")
        }
        ds.usedbysnapshots?.long?.takeIf { it > 0 }?.let { KeyValue("Used by snapshots", formatBytes(it)) }
        ds.quota?.long?.takeIf { it > 0 }?.let { KeyValue("Quota", formatBytes(it)) }
        ds.refquota?.long?.takeIf { it > 0 }?.let { KeyValue("Ref quota", formatBytes(it)) }
        ds.compression?.text?.let { KeyValue("Compression", "$it (${ds.compressratio?.text ?: "n/a"})") }
        ds.deduplication?.text?.takeIf { it != "OFF" && it != "off" }?.let { KeyValue("Deduplication", it) }
        ds.recordsize?.text?.let { KeyValue("Record size", it) }
        ds.volsize?.long?.let { KeyValue("Volume size", formatBytes(it)) }
        if (ds.readonly?.bool == true || ds.readonly?.text == "ON") KeyValue("Read-only", "yes")
        if (ds.encrypted) KeyValue("Encryption", if (ds.locked) "locked" else "unlocked")
        ds.mountpoint?.let { KeyValue("Mount point", it, mono = true) }
    }
}

@Composable
private fun SnapshotDialog(snap: Snapshot, latest: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val actions = LocalActions.current
    val destroy = Actions.snapshotDestroy(snap.name)
    val rollback = Actions.snapshotRollback(snap.name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(snap.snapshotName.ifBlank { snap.name }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValue("Created", snap.created?.let(::formatAgo) ?: "n/a")
                snap.used?.let { KeyValue("Unique data", formatBytes(it)) }
                snap.referenced?.let { KeyValue("Referenced", formatBytes(it)) }
                if (snap.held) Text("This snapshot has a hold and can't be deleted until it's released.", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                if (!latest && actions.can(rollback)) {
                    Text("Only the newest snapshot can be rolled back to from here.", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                }
            }
        },
        confirmButton = {
            Row {
                if (latest && actions.can(rollback)) {
                    TextButton({ onDismiss(); actions.launch(rollback, onChanged) }) { Text("Roll back", color = StatusColors.bad) }
                }
                if (!snap.held && actions.can(destroy)) {
                    TextButton({ onDismiss(); actions.launch(destroy, onChanged) }) { Text("Delete", color = StatusColors.bad) }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CreateSnapshotDialog(dataset: String, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val actions = LocalActions.current
    var name by remember { mutableStateOf("manual-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))) }
    var recursive by remember { mutableStateOf(false) }
    // ZFS snapshot names: letters, digits and _-.: only.
    val valid = name.isNotBlank() && name.all { it.isLetterOrDigit() || it in "_-.:" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New snapshot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dataset, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                OutlinedTextField(
                    name, { name = it.trim() },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { if (!valid) Text("Use letters, numbers, _ - . or :") },
                )
                Row(Modifier.selectable(recursive) { recursive = !recursive }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, null)
                    Text("Include child datasets")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onDismiss(); actions.launch(Actions.snapshotCreate(dataset, name, recursive), onCreated) }) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
