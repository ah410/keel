package com.arceusvault.keel.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.Route
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.theme.LocalPalette

private data class Entry(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

// Same names and order as the web UI's sidebar, minus what has its own tab.
private val server = listOf(
    Entry("Datasets", "Space, compression and snapshots", Icons.Default.Inventory2, "datasets"),
    Entry("Shares", "SMB and NFS shares", Icons.Default.FolderShared, "shares"),
    Entry("Data Protection", "Snapshot, replication and cloud sync tasks", Icons.Default.Shield, "protection"),
    Entry("Credentials", "Local users", Icons.Default.Group, "users"),
    Entry("Containers", "LXC containers", Icons.Default.ViewInAr, "containers"),
    Entry("Virtual Machines", "Start and stop VMs", Icons.Default.Computer, "vms"),
    Entry("Reporting", "CPU, memory, network and disk history", Icons.Default.BarChart, "reporting"),
    Entry("System", "Services, updates, power and audit log", Icons.Default.SettingsApplications, "system"),
)

private val app = listOf(
    Entry("Task Manager", "Jobs started from this app", Icons.AutoMirrored.Filled.ListAlt, "jobs"),
    Entry("App Settings", "Theme, lock timeout, permissions, forget server", Icons.Default.Settings, "settings"),
)

@Composable
fun MoreScreen(padding: PaddingValues) {
    val nav = LocalNavigator.current
    val p = LocalPalette.current
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(server, app).forEach { group ->
            item {
                TnCard(padded = false) {
                    group.forEachIndexed { i, e ->
                        ListRow(
                            e.title,
                            subtitle = e.subtitle,
                            leading = { Icon(e.icon, null, tint = p.primary, modifier = Modifier.size(22.dp)) },
                            onClick = { nav.open(Route(e.route)) },
                            divider = i != group.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

