package com.arceusvault.keel.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.UsageBar
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.theme.StatusColors
import java.util.Locale

/** Like the web UI's task manager, limited to jobs this app started since it was unlocked. */
@Composable
fun JobsScreen(padding: PaddingValues) {
    val tracker = LocalServer.current.api.jobs
    val jobs = tracker.jobs.collectAsState().value
    if (jobs.isEmpty()) {
        CenteredMessage("No tasks yet", "Long-running actions like app upgrades and scrubs show their progress here.", icon = Icons.AutoMirrored.Filled.ListAlt)
        return
    }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (jobs.any { it.finished }) item { TextButton(tracker::clearFinished) { Text("Clear finished") } }
        items(jobs, key = { it.id }) { j ->
            TnCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(j.state, statusColor(j.state))
                    Text(j.title, style = MaterialTheme.typography.titleSmall)
                }
                if (!j.finished) {
                    UsageBar((j.percent ?: 0.0) / 100)
                    Text(
                        listOfNotNull(j.percent?.let { String.format(Locale.ROOT, "%.0f%%", it) }, j.progress).joinToString(" · ").ifBlank { "Waiting..." },
                        style = MaterialTheme.typography.bodySmall, color = StatusColors.muted,
                    )
                }
                j.error?.let { Text(it.lineSequence().first(), style = MaterialTheme.typography.bodySmall, color = StatusColors.bad) }
                Text("${j.method} · started ${formatAgo(j.started)}", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
            }
        }
    }
}
