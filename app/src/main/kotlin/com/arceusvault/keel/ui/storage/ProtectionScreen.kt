package com.arceusvault.keel.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Action
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.CloudSyncTask
import com.arceusvault.keel.data.CronSchedule
import com.arceusvault.keel.data.ReplicationTask
import com.arceusvault.keel.data.ScrubTask
import com.arceusvault.keel.data.SnapshotTask
import com.arceusvault.keel.data.TaskState
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.Loadable
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.components.rememberRefresher
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.theme.StatusColors

/** Each list loads on its own so one missing role (say, no cloud sync access) doesn't blank the page. */
@Composable
fun ProtectionScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val refresher = rememberRefresher()
    val snapshotTasks = rememberPolled(api, 30_000, refresher) { api.snapshotTasks() }
    val replication = rememberPolled(api, 30_000, refresher) { api.replicationTasks() }
    val cloud = rememberPolled(api, 30_000, refresher) { api.cloudSyncTasks() }
    val scrubs = rememberPolled(api, 120_000, refresher) { api.scrubTasks() }

    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("Periodic Snapshot Tasks", snapshotTasks, "No snapshot tasks") { t: SnapshotTask, last ->
                TaskRow(
                    t.dataset + if (t.recursive) " (recursive)" else "",
                    "${describeCron(t.schedule)} · keep ${t.lifetimeValue} ${t.lifetimeUnit.lowercase()}${if (t.lifetimeValue != 1) "s" else ""}",
                    t.enabled, t.state, Actions.snapshotTaskRun(t.id, t.dataset), last, refresher::refresh,
                )
            }
        }
        item {
            Section("Replication Tasks", replication, "No replication tasks") { t: ReplicationTask, last ->
                TaskRow(
                    t.name,
                    "${t.direction.lowercase()} ${t.sourceDatasets.joinToString()} → ${t.targetDataset}",
                    t.enabled, t.state, Actions.replicationRun(t.id, t.name), last, refresher::refresh,
                )
            }
        }
        item {
            Section("Cloud Sync Tasks", cloud, "No cloud sync tasks") { t: CloudSyncTask, last ->
                TaskRow(
                    t.description.ifBlank { t.path },
                    listOfNotNull(t.direction.lowercase(), t.transferMode.lowercase(), t.provider, describeCron(t.schedule)).joinToString(" · "),
                    t.enabled, t.state, Actions.cloudSyncRun(t.id, t.description.ifBlank { "cloud sync" }), last, refresher::refresh,
                )
            }
        }
        item {
            Section("Scrub Tasks", scrubs, "No scrub tasks") { t: ScrubTask, last ->
                ListRow(
                    t.poolName,
                    subtitle = "${describeCron(t.schedule)} · every ${t.threshold} days at most",
                    trailing = { if (!t.enabled) Pill("Disabled", StatusColors.muted) },
                    divider = !last,
                )
            }
        }
    }
}

@Composable
private fun <T> Section(title: String, state: Loadable<List<T>>, empty: String, row: @Composable (T, Boolean) -> Unit) {
    TnCard(title = title, padded = false) {
        when (state) {
            Loadable.Loading -> Text("Loading...", Modifier.padding(16.dp), color = StatusColors.muted)
            is Loadable.Error -> Text(state.message, Modifier.padding(16.dp), color = StatusColors.muted, style = MaterialTheme.typography.bodySmall)
            is Loadable.Ready -> {
                if (state.value.isEmpty()) Text(empty, Modifier.padding(16.dp), color = StatusColors.muted)
                state.value.forEachIndexed { i, t -> row(t, i == state.value.lastIndex) }
            }
        }
    }
}

@Composable
private fun TaskRow(title: String, detail: String, enabled: Boolean, state: TaskState?, run: Action, last: Boolean, onRan: () -> Unit) {
    val server = LocalServer.current
    val actions = LocalActions.current
    val running = rememberRunning(server)
    ListRow(
        title,
        subtitle = listOfNotNull(
            detail,
            state?.let { s -> "Last run ${formatAgo(s.time)}" + (s.error?.let { ": ${it.lineSequence().first()}" } ?: "") },
        ).joinToString("\n"),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !enabled -> Pill("Disabled", StatusColors.muted)
                    state != null -> Pill(state.state, statusColor(state.state))
                }
                if (actions.can(run)) {
                    TextButton({ actions.launch(run, onRan) }, enabled = run.key !in running && state?.state != "RUNNING") { Text("Run") }
                }
            }
        },
        divider = !last,
    )
}

/** Plain-language summary of the common cron shapes; anything unusual is shown as cron. */
fun describeCron(c: CronSchedule): String {
    val time = if (c.hour.all(Char::isDigit) && c.minute.all(Char::isDigit)) String.format(java.util.Locale.ROOT, "%02d:%02d", c.hour.toInt(), c.minute.toInt()) else null
    val days = mapOf("0" to "Sunday", "7" to "Sunday", "1" to "Monday", "2" to "Tuesday", "3" to "Wednesday", "4" to "Thursday", "5" to "Friday", "6" to "Saturday")
    return when {
        c.minute.all(Char::isDigit) && c.hour == "*" && c.dom == "*" && c.month == "*" && c.dow == "*" -> "Hourly"
        time != null && c.dom == "*" && c.month == "*" && c.dow == "*" -> "Daily at $time"
        time != null && c.dom == "*" && c.month == "*" && c.dow in days -> "Weekly on ${days[c.dow]} at $time"
        time != null && c.dom.all(Char::isDigit) && c.month == "*" && c.dow == "*" -> "Monthly on day ${c.dom} at $time"
        else -> "cron $c"
    }
}
