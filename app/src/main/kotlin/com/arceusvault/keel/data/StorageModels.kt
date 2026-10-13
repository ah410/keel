package com.arceusvault.keel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** A ZFS property as pool.dataset.query returns it. */
@Serializable
data class ZfsProp(val parsed: JsonElement? = null, val rawvalue: String? = null, val value: String? = null, val source: String? = null) {
    val long: Long? get() = (parsed as? JsonPrimitive)?.longOrNull ?: rawvalue?.toLongOrNull()
    val text: String? get() = value ?: rawvalue
    val bool: Boolean? get() = (parsed as? JsonPrimitive)?.booleanOrNull
}

@Serializable
data class Dataset(
    val id: String,
    val name: String = id,
    val type: String = "FILESYSTEM",
    val pool: String = "",
    val encrypted: Boolean = false,
    val locked: Boolean = false,
    @SerialName("key_loaded") val keyLoaded: Boolean? = null,
    val used: ZfsProp? = null,
    val available: ZfsProp? = null,
    val usedbysnapshots: ZfsProp? = null,
    val quota: ZfsProp? = null,
    val refquota: ZfsProp? = null,
    val compression: ZfsProp? = null,
    val compressratio: ZfsProp? = null,
    val readonly: ZfsProp? = null,
    val deduplication: ZfsProp? = null,
    val recordsize: ZfsProp? = null,
    val mountpoint: String? = null,
    val volsize: ZfsProp? = null,
) {
    val depth: Int get() = name.count { it == '/' }
    val shortName: String get() = name.substringAfterLast('/')
    val parent: String? get() = name.substringBeforeLast('/', "").ifEmpty { null }
}

@Serializable
data class Snapshot(
    val name: String,
    val pool: String = "",
    val dataset: String = "",
    @SerialName("snapshot_name") val snapshotName: String = "",
    val createtxg: Long = 0,
    val holds: JsonElement? = null,
    val properties: JsonObject? = null,
) {
    private fun prop(k: String) = (properties?.get(k) as? JsonObject)
    private fun propLong(k: String) = prop(k)?.let { (it["value"] as? JsonPrimitive)?.longOrNull ?: (it["raw"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }

    val used: Long? get() = propLong("used")
    val referenced: Long? get() = propLong("referenced")
    // creation comes back as epoch seconds.
    val created: Instant? get() = propLong("creation")?.let(Instant::ofEpochSecond)
    // holds is a list of tags or a tag-to-count map depending on version.
    val held: Boolean get() = (holds as? JsonObject)?.isNotEmpty() ?: (holds as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() ?: false
}

/** Last-run state shared by periodic snapshot and replication tasks. */
data class TaskState(val state: String, val error: String?, val time: Instant?) {
    companion object {
        fun parse(e: JsonElement?): TaskState? {
            val o = e as? JsonObject ?: return null
            val state = (o["state"] as? JsonPrimitive)?.contentOrNull ?: return null
            return TaskState(
                state = state,
                error = (o["error"] as? JsonPrimitive)?.contentOrNull,
                time = parseTime(o["datetime"]),
            )
        }

        /** Cloud sync and rsync report the last job instead. */
        fun fromJob(e: JsonElement?): TaskState? {
            val o = e as? JsonObject ?: return null
            return TaskState(
                state = (o["state"] as? JsonPrimitive)?.contentOrNull ?: return null,
                error = (o["error"] as? JsonPrimitive)?.contentOrNull,
                time = parseTime(o["time_finished"]) ?: parseTime(o["time_started"]),
            )
        }
    }
}

@Serializable
data class CronSchedule(
    val minute: String = "00",
    val hour: String = "*",
    val dom: String = "*",
    val month: String = "*",
    val dow: String = "*",
) {
    override fun toString() = "$minute $hour $dom $month $dow"
}

@Serializable
data class SnapshotTask(
    val id: Long,
    val dataset: String,
    val recursive: Boolean = false,
    @SerialName("lifetime_value") val lifetimeValue: Int = 0,
    @SerialName("lifetime_unit") val lifetimeUnit: String = "",
    val enabled: Boolean = true,
    @SerialName("naming_schema") val namingSchema: String = "",
    val schedule: CronSchedule = CronSchedule(),
    @SerialName("state") val rawState: JsonElement? = null,
) {
    val state: TaskState? get() = TaskState.parse(rawState)
}

@Serializable
data class ReplicationTask(
    val id: Long,
    val name: String,
    val direction: String = "PUSH",
    val transport: String = "SSH",
    @SerialName("source_datasets") val sourceDatasets: List<String> = emptyList(),
    @SerialName("target_dataset") val targetDataset: String = "",
    val enabled: Boolean = true,
    val auto: Boolean = false,
    @SerialName("state") val rawState: JsonElement? = null,
    @SerialName("job") val rawJob: JsonElement? = null,
) {
    val state: TaskState? get() = TaskState.parse(rawState) ?: TaskState.fromJob(rawJob)
}

@Serializable
data class CloudSyncTask(
    val id: Long,
    val description: String = "",
    val path: String = "",
    val direction: String = "PUSH",
    @SerialName("transfer_mode") val transferMode: String = "",
    val enabled: Boolean = true,
    val schedule: CronSchedule = CronSchedule(),
    @SerialName("job") val rawJob: JsonElement? = null,
    val credentials: JsonObject? = null,
) {
    val state: TaskState? get() = TaskState.fromJob(rawJob)
    val provider: String? get() = (credentials?.get("name") as? JsonPrimitive)?.contentOrNull
    val percent: Double?
        get() = ((rawJob as? JsonObject)?.get("progress") as? JsonObject)?.get("percent")?.let { (it as? JsonPrimitive)?.doubleOrNull }
}

@Serializable
data class ScrubTask(
    val id: Long,
    val pool: Long = 0,
    @SerialName("pool_name") val poolName: String = "",
    val threshold: Int = 35,
    val description: String = "",
    val enabled: Boolean = true,
    val schedule: CronSchedule = CronSchedule(),
)
