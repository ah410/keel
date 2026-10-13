package com.arceusvault.keel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.arceusvault.keel.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.Disk
import com.arceusvault.keel.data.Pool
import com.arceusvault.keel.data.PoolScan
import com.arceusvault.keel.data.Vdev
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.Route
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Meter
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.UsageBar
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.rememberLive
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.StatusColors
import java.util.Locale

private class DiskData(val disks: List<Disk>, val temps: Map<String, Double>)

@Composable
fun StorageScreen(padding: PaddingValues) {
    val server = LocalServer.current
    val api = server.api
    val nav = LocalNavigator.current
    val pools = rememberLive(api) { api.poolsLive() }
    val disks = rememberPolled(api, 120_000) {
        val d = api.disks()
        // Temperatures are cached server-side for 5 minutes; failure here shouldn't hide the rest.
        DiskData(d, runCatching { api.diskTemperatures(d.map { it.name }) }.getOrDefault(emptyMap()))
    }
    val scans = rememberScans()

    LoadableContent(pools) { list ->
        val d = disks.valueOrNull
        val byPool = d?.disks.orEmpty().groupBy { it.pool }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TnCard(padded = false) {
                    ListRow("Datasets", subtitle = "Space, compression and snapshots", onClick = { nav.open(Route("datasets")) })
                    ListRow("Data Protection", subtitle = "Snapshot, replication, cloud sync and scrub tasks", onClick = { nav.open(Route("protection")) }, divider = false)
                }
            }
            items(list, key = { it.id }) { pool ->
                PoolDetailCard(pool, scans[pool.name] ?: pool.scan, byPool[pool.name].orEmpty(), d?.temps.orEmpty())
            }
            byPool[null]?.takeIf { it.isNotEmpty() }?.let { unassigned ->
                item {
                    TnCard(title = "Unassigned disks") { unassigned.forEach { DiskRow(it, d?.temps?.get(it.name)) } }
                }
            }
        }
    }
}

/** Live scrub/resilver progress by pool name; the pool.scan event is much more frequent than pool.query changes. */
@Composable
private fun rememberScans(): Map<String, PoolScan> {
    val api = LocalServer.current.api
    val scans = remember(api) { mutableStateMapOf<String, PoolScan>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runCatching { api.poolScans().collect { (name, scan) -> scans[name] = scan } }
        }
    }
    return scans
}

@Composable
private fun PoolDetailCard(pool: Pool, scan: PoolScan?, disks: List<Disk>, temps: Map<String, Double>) {
    val server = LocalServer.current
    val actions = LocalActions.current
    val running = rememberRunning(server)
    TnCard(title = pool.name, actions = { Pill(pool.status, statusColor(pool.status)) }) {
        val size = pool.size
        val alloc = pool.allocated
        if (size != null && alloc != null && size > 0) {
            Meter("Used", alloc.toDouble() / size, "${formatBytes(alloc)} of ${formatBytes(size)}")
        }
        pool.fragmentation?.let { KeyValue("Fragmentation", "$it%") }
        pool.statusDetail?.takeIf { !pool.healthy }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }

        scan?.let { s ->
            val what = s.function?.lowercase(Locale.ROOT)?.replaceFirstChar { it.uppercase() } ?: "Scan"
            if (s.running) {
                val left = s.totalSecsLeft?.let { secs -> " · ${secs / 3600}h ${(secs % 3600) / 60}m left" } ?: ""
                Text("$what in progress: ${String.format(Locale.ROOT, "%.1f%%", s.percentage ?: 0.0)}$left", style = MaterialTheme.typography.bodyMedium)
                UsageBar((s.percentage ?: 0.0) / 100)
            } else {
                KeyValue("Last ${what.lowercase()}", "${s.state?.lowercase() ?: ""} ${formatAgo(s.ended)}, ${s.errors ?: 0} errors".trim())
            }
        }
        val scrubbing = scan?.running == true && scan.function == "SCRUB"
        val action = if (scrubbing) Actions.scrubStop(pool.name) else Actions.scrubStart(pool.name)
        if (actions.can(action) && !(scan?.running == true && !scrubbing)) {
            OutlinedButton({ actions.launch(action) }, enabled = action.key !in running) {
                Text(if (scrubbing) "Stop scrub" else "Scrub now")
            }
        }

        pool.topology?.let { t ->
            HorizontalDivider(color = LocalPalette.current.lines)
            (t.data.map { "Data" to it } + t.log.map { "Log" to it } + t.cache.map { "Cache" to it } +
                t.special.map { "Special" to it } + t.dedup.map { "Dedup" to it } + t.spare.map { "Spare" to it })
                .forEach { (role, vdev) -> VdevRow(role, vdev) }
        }
        if (disks.isNotEmpty()) {
            HorizontalDivider(color = LocalPalette.current.lines)
            disks.sortedBy { it.name }.forEach { DiskRow(it, temps[it.name]) }
        }
    }
}

@Composable
private fun VdevRow(role: String, vdev: Vdev, depth: Int = 0) {
    val errors = vdev.stats?.let { it.readErrors + it.writeErrors + it.checksumErrors } ?: 0
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (depth == 0) listOfNotNull(role, vdev.type.takeIf { it != "DISK" }, vdev.disk.takeIf { vdev.children.isEmpty() }).joinToString(" · ")
            else vdev.disk ?: vdev.name,
            style = if (depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (errors > 0) Pill("$errors errors", StatusColors.bad)
            vdev.status?.let { Pill(it, statusColor(it)) }
        }
    }
    vdev.children.forEach { VdevRow(role, it, depth + 1) }
}

@Composable
private fun DiskRow(disk: Disk, temp: Double?) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(disk.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(formatBytes(disk.size), temp?.let { String.format(Locale.ROOT, "%.0f°C", it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if ((temp ?: 0.0) >= 55) StatusColors.warn else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            listOfNotNull(disk.model, disk.type, disk.rotationrate?.let { "$it rpm" }, disk.serial).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.muted,
        )
    }
}
