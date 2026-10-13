package com.arceusvault.keel.ui.system

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Series
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.LineChart
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LoadableInline
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.theme.LocalPalette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

private data class Graph(val name: String, val identifier: String?, val title: String, val label: String)

// The web UI's reporting pages, in the same order; UPS and others only appear if the server has them.
private val ORDER = listOf("cpu", "cputemp", "load", "memory", "arcsize", "arcrate", "interface", "disk", "disktemp", "upscharge", "upsload")
private val UNITS = listOf("HOUR" to "1h", "DAY" to "1d", "WEEK" to "1w", "MONTH" to "1m")

@Composable
fun ReportingScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    var unit by rememberSaveable { mutableStateOf("HOUR") }
    var category by rememberSaveable { mutableStateOf("cpu") }
    val graphs = rememberPolled(api, 3_600_000) {
        api.reportingGraphs().flatMap { o ->
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: name
            val label = (o["vertical_label"] as? JsonPrimitive)?.contentOrNull ?: ""
            val ids = (o["identifiers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (ids.isNullOrEmpty()) listOf(Graph(name, null, title, label))
            else ids.map { Graph(name, it, title.replace("{identifier}", it), label) }
        }.filter { it.name in ORDER }.sortedBy { ORDER.indexOf(it.name) }
    }

    LoadableContent(graphs) { all ->
        val categories = all.map { it.name }.distinct()
        val shown = all.filter { it.name == category }.ifEmpty { all.filter { it.name == categories.firstOrNull() } }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c -> FilterChip(c == category, { category = c }, label = { Text(categoryLabel(c)) }) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UNITS.forEach { (u, l) -> FilterChip(u == unit, { unit = u }, label = { Text(l) }) }
                }
            }
            items(shown, key = { "${it.name}/${it.identifier}/$unit" }) { GraphCard(it, unit) }
        }
    }
}

@Composable
private fun GraphCard(g: Graph, unit: String) {
    val api = LocalServer.current.api
    val p = LocalPalette.current
    val data = rememberPolled("${g.name}/${g.identifier}/$unit", if (unit == "HOUR") 60_000 else 600_000) {
        api.reportingData(listOf(g.name to g.identifier), unit).firstOrNull()
    }
    TnCard(title = g.title, subtitle = g.label.ifBlank { null }) {
        LoadableInline(data) { s ->
            if (s == null || s.points.isEmpty()) {
                Text("No data for this period")
                return@LoadableInline
            }
            LineChart(
                columns(s),
                listOf(p.primary, p.orange, p.green, p.violet, p.cyan, p.magenta, p.yellow, p.red),
                format = formatterFor(g),
            )
        }
    }
}

/** Splits rows of [time, a, b, ...] into one list per series, thinned to at most ~300 points. */
private fun columns(s: Series): List<Pair<String, List<Double>>> {
    val step = (s.points.size / 300).coerceAtLeast(1)
    val rows = s.points.filterIndexed { i, _ -> i % step == 0 }
    return s.legend.mapIndexedNotNull { col, name ->
        if (col == 0 && name.equals("time", ignoreCase = true)) null
        else name to rows.map { it.getOrElse(col) { Double.NaN } }
    }.take(8)
}

private fun formatterFor(g: Graph): (Double) -> String {
    val label = g.label.lowercase(Locale.ROOT)
    return when {
        g.name in setOf("cputemp", "disktemp") || "celsius" in label -> { v -> String.format(Locale.ROOT, "%.0f°C", v) }
        "%" in label || "percent" in label || g.name == "cpu" -> { v -> String.format(Locale.ROOT, "%.0f%%", v) }
        g.name == "memory" || g.name == "arcsize" || "bytes" in label && "/s" !in label -> { v -> formatBytes(v.toLong()) }
        g.name == "interface" -> { v -> String.format(Locale.ROOT, "%.1f Mb/s", v / 1000) }
        else -> { v -> if (v >= 1000) String.format(Locale.ROOT, "%.1fk", v / 1000) else String.format(Locale.ROOT, "%.1f", v) }
    }
}

private fun categoryLabel(name: String) = when (name) {
    "cpu" -> "CPU"
    "cputemp" -> "CPU temp"
    "load" -> "Load"
    "memory" -> "Memory"
    "arcsize" -> "ARC size"
    "arcrate" -> "ARC hit rate"
    "interface" -> "Network"
    "disk" -> "Disk I/O"
    "disktemp" -> "Disk temp"
    "upscharge" -> "UPS charge"
    "upsload" -> "UPS load"
    else -> name
}
