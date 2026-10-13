package com.arceusvault.keel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

@Serializable
data class UpdateStatus(
    val code: String = "NORMAL",
    val status: UpdateStatusDetail? = null,
    val error: UpdateError? = null,
    @SerialName("update_download_progress") val download: UpdateDownload? = null,
)

@Serializable
data class UpdateStatusDetail(
    @SerialName("current_version") val current: UpdateVersion? = null,
    @SerialName("new_version") val newVersion: UpdateNewVersion? = null,
)

@Serializable
data class UpdateVersion(
    val train: String = "",
    val profile: String = "",
    @SerialName("matches_profile") val matchesProfile: Boolean = true,
)

@Serializable
data class UpdateNewVersion(
    val version: String,
    @SerialName("release_notes") val releaseNotes: String? = null,
    @SerialName("release_notes_url") val releaseNotesUrl: String? = null,
)

@Serializable
data class UpdateError(val errname: String = "", val reason: String = "")

@Serializable
data class UpdateDownload(val percent: Double = 0.0, val description: String = "", val version: String = "")

@Serializable
data class BootEnvironment(
    val id: String,
    val active: Boolean = false,
    val activated: Boolean = false,
    val created: JsonElement? = null,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    val keep: Boolean = false,
) {
    val createdAt: Instant? get() = parseTime(created)
}

@Serializable
data class NetInterface(
    val id: String,
    val name: String = id,
    val type: String = "",
    val description: String = "",
    val mtu: Int? = null,
    val state: JsonObject? = null,
    val aliases: List<NetAlias> = emptyList(),
    @SerialName("ipv4_dhcp") val dhcp: Boolean = false,
) {
    private fun st(k: String) = (state?.get(k) as? JsonPrimitive)?.contentOrNull
    val linkState: String? get() = st("link_state")
    val macAddress: String? get() = st("link_address")
    val activeMedia: String? get() = st("active_media_subtype")?.takeIf { it.isNotBlank() } ?: st("active_media_type")

    /** Addresses actually configured right now (DHCP ones only show up in state.aliases). */
    val addresses: List<String>
        get() {
            val live = (state?.get("aliases") as? JsonArray).orEmpty().mapNotNull { a ->
                val o = a as? JsonObject ?: return@mapNotNull null
                val type = (o["type"] as? JsonPrimitive)?.contentOrNull
                if (type != "INET" && type != "INET6") return@mapNotNull null
                val addr = (o["address"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val mask = (o["netmask"] as? JsonPrimitive)?.contentOrNull
                if (mask != null) "$addr/$mask" else addr
            }
            return live.ifEmpty { aliases.map { a -> a.mask?.let { "${a.address}/$it" } ?: a.address } }
        }
}

@Serializable
data class NetAlias(val type: String = "", val address: String = "", val netmask: JsonElement? = null) {
    val mask: String? get() = (netmask as? JsonPrimitive)?.contentOrNull
}

@Serializable
data class AuditEntry(
    @SerialName("audit_id") val auditId: String? = null,
    @SerialName("message_timestamp") val messageTimestamp: Long = 0,
    val address: String = "",
    val username: String = "",
    val service: String = "",
    val event: String = "",
    @SerialName("event_data") val eventData: JsonElement? = null,
    val success: Boolean = true,
) {
    val time: Instant get() = Instant.ofEpochSecond(messageTimestamp)

    /** The method name for MIDDLEWARE METHOD_CALL events, which is what most entries are. */
    val summary: String
        get() {
            val d = eventData as? JsonObject
            val method = (d?.get("method") as? JsonPrimitive)?.contentOrNull
            val desc = (d?.get("description") as? JsonPrimitive)?.contentOrNull
            return listOfNotNull(desc ?: method, event.takeIf { method == null && desc == null }).firstOrNull() ?: event
        }
}

/** One reporting graph's series, from reporting.netdata_get_data. */
data class Series(val name: String, val identifier: String?, val legend: List<String>, val points: List<DoubleArray>) {
    companion object {
        fun parse(o: JsonObject): Series {
            val legend = (o["legend"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
            val rows = (o["data"] as? JsonArray).orEmpty().mapNotNull { row ->
                (row as? JsonArray)?.map { v -> (v as? JsonPrimitive)?.takeIf { it != JsonNull }?.doubleOrNull ?: Double.NaN }?.toDoubleArray()
            }
            return Series(
                name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                identifier = (o["identifier"] as? JsonPrimitive)?.contentOrNull,
                legend = legend,
                points = rows,
            )
        }
    }
}

@Serializable
data class UserAccount(
    val id: Long,
    val uid: Long = 0,
    val username: String = "",
    @SerialName("full_name") val fullName: String = "",
    val builtin: Boolean = false,
    val locked: Boolean = false,
    val smb: Boolean = false,
    @SerialName("password_disabled") val passwordDisabled: Boolean = false,
    @SerialName("twofactor_auth_configured") val twoFactor: Boolean = false,
    val roles: List<String> = emptyList(),
    @SerialName("api_keys") val apiKeys: List<Long> = emptyList(),
)

@Serializable
data class SmbShare(
    val id: Long,
    val name: String = "",
    val path: String? = null,
    val purpose: String? = null,
    val enabled: Boolean = true,
    val comment: String = "",
    val readonly: Boolean = false,
    val locked: Boolean? = null,
)

@Serializable
data class NfsShare(
    val id: Long,
    val path: String = "",
    val comment: String = "",
    val enabled: Boolean = true,
    val ro: Boolean = false,
    val networks: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    val locked: Boolean? = null,
)

@Serializable
data class Vm(
    val id: Long,
    val name: String,
    val description: String = "",
    val vcpus: Int = 1,
    val cores: Int = 1,
    val threads: Int = 1,
    val memory: Long = 0,
    val autostart: Boolean = false,
    val bootloader: String = "",
    val status: JsonObject? = null,
) {
    val state: String get() = (status?.get("state") as? JsonPrimitive)?.contentOrNull ?: "UNKNOWN"
    val cpuCount: Int get() = vcpus * cores * threads
}

@Serializable
data class LxcContainer(
    val id: Long,
    val name: String,
    val description: String = "",
    val autostart: Boolean = false,
    val dataset: String = "",
    val status: JsonObject? = null,
) {
    val state: String get() = (status?.get("state") as? JsonPrimitive)?.contentOrNull ?: "UNKNOWN"
}

/** container.metrics fields keyed by container name or id; the schema leaves the values untyped. */
data class ContainerMetrics(val cpuPercent: Double?, val memory: Long?) {
    companion object {
        fun parseAll(fields: JsonObject?): Map<String, ContainerMetrics> = fields.orEmpty().mapNotNull { (k, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val cpu = o["cpu"] as? JsonObject
            val mem = o["memory"] as? JsonObject
            k to ContainerMetrics(
                cpuPercent = (cpu?.get("cpu_user_percentage") as? JsonPrimitive)?.doubleOrNull
                    ?: (o["cpu_usage"] as? JsonPrimitive)?.doubleOrNull,
                memory = (mem?.get("mem_usage_ram_mib") as? JsonPrimitive)?.doubleOrNull?.let { (it * 1024 * 1024).toLong() }
                    ?: (o["memory"] as? JsonPrimitive)?.longOrNull,
            )
        }.toMap()
    }
}
