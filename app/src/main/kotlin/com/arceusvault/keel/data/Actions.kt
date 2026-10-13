package com.arceusvault.keel.data

import com.arceusvault.keel.data.TrueNasApi.Companion.params
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put

/** How much confirmation an action needs, scaled to the damage it can do. */
enum class Tier {
    /** Just do it. */
    BENIGN,
    /** A confirmation dialog. */
    DISRUPTIVE,
    /** Dialog plus a fresh biometric or device PIN prompt. */
    DESTRUCTIVE,
    /** Type a name to confirm, plus a fresh device prompt. */
    CRITICAL,
}

/** One server write. [roles] are alternatives: holding any one is enough. */
data class Action(
    val title: String,
    val method: String,
    val params: JsonArray,
    val roles: List<String>,
    val tier: Tier,
    val isJob: Boolean = false,
    val explanation: String? = null,
    val confirmLabel: String = "Confirm",
    /** For [Tier.CRITICAL]: the text the user has to type. */
    val typeToConfirm: String? = null,
    val done: String = "Done",
) {
    fun allowedFor(roles: Roles) = roles.any(*this.roles.toTypedArray())

    /** Identifies this exact call, so the same button can't fire it twice while it runs. */
    val key: String get() = "$method $params"
}

/** Every write the app can make, in one place so the role and tier of each is easy to audit. */
object Actions {
    // Apps
    fun appStart(app: String) = Action(
        "Start $app", "app.start", params { add(app) }, listOf("APPS_WRITE"), Tier.DISRUPTIVE, isJob = true,
        confirmLabel = "Start", done = "$app started",
    )

    fun appStop(app: String) = Action(
        "Stop $app", "app.stop", params { add(app) }, listOf("APPS_WRITE"), Tier.DISRUPTIVE, isJob = true,
        explanation = "Anything using $app loses access until it's started again.", confirmLabel = "Stop", done = "$app stopped",
    )

    fun appRedeploy(app: String) = Action(
        "Redeploy $app", "app.redeploy", params { add(app) }, listOf("APPS_WRITE"), Tier.DISRUPTIVE, isJob = true,
        explanation = "Stops $app, pulls its images again and starts it. It will be briefly unavailable.",
        confirmLabel = "Redeploy", done = "$app redeployed",
    )

    fun appUpgrade(app: String, version: String, snapshotHostPaths: Boolean) = Action(
        "Upgrade $app", "app.upgrade",
        params {
            add(app)
            addJsonObject { put("app_version", version); put("snapshot_hostpaths", snapshotHostPaths) }
        },
        listOf("APPS_WRITE"), Tier.DESTRUCTIVE, isJob = true,
        explanation = "Upgrades $app to $version. Upgrades can migrate data; you can roll back afterwards, but data changes may not be undone.",
        confirmLabel = "Upgrade", done = "$app upgraded",
    )

    /** [values] is the whole new configuration; [changes] names what differs, for the dialog. */
    fun appUpdate(app: String, values: JsonObject, changes: List<String>) = Action(
        "Update $app settings", "app.update",
        params { add(app); addJsonObject { put("values", values) } },
        listOf("APPS_WRITE"), Tier.DESTRUCTIVE, isJob = true,
        explanation = changeSummary(changes) + "\n\n$app is redeployed with the new settings and will be briefly unavailable.",
        confirmLabel = "Save", done = "$app settings saved",
    )

    fun appUpdateCompose(app: String, compose: JsonObject) = Action(
        "Update $app compose file", "app.update",
        params { add(app); addJsonObject { put("custom_compose_config", compose) } },
        listOf("APPS_WRITE"), Tier.DESTRUCTIVE, isJob = true,
        explanation = "$app is redeployed from the edited compose file and will be briefly unavailable.",
        confirmLabel = "Save", done = "$app compose file saved",
    )

    private fun changeSummary(changes: List<String>) = when {
        changes.isEmpty() -> "No visible settings changed."
        changes.size <= 8 -> "Changed:\n" + changes.joinToString("\n") { "• $it" }
        else -> "Changed:\n" + changes.take(8).joinToString("\n") { "• $it" } + "\n• and ${changes.size - 8} more"
    }

    fun appRollback(app: String, version: String) = Action(
        "Roll back $app", "app.rollback",
        params {
            add(app)
            addJsonObject { put("app_version", version); put("rollback_snapshot", true) }
        },
        listOf("APPS_WRITE"), Tier.DESTRUCTIVE, isJob = true,
        explanation = "Rolls $app back to $version, restoring its snapshot from before the upgrade.",
        confirmLabel = "Roll back", done = "$app rolled back",
    )

    // Alerts
    fun alertDismiss(uuid: String) = Action(
        "Dismiss alert", "alert.dismiss", params { add(uuid) }, listOf("ALERT_LIST_WRITE"), Tier.BENIGN, done = "Alert dismissed",
    )

    fun alertRestore(uuid: String) = Action(
        "Restore alert", "alert.restore", params { add(uuid) }, listOf("ALERT_LIST_WRITE"), Tier.BENIGN, done = "Alert restored",
    )

    // Services
    private val serviceRoles = listOf(
        "SERVICE_WRITE", "SHARING_FTP_WRITE", "SHARING_ISCSI_WRITE", "SHARING_NFS_WRITE", "SHARING_NVME_TARGET_WRITE", "SHARING_SMB_WRITE",
    )

    fun serviceControl(service: String, verb: String) = Action(
        "${verb.lowercase().replaceFirstChar { it.uppercase() }} ${serviceName(service)}", "service.control",
        params {
            add(verb)
            add(service)
            addJsonObject { put("silent", false) }
        },
        serviceRoles, Tier.DISRUPTIVE, isJob = true,
        explanation = if (verb == "STOP") "Clients using ${serviceName(service)} will be disconnected." else null,
        confirmLabel = verb.lowercase().replaceFirstChar { it.uppercase() },
        done = "${serviceName(service)} " + when (verb) { "STOP" -> "stopped"; "START" -> "started"; else -> verb.lowercase() + "ed" },
    )

    fun serviceSetEnabled(service: String, enable: Boolean) = Action(
        if (enable) "Start ${serviceName(service)} on boot" else "Don't start ${serviceName(service)} on boot",
        "service.update",
        params {
            add(service)
            addJsonObject { put("enable", enable) }
        },
        serviceRoles, Tier.DISRUPTIVE, confirmLabel = if (enable) "Enable" else "Disable",
        done = "${serviceName(service)} will ${if (enable) "" else "not "}start on boot",
    )

    // Storage and data protection
    fun snapshotCreate(dataset: String, name: String, recursive: Boolean) = Action(
        "Snapshot $dataset", "zfs.resource.snapshot.create",
        params {
            addJsonObject { put("dataset", dataset); put("name", name); put("recursive", recursive) }
        },
        listOf("SNAPSHOT_WRITE"), Tier.BENIGN, done = "Created $dataset@$name",
    )

    fun snapshotDestroy(snapshot: String) = Action(
        "Delete snapshot", "zfs.resource.snapshot.destroy", params { addJsonObject { put("path", snapshot) } },
        listOf("SNAPSHOT_DELETE"), Tier.DESTRUCTIVE,
        explanation = "$snapshot will be permanently deleted. Files that only exist in this snapshot can't be recovered.",
        confirmLabel = "Delete", done = "Snapshot deleted",
    )

    // Rolling back throws away everything written since; that's worse than deleting one snapshot.
    fun snapshotRollback(snapshot: String) = Action(
        "Roll back dataset", "zfs.resource.snapshot.rollback", params { addJsonObject { put("path", snapshot) } },
        listOf("SNAPSHOT_WRITE"), Tier.CRITICAL,
        explanation = "${snapshot.substringBefore('@')} will be reverted to ${snapshot.substringAfter('@')}. " +
            "Everything written to it since then is permanently lost.",
        confirmLabel = "Roll back", typeToConfirm = snapshot.substringBefore('@'), done = "Rolled back to ${snapshot.substringAfter('@')}",
    )

    fun snapshotTaskRun(id: Long, dataset: String) = Action(
        "Run snapshot task", "pool.snapshottask.run", params { add(id) }, listOf("SNAPSHOT_TASK_WRITE"), Tier.DISRUPTIVE,
        explanation = "Takes a snapshot of $dataset now, using the task's naming and retention.",
        confirmLabel = "Run now", done = "Snapshot task started",
    )

    fun replicationRun(id: Long, name: String) = Action(
        "Run $name", "replication.run", params { add(id) }, listOf("REPLICATION_TASK_WRITE"), Tier.DISRUPTIVE, isJob = true,
        explanation = "Starts replication now instead of waiting for its schedule.", confirmLabel = "Run now", done = "$name finished",
    )

    fun cloudSyncRun(id: Long, name: String) = Action(
        "Run $name", "cloudsync.sync", params { add(id); addJsonObject { put("dry_run", false) } },
        listOf("CLOUD_SYNC_WRITE"), Tier.DISRUPTIVE, isJob = true,
        explanation = "Starts the cloud sync now. Depending on the task's mode this can delete files at the destination.",
        confirmLabel = "Run now", done = "$name finished",
    )

    fun scrubStart(pool: String) = Action(
        "Scrub $pool", "pool.scrub.scrub", params { add(pool); add("START") }, listOf("POOL_WRITE"),
        Tier.DISRUPTIVE, isJob = true,
        explanation = "Reads and verifies every block in $pool. Disk activity will be high until it finishes, which can take hours.",
        confirmLabel = "Start scrub", done = "Scrub of $pool finished",
    )

    fun scrubStop(pool: String) = Action(
        "Stop scrub", "pool.scrub.scrub", params { add(pool); add("STOP") }, listOf("POOL_WRITE"),
        Tier.DISRUPTIVE, isJob = true, confirmLabel = "Stop scrub", done = "Scrub stopped",
    )

    fun shareSmbEnabled(id: Long, name: String, enabled: Boolean) = Action(
        "${if (enabled) "Enable" else "Disable"} $name", "sharing.smb.update",
        params { add(id); addJsonObject { put("enabled", enabled) } },
        listOf("SHARING_SMB_WRITE"), Tier.DISRUPTIVE,
        explanation = if (!enabled) "Clients connected to $name will lose access." else null,
        confirmLabel = if (enabled) "Enable" else "Disable", done = "$name ${if (enabled) "enabled" else "disabled"}",
    )

    fun shareSmbCreate(data: JsonObject) = Action(
        "Create SMB share ${data.text("name")}", "sharing.smb.create", params { add(data) },
        listOf("SHARING_SMB_WRITE"), Tier.DISRUPTIVE,
        explanation = "Shares ${data.text("path")} over SMB. Who can get in is decided by the dataset's permissions.",
        confirmLabel = "Create", done = "Share ${data.text("name")} created",
    )

    fun shareSmbUpdate(id: Long, name: String, changes: JsonObject) = Action(
        "Save SMB share $name", "sharing.smb.update", params { add(id); add(changes) },
        listOf("SHARING_SMB_WRITE"), Tier.DISRUPTIVE,
        explanation = "Connected clients may need to reconnect.", confirmLabel = "Save", done = "Share $name saved",
    )

    fun shareSmbDelete(id: Long, name: String) = Action(
        "Delete SMB share $name", "sharing.smb.delete", params { add(id) },
        listOf("SHARING_SMB_WRITE"), Tier.DESTRUCTIVE,
        explanation = "Clients lose access to $name. The files stay on the server; only the share is removed.",
        confirmLabel = "Delete", done = "Share $name deleted",
    )

    fun shareNfsCreate(data: JsonObject) = Action(
        "Create NFS share ${data.text("path")}", "sharing.nfs.create", params { add(data) },
        listOf("SHARING_NFS_WRITE"), Tier.DISRUPTIVE,
        explanation = "Exports ${data.text("path")} over NFS to the networks and hosts you listed, or to everyone if both are empty.",
        confirmLabel = "Create", done = "Export ${data.text("path")} created",
    )

    fun shareNfsUpdate(id: Long, path: String, changes: JsonObject) = Action(
        "Save NFS share $path", "sharing.nfs.update", params { add(id); add(changes) },
        listOf("SHARING_NFS_WRITE"), Tier.DISRUPTIVE,
        explanation = "Clients may need to remount.", confirmLabel = "Save", done = "Export $path saved",
    )

    fun shareNfsDelete(id: Long, path: String) = Action(
        "Delete NFS share $path", "sharing.nfs.delete", params { add(id) },
        listOf("SHARING_NFS_WRITE"), Tier.DESTRUCTIVE,
        explanation = "Clients mounting $path lose access. The files stay on the server; only the export is removed.",
        confirmLabel = "Delete", done = "Export $path deleted",
    )

    private fun JsonObject.text(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    fun shareNfsEnabled(id: Long, path: String, enabled: Boolean) = Action(
        "${if (enabled) "Enable" else "Disable"} $path", "sharing.nfs.update",
        params { add(id); addJsonObject { put("enabled", enabled) } },
        listOf("SHARING_NFS_WRITE"), Tier.DISRUPTIVE,
        explanation = if (!enabled) "Clients mounting $path will lose access." else null,
        confirmLabel = if (enabled) "Enable" else "Disable", done = "$path ${if (enabled) "enabled" else "disabled"}",
    )

    // System
    fun updateApply(version: String?, hostname: String) = Action(
        "Apply update", "update.run",
        params {
            addJsonObject {
                put("version", version)
                put("reboot", false)
            }
        },
        listOf("SYSTEM_UPDATE_WRITE"), Tier.DESTRUCTIVE, isJob = true,
        explanation = "Downloads and installs ${version ?: "the update"} into a new boot environment on $hostname. " +
            "It takes effect after the next reboot, and the previous version stays available as a boot environment.",
        confirmLabel = "Install", done = "Update installed. Reboot to finish.",
    )

    fun reboot(hostname: String) = Action(
        "Reboot $hostname", "system.reboot", params { add("Requested from Keel") },
        listOf("FULL_ADMIN"), Tier.CRITICAL, isJob = true,
        explanation = "Every app, share, VM and connection on $hostname goes down until it comes back up.",
        confirmLabel = "Reboot", typeToConfirm = hostname, done = "Rebooting",
    )

    fun shutdown(hostname: String) = Action(
        "Shut down $hostname", "system.shutdown", params { add("Requested from Keel") },
        listOf("FULL_ADMIN"), Tier.CRITICAL, isJob = true,
        explanation = "$hostname powers off and stays off. You'll need physical access or IPMI to turn it back on.",
        confirmLabel = "Shut down", typeToConfirm = hostname, done = "Shutting down",
    )

    // Virtualization
    fun vmStart(id: Long, name: String) = Action(
        "Start $name", "vm.start", params { add(id) }, listOf("VM_WRITE"), Tier.DISRUPTIVE, confirmLabel = "Start", done = "$name started",
    )

    fun vmStop(id: Long, name: String) = Action(
        "Stop $name", "vm.stop", params { add(id); addJsonObject { put("force_after_timeout", false) } },
        listOf("VM_WRITE"), Tier.DISRUPTIVE, isJob = true,
        explanation = "Sends $name a shutdown request and waits for the guest to power off.", confirmLabel = "Stop", done = "$name stopped",
    )

    fun vmPowerOff(id: Long, name: String) = Action(
        "Power off $name", "vm.poweroff", params { add(id) }, listOf("VM_WRITE"), Tier.DESTRUCTIVE,
        explanation = "Cuts power to $name immediately, like pulling the plug. Unsaved data in the guest is lost.",
        confirmLabel = "Power off", done = "$name powered off",
    )

    fun containerStart(id: Long, name: String) = Action(
        "Start $name", "container.start", params { add(id) }, listOf("CONTAINER_WRITE"), Tier.DISRUPTIVE,
        confirmLabel = "Start", done = "$name started",
    )

    fun containerStop(id: Long, name: String) = Action(
        "Stop $name", "container.stop", params { add(id); addJsonObject { put("force_after_timeout", true) } },
        listOf("CONTAINER_WRITE"), Tier.DISRUPTIVE, isJob = true, confirmLabel = "Stop", done = "$name stopped",
    )
}
