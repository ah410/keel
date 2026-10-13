package com.arceusvault.keel.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.components.formatBytes
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.theme.StatusColors

@Composable
fun BootEnvironmentsScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val envs = rememberPolled(api, 300_000) { api.bootEnvironments() }
    LoadableContent(envs) { list ->
        val sorted = list.sortedByDescending { it.createdAt }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TnCard(title = "Boot Environments", subtitle = "Each update installs a new one; older ones let you go back", padded = false) {
                    sorted.forEachIndexed { i, be ->
                        ListRow(
                            be.id,
                            subtitle = "${formatBytes(be.usedBytes)} · created ${formatAgo(be.createdAt)}",
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (be.active) Pill("Now", StatusColors.ok)
                                    if (be.activated) Pill("Next boot", StatusColors.info)
                                    if (be.keep) Pill("Keep", StatusColors.muted)
                                }
                            },
                            divider = i != sorted.lastIndex,
                        )
                    }
                }
            }
        }
    }
}
