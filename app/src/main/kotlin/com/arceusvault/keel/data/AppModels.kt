package com.arceusvault.keel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class App(
    val id: String = "",
    val name: String,
    val state: String = "UNKNOWN",
    @SerialName("upgrade_available") val upgradeAvailable: Boolean = false,
    @SerialName("latest_version") val latestVersion: String? = null,
    @SerialName("image_updates_available") val imageUpdatesAvailable: Boolean = false,
    @SerialName("custom_app") val customApp: Boolean = false,
    @SerialName("human_version") val humanVersion: String? = null,
    val version: String? = null,
    val metadata: JsonObject? = null,
    @SerialName("active_workloads") val workloads: AppWorkloads? = null,
    val notes: String? = null,
    val portals: JsonObject? = null,
) {
    val title: String get() = (metadata?.get("title") as? JsonPrimitive)?.contentOrNull ?: name
    val train: String? get() = (metadata?.get("train") as? JsonPrimitive)?.contentOrNull
    val description: String? get() = (metadata?.get("description") as? JsonPrimitive)?.contentOrNull

    /** Portal name to URL, e.g. "Web UI" to "http://192.168.0.78:30013/". */
    val portalLinks: List<Pair<String, String>>
        get() = portals.orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
}

@Serializable
data class AppWorkloads(
    val containers: Int = 0,
    @SerialName("used_ports") val usedPorts: List<AppPort> = emptyList(),
    @SerialName("container_details") val containerDetails: List<AppContainer> = emptyList(),
    val volumes: List<AppVolume> = emptyList(),
    val images: List<String> = emptyList(),
)

@Serializable
data class AppPort(
    @SerialName("container_port") val containerPort: Int = 0,
    val protocol: String = "tcp",
    @SerialName("host_ports") val hostPorts: List<AppHostPort> = emptyList(),
)

@Serializable
data class AppHostPort(@SerialName("host_port") val hostPort: Int = 0, @SerialName("host_ip") val hostIp: String = "")

@Serializable
data class AppContainer(
    val id: String,
    @SerialName("service_name") val serviceName: String = "",
    val image: String = "",
    val state: String = "",
)

@Serializable
data class AppVolume(val source: String = "", val destination: String = "", val mode: String = "", val type: String = "")

/** One app's live usage from the app.stats event. */
data class AppStats(val cpuPercent: Double, val memory: Long, val rxRate: Long, val txRate: Long) {
    companion object {
        /** Parses the event's fields array into stats by app name. */
        fun parseAll(fields: JsonElement?): Map<String, AppStats> = (fields as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["app_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val nets = (o["networks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            name to AppStats(
                cpuPercent = (o["cpu_usage"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                memory = (o["memory"] as? JsonPrimitive)?.longOrNull ?: 0,
                rxRate = nets.sumOf { (it["rx_bytes"] as? JsonPrimitive)?.longOrNull ?: 0 },
                txRate = nets.sumOf { (it["tx_bytes"] as? JsonPrimitive)?.longOrNull ?: 0 },
            )
        }.toMap()
    }
}

@Serializable
data class AppVersionInfo(
    val version: String,
    @SerialName("human_version") val humanVersion: String = "",
    val changelog: String? = null,
)

@Serializable
data class AppUpgradeSummary(
    @SerialName("latest_version") val latestVersion: String = "",
    @SerialName("latest_human_version") val latestHumanVersion: String = "",
    @SerialName("upgrade_version") val upgradeVersion: String = "",
    @SerialName("upgrade_human_version") val upgradeHumanVersion: String = "",
    @SerialName("available_versions_for_upgrade") val available: List<AppVersionInfo> = emptyList(),
)

/** One line from app.container_log_follow. */
data class LogLine(val text: String, val timestamp: String?)

/** What the settings editor needs. [form] is null for custom (compose) apps and when no schema is available. */
class AppEditData(val name: String, val custom: Boolean, val config: JsonObject, val form: AppForm?)
