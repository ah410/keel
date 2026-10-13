package com.arceusvault.keel.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.arceusvault.keel.data.App
import com.arceusvault.keel.data.AppStats
import com.arceusvault.keel.data.TrueNasApi
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.Route
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.SectionLabel
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.rememberLive
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.StatusColors
import com.arceusvault.keel.ui.theme.contentOn
import java.util.Locale
import kotlin.math.absoluteValue

@Composable
fun AppsScreen(padding: PaddingValues) {
    val server = LocalServer.current
    val api = server.api
    val nav = LocalNavigator.current
    val apps = rememberLive(api) { api.appsLive(server.profile.host) }
    val stats = rememberAppStats(api)

    LoadableContent(apps) { list ->
        if (list.isEmpty()) {
            CenteredMessage("No apps installed", "Apps you install in the TrueNAS web UI show up here.", icon = Icons.Default.Apps)
            return@LoadableContent
        }
        // Problems first, then running, then stopped; alphabetical within each.
        val groups = list.groupBy {
            when (it.state) {
                "CRASHED" -> 0
                "DEPLOYING", "STOPPING" -> 1
                "RUNNING" -> 2
                else -> 3
            }
        }.toSortedMap()
        val updates = list.count { it.upgradeAvailable || it.imageUpdatesAvailable }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("${list.count { it.state == "RUNNING" }} of ${list.size} running", StatusColors.ok)
                    if (updates > 0) Pill("$updates update${if (updates > 1) "s" else ""} available", StatusColors.info)
                }
            }
            groups.forEach { (rank, group) ->
                item(key = "label-$rank") {
                    SectionLabel(listOf("Needs attention", "Changing", "Running", "Stopped")[rank])
                }
                item(key = "group-$rank") {
                    TnCard(padded = false) {
                        group.sortedBy { it.name.lowercase(Locale.ROOT) }.forEachIndexed { i, app ->
                            AppRow(app, stats[app.name], last = i == group.lastIndex) { nav.open(Route("app", app.name)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: App, stats: AppStats?, last: Boolean, onClick: () -> Unit) {
    val usage = stats?.takeIf { app.state == "RUNNING" }?.let { "${String.format(Locale.ROOT, "%.0f%%", it.cpuPercent)} CPU · ${formatBytes(it.memory)}" }
    ListRow(
        title = app.title,
        subtitle = listOfNotNull(app.humanVersion ?: app.version, usage).joinToString(" · "),
        leading = { AppBadge(app.name) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (app.upgradeAvailable || app.imageUpdatesAvailable) Pill("Update", StatusColors.info)
                Pill(app.state, statusColor(app.state))
            }
        },
        onClick = onClick,
        divider = !last,
    )
}

/**
 * Two-letter badge in a color derived from the name. Catalog icons would mean loading images from
 * GitHub, and the app never talks to anything but your server.
 */
@Composable
fun AppBadge(name: String, size: Int = 36) {
    val p = LocalPalette.current
    val colors = listOf(p.blue, p.green, p.orange, p.violet, p.cyan, p.magenta, p.yellow, p.red)
    val c: Color = colors[name.hashCode().absoluteValue % colors.size]
    Box(Modifier.size(size.dp).background(c, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        Text(
            name.filter { it.isLetterOrDigit() }.take(2).uppercase(Locale.ROOT),
            color = contentOn(c),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** Latest per-app CPU/memory/network from the app.stats event. */
@Composable
fun rememberAppStats(api: TrueNasApi): Map<String, AppStats> {
    var stats by remember(api) { mutableStateOf(emptyMap<String, AppStats>()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runCatching { api.appStats(3).collect { stats = stats + it } }
        }
    }
    return stats
}
