package com.arceusvault.keel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Roles
import com.arceusvault.keel.ui.theme.StatusColors

/** What a key can do, in the terms of the role each feature needs. */
private val capabilities = listOf(
    "View everything" to listOf("READONLY_ADMIN"),
    "Start, stop, configure and upgrade apps" to listOf("APPS_WRITE"),
    "Dismiss alerts" to listOf("ALERT_LIST_WRITE"),
    "Control services" to listOf("SERVICE_WRITE"),
    "Create and roll back snapshots" to listOf("SNAPSHOT_WRITE"),
    "Delete snapshots" to listOf("SNAPSHOT_DELETE"),
    "Run snapshot tasks" to listOf("SNAPSHOT_TASK_WRITE"),
    "Run replication" to listOf("REPLICATION_TASK_WRITE"),
    "Run cloud sync" to listOf("CLOUD_SYNC_WRITE"),
    "Scrub pools" to listOf("POOL_WRITE"),
    "Create, edit and delete shares" to listOf("SHARING_SMB_WRITE", "SHARING_NFS_WRITE"),
    "Install updates" to listOf("SYSTEM_UPDATE_WRITE"),
    "Start and stop VMs" to listOf("VM_WRITE"),
    "Start and stop containers" to listOf("CONTAINER_WRITE"),
    "Reboot and shut down" to listOf("FULL_ADMIN"),
)

/** Check or cross per feature, shown in Settings and before saving a new key. */
@Composable
fun CapabilityList(roles: Roles) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        capabilities.forEach { (label, needs) ->
            val ok = roles.any(*needs.toTypedArray())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (ok) Icons.Default.Check else Icons.Default.Close,
                    null,
                    tint = if (ok) StatusColors.ok else StatusColors.muted,
                    modifier = Modifier.size(18.dp),
                )
                Text(label, style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.onSurface else StatusColors.muted)
            }
        }
    }
}
