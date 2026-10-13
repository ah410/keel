package com.arceusvault.keel.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.AuditEntry
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date

@Composable
fun AuditScreen(padding: PaddingValues) {
    val server = LocalServer.current
    val api = server.api
    var mine by rememberSaveable { mutableStateOf(false) }
    var failures by rememberSaveable { mutableStateOf(false) }
    val entries = rememberPolled(mine to failures, 60_000) {
        val filters = buildList {
            if (mine) add(JsonArray(listOf(JsonPrimitive("username"), JsonPrimitive("="), JsonPrimitive(server.profile.username))))
            if (failures) add(JsonArray(listOf(JsonPrimitive("success"), JsonPrimitive("="), JsonPrimitive(false))))
        }
        api.audit(150, JsonArray(filters))
    }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mine, { mine = !mine }, label = { Text("This app's user") })
                FilterChip(failures, { failures = !failures }, label = { Text("Failures only") })
            }
        }
        item {
            LoadableContent(entries) { list ->
                if (list.isEmpty()) CenteredMessage("No audit entries")
            }
        }
        entries.valueOrNull?.let { list -> items(list, key = { it.auditId ?: "${it.messageTimestamp}${it.event}${it.username}" }) { AuditRow(it) } }
    }
}

@Composable
private fun AuditRow(e: AuditEntry) {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    TnCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (e.success) "OK" else "FAILED", if (e.success) StatusColors.ok else StatusColors.bad)
            Text(e.username.ifBlank { "(system)" }, style = MaterialTheme.typography.titleSmall)
            Text(formatAgo(e.time), style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
        }
        Text(e.summary, style = MaterialTheme.typography.bodyMedium)
        Text("${fmt.format(Date.from(e.time))} · ${e.address} · ${e.event}", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
    }
}
