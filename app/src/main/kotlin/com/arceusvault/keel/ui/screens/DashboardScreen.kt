package com.arceusvault.keel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.arceusvault.keel.data.Alert
import com.arceusvault.keel.data.App
import com.arceusvault.keel.data.Pool
import com.arceusvault.keel.data.Realtime
import com.arceusvault.keel.data.SystemInfo
import com.arceusvault.keel.data.TrueNasApi
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.Route
import com.arceusvault.keel.ui.Tab
import com.arceusvault.keel.ui.components.Donut
import com.arceusvault.keel.ui.components.Gauge
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.Loadable
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.Slice
import com.arceusvault.keel.ui.components.Sparkline
import com.arceusvault.keel.ui.components.StatusDot
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.formatBitRate
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.formatUptime
import com.arceusvault.keel.ui.components.rememberLive
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.components.usageColor
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.StatusColors
import java.util.Locale

private const val HISTORY = 60

@Composable
fun DashboardScreen(padding: PaddingValues) {
    val server = LocalServer.current
    val api = server.api
    val nav = LocalNavigator.current
    val info = rememberPolled(api, 60_000) { api.systemInfo() }
    val pools = rememberLive(api) { api.poolsLive() }
    val alerts = rememberLive(api) { api.alertsLive() }
    val apps = if (server.roles.has("APPS_READ")) rememberLive(api) { api.appsLive() } else null
    val history = rememberRealtimeHistory(api)
    val rt = history.lastOrNull()

    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SystemInfoCard(info, server.profile.displayAddress) }
        alerts.valueOrNull?.let { list -> item { AlertsCard(list.filterNot { it.dismissed }) { nav.go(Tab.ALERTS) } } }
        val reporting = { nav.go(Tab.MORE, Route("reporting")) }
        item { CpuCard(info.valueOrNull, rt, history, reporting) }
        item { MemoryCard(info.valueOrNull, rt, reporting) }
        pools.valueOrNull?.let { list -> items(list, key = { "pool-${it.id}" }) { PoolCard(it) { nav.go(Tab.STORAGE) } } }
        apps?.valueOrNull?.let { list -> item { AppsCard(list) { nav.go(Tab.APPS) } } }
        if (rt != null && rt.interfaces.isNotEmpty()) item { NetworkCard(history, reporting) }
        if (info is Loadable.Error) item { TnCard(title = "Couldn't load") { Text(info.message) } }
    }
}

/** Keeps the last minute or two of realtime samples for sparklines. */
@Composable
private fun rememberRealtimeHistory(api: TrueNasApi): List<Realtime> {
    var history by remember(api) { mutableStateOf(emptyList<Realtime>()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Realtime stats are a nice-to-have; the dashboard works without them.
            runCatching { api.realtime(2).collect { history = (history + it).takeLast(HISTORY) } }
        }
    }
    return history
}

@Composable
private fun SystemInfoCard(info: Loadable<SystemInfo>, address: String) {
    val i = info.valueOrNull
    TnCard(title = "System Information", subtitle = address) {
        if (i == null) {
            Text(if (info is Loadable.Error) info.message else "Loading...", color = StatusColors.muted)
            return@TnCard
        }
        KeyValue("Hostname", i.hostname)
        KeyValue("Version", i.version)
        listOfNotNull(i.systemManufacturer, i.systemProduct).filter { it.isNotBlank() && it != "To be filled by O.E.M." }
            .joinToString(" ").takeIf { it.isNotBlank() }?.let { KeyValue("Platform", it) }
        KeyValue("Uptime", formatUptime(i.uptimeSeconds))
    }
}

@Composable
private fun CpuCard(info: SystemInfo?, rt: Realtime?, history: List<Realtime>, onClick: () -> Unit) {
    val p = LocalPalette.current
    TnCard(title = "CPU", subtitle = info?.model, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Gauge(rt?.cpuPercent?.let { (it / 100).toFloat() }, rt?.cpuPercent?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "...", "usage")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                info?.let {
                    KeyValue("Cores", if (it.physicalCores > 0) "${it.physicalCores} cores, ${it.cores} threads" else "${it.cores} threads")
                    if (it.loadavg.isNotEmpty()) KeyValue("Load", it.loadavg.take(3).joinToString(" ") { v -> String.format(Locale.ROOT, "%.2f", v) })
                }
                rt?.cpuTemp?.let { KeyValue("Temperature", String.format(Locale.ROOT, "%.0f°C", it)) }
            }
        }
        if (rt != null && rt.cores.isNotEmpty()) CoreBars(rt.cores)
        if (history.size > 1) Sparkline(history.map { it.cpuPercent ?: 0.0 }, p.primary, max = 100.0)
    }
}

/** One thin bar per thread, like the web UI's per-core chart. */
@Composable
private fun CoreBars(cores: List<Double>) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        cores.forEach { usage ->
            val f = (usage / 100).toFloat().coerceIn(0f, 1f)
            Box(Modifier.weight(1f).fillMaxHeight().background(p.lines), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(f.coerceAtLeast(0.02f)).background(usageColor(f)))
            }
        }
    }
}

@Composable
private fun MemoryCard(info: SystemInfo?, rt: Realtime?, onClick: () -> Unit) {
    val p = LocalPalette.current
    val total = rt?.memTotal ?: info?.physmem ?: return
    TnCard(title = "Memory", subtitle = formatBytes(total) + if (info?.eccMemory == true) " ECC" else "", onClick = onClick) {
        val arc = rt?.arcSize
        val services = rt?.servicesMemory
        val free = rt?.memAvailable
        if (arc == null || services == null || free == null) {
            Text("Waiting for live data...", color = StatusColors.muted)
            return@TnCard
        }
        Donut(
            listOf(
                Slice("ZFS Cache", arc.toDouble(), p.primary),
                Slice("Services", services.toDouble(), p.orange),
                Slice("Free", free.toDouble(), p.green),
            ),
            center = formatBytes(total - free),
            legendValue = { formatBytes(it.value.toLong()) },
        )
    }
}

@Composable
private fun PoolCard(pool: Pool, onClick: () -> Unit) {
    val p = LocalPalette.current
    TnCard(
        title = pool.name,
        onClick = onClick,
        actions = { Pill(pool.status, statusColor(pool.status)) },
    ) {
        val size = pool.size
        val alloc = pool.allocated
        if (size != null && alloc != null && size > 0) {
            val f = alloc.toDouble() / size
            Donut(
                listOf(Slice("Used", alloc.toDouble(), usageColor(f.toFloat())), Slice("Free", (size - alloc).toDouble(), p.lines)),
                center = String.format(Locale.ROOT, "%.0f%%", f * 100),
                size = 90.dp,
                legendValue = { formatBytes(it.value.toLong()) },
            )
        }
        val disks = pool.topology?.data?.sumOf { if (it.children.isEmpty()) 1 else it.children.size } ?: 0
        val vdevs = pool.topology?.data?.size ?: 0
        if (vdevs > 0) KeyValue("Layout", "$vdevs data vdev${if (vdevs > 1) "s" else ""}, $disks disks")
        pool.scan?.let { s ->
            if (s.running) KeyValue("${s.function?.lowercase()?.replaceFirstChar { it.uppercase() }} running", String.format(Locale.ROOT, "%.1f%%", s.percentage ?: 0.0))
            else KeyValue("Last ${s.function?.lowercase() ?: "scan"}", "${formatAgo(s.ended)}, ${s.errors ?: 0} errors")
        }
        pool.statusDetail?.takeIf { !pool.healthy }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
    }
}

@Composable
private fun AlertsCard(active: List<Alert>, onClick: () -> Unit) {
    val worst = active.map { it.level.uppercase(Locale.ROOT) }.let { levels ->
        listOf("EMERGENCY", "ALERT", "CRITICAL", "ERROR", "WARNING", "NOTICE", "INFO").firstOrNull { it in levels }
    }
    TnCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(if (active.isEmpty()) StatusColors.ok else statusColor(worst))
            Text(
                if (active.isEmpty()) "No active alerts" else "${active.size} active alert${if (active.size > 1) "s" else ""}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        active.sortedBy { levelRank(it.level) }.take(2).forEach {
            Text(it.message, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted, maxLines = 2)
        }
    }
}

// Docker and VM plumbing shows up in realtime too; the web dashboard only lists real NICs.
private fun isPhysical(name: String) = name != "lo" && listOf("veth", "docker", "br-", "vnet", "tap", "kube", "cni", "flannel").none(name::startsWith)

fun levelRank(level: String) = listOf("EMERGENCY", "ALERT", "CRITICAL", "ERROR", "WARNING", "NOTICE", "INFO")
    .indexOf(level.uppercase(Locale.ROOT)).let { if (it < 0) 99 else it }

@Composable
private fun AppsCard(apps: List<App>, onClick: () -> Unit) {
    val running = apps.count { it.state == "RUNNING" }
    val problems = apps.count { it.state == "CRASHED" }
    val updates = apps.count { it.upgradeAvailable || it.imageUpdatesAvailable }
    TnCard(title = "Apps", onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("$running running", StatusColors.ok)
            if (apps.size > running) Pill("${apps.size - running - problems} stopped", StatusColors.muted)
            if (problems > 0) Pill("$problems crashed", StatusColors.bad)
            if (updates > 0) Pill("$updates update${if (updates > 1) "s" else ""}", StatusColors.info)
        }
    }
}

@Composable
private fun NetworkCard(history: List<Realtime>, onClick: () -> Unit) {
    val p = LocalPalette.current
    val latest = history.last().interfaces
    TnCard(title = "Network", onClick = onClick) {
        latest.entries.filter { it.value.linkUp != false && isPhysical(it.key) }.sortedBy { it.key }.forEach { (name, rate) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text("↓ ${formatBitRate(rate.rx)}   ↑ ${formatBitRate(rate.tx)}", style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
                val rx = history.map { it.interfaces[name]?.rx ?: 0.0 }
                val tx = history.map { it.interfaces[name]?.tx ?: 0.0 }
                val peak = (rx + tx).maxOrNull()
                Box {
                    Sparkline(rx, p.primary, max = peak)
                    Sparkline(tx, p.orange, max = peak, fill = false)
                }
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}
