package com.arceusvault.keel.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Dataset
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.Route
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.StatusColors

@Composable
fun DatasetsScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val nav = LocalNavigator.current
    val datasets = rememberPolled(api, 60_000) { api.datasets() }
    LoadableContent(datasets) { list ->
        if (list.isEmpty()) {
            CenteredMessage("No datasets")
            return@LoadableContent
        }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            list.groupBy { it.pool }.forEach { (pool, items) ->
                item(key = pool) {
                    TnCard(title = pool, padded = false) {
                        items.sortedBy { it.name }.forEachIndexed { i, ds ->
                            DatasetRow(ds, last = i == items.lastIndex) { nav.open(Route("dataset", ds.name)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DatasetRow(ds: Dataset, last: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val used = ds.used?.long
    val avail = ds.available?.long
    ListRow(
        title = if (ds.depth == 0) ds.name else ds.shortName,
        modifier = Modifier.padding(start = (ds.depth.coerceAtMost(6) * 14).dp),
        subtitle = listOfNotNull(
            used?.let { "${formatBytes(it)} used" },
            avail?.let { "${formatBytes(it)} free" },
            ds.compressratio?.text?.takeIf { it != "1.00x" && it != "1.00" }?.let { "${it.removeSuffix("x")}x compression" },
        ).joinToString(" · "),
        leading = {
            Icon(if (ds.type == "VOLUME") Icons.Default.ViewInAr else Icons.Default.Folder, null, tint = p.primary, modifier = Modifier.size(20.dp))
        },
        trailing = {
            if (ds.encrypted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ds.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        if (ds.locked) "Locked" else "Encrypted, unlocked",
                        tint = if (ds.locked) StatusColors.warn else StatusColors.ok,
                        modifier = Modifier.size(18.dp),
                    )
                    if (ds.locked) Text(" Locked", color = StatusColors.warn)
                }
            }
        },
        onClick = onClick,
        divider = !last,
    )
}
