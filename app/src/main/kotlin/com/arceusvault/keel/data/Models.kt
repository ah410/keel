package com.arceusvault.keel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant

// Shapes follow the TrueNAS API v26.0.0 docs (see docs/api-reference.md). Fields the app doesn't
// show are omitted and ignored on decode, and most are nullable because the schemas allow null.

@Serializable
data class SystemInfo(
    val version: String,
    val hostname: String,
    val physmem: Long = 0,
    val model: String? = null,
    val cores: Int = 0,
    @SerialName("physical_cores") val physicalCores: Int = 0,
    val loadavg: List<Double> = emptyList(),
    @SerialName("uptime_seconds") val uptimeSeconds: Double = 0.0,
    @SerialName("ecc_memory") val eccMemory: Boolean = false,
    @SerialName("system_product") val systemProduct: String? = null,
    @SerialName("system_manufacturer") val systemManufacturer: String? = null,
)

@Serializable
data class Pool(
    val id: Long,
    val name: String,
    val status: String,
    val healthy: Boolean = false,
    val warning: Boolean = false,
    @SerialName("status_detail") val statusDetail: String? = null,
    val size: Long? = null,
    val allocated: Long? = null,
    val free: Long? = null,
    val fragmentation: String? = null,
    val topology: PoolTopology? = null,
    val scan: PoolScan? = null,
)

@Serializable
data class PoolTopology(
    val data: List<Vdev> = emptyList(),
    val log: List<Vdev> = emptyList(),
    val cache: List<Vdev> = emptyList(),
    val spare: List<Vdev> = emptyList(),
    val special: List<Vdev> = emptyList(),
    val dedup: List<Vdev> = emptyList(),
)

@Serializable
data class Vdev(
    val name: String = "",
    val type: String = "",
    val status: String? = null,
    val disk: String? = null,
    val stats: VdevStats? = null,
    val children: List<Vdev> = emptyList(),
)

@Serializable
data class VdevStats(
    @SerialName("read_errors") val readErrors: Long = 0,
    @SerialName("write_errors") val writeErrors: Long = 0,
    @SerialName("checksum_errors") val checksumErrors: Long = 0,
)

@Serializable
data class PoolScan(
    val function: String? = null,
    val state: String? = null,
    val percentage: Double? = null,
    val errors: Long? = null,
    @SerialName("start_time") val startTime: JsonElement? = null,
    @SerialName("end_time") val endTime: JsonElement? = null,
    @SerialName("bytes_to_process") val bytesToProcess: Long? = null,
    @SerialName("bytes_processed") val bytesProcessed: Long? = null,
    @SerialName("total_secs_left") val totalSecsLeft: Long? = null,
) {
    val ended: java.time.Instant? get() = parseTime(endTime)
    val running: Boolean get() = state == "SCANNING"
}

@Serializable
data class Disk(
    val identifier: String,
    val name: String,
    val serial: String? = null,
    val size: Long? = null,
    val model: String? = null,
    val type: String? = null,
    val rotationrate: Int? = null,
    val pool: String? = null,
    val description: String? = null,
)

@Serializable
data class Service(
    val id: Long,
    val service: String,
    val enable: Boolean = false,
    val state: String = "UNKNOWN",
) {
    val displayName: String get() = serviceName(service)
}

/** The names the web UI uses for middleware service ids. */
fun serviceName(id: String): String = when (id) {
    "cifs" -> "SMB"
    "iscsitarget" -> "iSCSI"
    "smartd" -> "S.M.A.R.T."
    "nvmet" -> "NVMe-oF"
    "webshare" -> "WebShare"
    else -> id.uppercase()
}

@Serializable
data class Alert(
    val uuid: String,
    val klass: String = "",
    val level: String = "INFO",
    val text: String = "",
    val formatted: String? = null,
    val datetime: JsonElement? = null,
    val dismissed: Boolean = false,
    val source: String? = null,
) {
    val message: String get() = formatted?.let(::stripHtml) ?: text
    val time: Instant? get() = parseTime(datetime)
}

/** Live CPU, memory and network from the reporting.realtime event. */
data class Realtime(
    val cpuPercent: Double?,
    val memTotal: Long?,
    val memAvailable: Long?,
    val arcSize: Long?,
    val cpuTemp: Double? = null,
    val cores: List<Double> = emptyList(),
    val interfaces: Map<String, NetRate> = emptyMap(),
) {
    /** Memory used by everything except the ZFS cache, the way the web UI's memory widget splits it. */
    val servicesMemory: Long? get() = if (memTotal != null && memAvailable != null) (memTotal - memAvailable - (arcSize ?: 0)).coerceAtLeast(0) else null

    companion object {
        fun parse(fields: JsonObject): Realtime {
            val mem = fields["memory"] as? JsonObject
            val cpu = fields["cpu"] as? JsonObject
            return Realtime(
                cpuPercent = parseCpu(cpu),
                memTotal = mem.long("physical_memory_total"),
                memAvailable = mem.long("physical_memory_available"),
                arcSize = mem.long("arc_size"),
                cpuTemp = (cpu?.get("cpu") as? JsonObject).double("temp"),
                cores = parseCores(cpu),
                interfaces = (fields["interfaces"] as? JsonObject).orEmpty().mapNotNull { (name, v) ->
                    val o = v as? JsonObject ?: return@mapNotNull null
                    name to NetRate(
                        rx = o.double("received_bytes_rate") ?: 0.0,
                        tx = o.double("sent_bytes_rate") ?: 0.0,
                        linkUp = (o["link_state"] as? JsonPrimitive)?.contentOrNull?.let { it == "LINK_STATE_UP" },
                    )
                }.toMap(),
            )
        }

        // The cpu object isn't typed in the schema. The web UI reads cpu.cpu.usage; fall back to averaging cores.
        private fun parseCpu(cpu: JsonObject?): Double? {
            cpu ?: return null
            (cpu["cpu"] as? JsonObject)?.let { agg -> agg.double("usage")?.let { return it } }
            cpu.double("usage")?.let { return it }
            return parseCores(cpu).takeIf { it.isNotEmpty() }?.average()
        }

        // Per-core entries are keyed cpu0, cpu1, ... next to the aggregate "cpu".
        private fun parseCores(cpu: JsonObject?): List<Double> = cpu.orEmpty().entries
            .mapNotNull { (k, v) -> k.removePrefix("cpu").toIntOrNull()?.let { i -> (v as? JsonObject).double("usage")?.let { i to it } } }
            .sortedBy { it.first }
            .map { it.second }

        private fun JsonObject?.long(k: String) = (this?.get(k) as? JsonPrimitive)?.longOrNull
        private fun JsonObject?.double(k: String) = (this?.get(k) as? JsonPrimitive)?.doubleOrNull
    }
}

/** Bytes per second in and out of one interface. */
data class NetRate(val rx: Double, val tx: Double, val linkUp: Boolean?)

/** Middleware returns ISO-8601 strings in the current API, and `{"$date": millis}` in older ones. */
fun parseTime(e: JsonElement?): Instant? = when (e) {
    is JsonPrimitive -> e.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: e.longOrNull?.let(Instant::ofEpochMilli)
    is JsonObject -> (e.jsonObject["\$date"] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochMilli)
    else -> null
}

private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
