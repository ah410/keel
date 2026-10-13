package com.arceusvault.keel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.Alert
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.SectionLabel
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.rememberLive
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.components.statusColor
import com.arceusvault.keel.ui.theme.StatusColors
import java.text.DateFormat
import java.util.Date

@Composable
fun AlertsScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val alerts = rememberLive(api) { api.alertsLive() }
    LoadableContent(alerts) { list ->
        if (list.isEmpty()) {
            CenteredMessage("No alerts", "Everything looks fine.", icon = Icons.Default.CheckCircle)
            return@LoadableContent
        }
        val (dismissed, active) = list.partition { it.dismissed }
        val order = compareBy<Alert>({ levelRank(it.level) }, { -(it.time?.toEpochMilli() ?: 0) })
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active.isEmpty()) item { TnCard { Text("No active alerts", style = MaterialTheme.typography.titleSmall) } }
            items(active.sortedWith(order), key = { it.uuid }) { AlertCard(it) }
            if (dismissed.isNotEmpty()) {
                item { SectionLabel("Dismissed") }
                items(dismissed.sortedWith(order), key = { it.uuid }) { AlertCard(it) }
            }
        }
    }
}

@Composable
private fun AlertCard(a: Alert) {
    val server = LocalServer.current
    val actions = LocalActions.current
    val running = rememberRunning(server)
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val action = if (a.dismissed) Actions.alertRestore(a.uuid) else Actions.alertDismiss(a.uuid)
    TnCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(a.level, if (a.dismissed) StatusColors.muted else statusColor(a.level))
            a.time?.let {
                Text(
                    "${fmt.format(Date.from(it))} (${formatAgo(it)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.muted,
                    modifier = Modifier.weight(1f),
                )
            } ?: Spacer(Modifier.weight(1f))
            if (actions.can(action)) {
                TextButton({ actions.launch(action) }, enabled = action.key !in running, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (a.dismissed) "Restore" else "Dismiss")
                }
            }
        }
        Text(a.message, style = MaterialTheme.typography.bodyMedium, color = if (a.dismissed) StatusColors.muted else MaterialTheme.colorScheme.onSurface)
    }
}
