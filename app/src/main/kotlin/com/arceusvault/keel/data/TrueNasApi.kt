package com.arceusvault.keel.data

import com.arceusvault.keel.core.network.RpcChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Typed TrueNAS calls. Reads need the matching *_READ role (READONLY_ADMIN covers all of them);
 * every write is listed in [Action] with the role it needs. Long-running writes go through [jobs].
 */
class TrueNasApi(val rpc: RpcChannel, private val json: Json, val jobs: JobTracker) {

    // ---- System ----

    suspend fun systemInfo(): SystemInfo = call("system.info", SystemInfo.serializer())

    suspend fun me(): JsonObject = rpc.call("auth.me") as? JsonObject ?: JsonObject(emptyMap())

    /** Live CPU/RAM/network, pushed by the server every [intervalSeconds] (minimum 2). */
    fun realtime(intervalSeconds: Int = 2): Flow<Realtime> {
        // The web UI subscribes with no parameters (2 seconds is the default), so do the same when we can.
        val name = if (intervalSeconds == 2) "reporting.realtime" else "reporting.realtime:{\"interval\": $intervalSeconds}"
        return rpc.subscribe(name).mapNotNull { e -> e.fields?.let(Realtime::parse) }
    }

    suspend fun services(): List<Service> = call("service.query", ListSerializer(Service.serializer()))

    fun servicesLive(): Flow<List<Service>> = live("service.query", Service.serializer())

    suspend fun updateStatus(): UpdateStatus = call("update.status", UpdateStatus.serializer())

    suspend fun bootEnvironments(): List<BootEnvironment> = call("boot.environment.query", ListSerializer(BootEnvironment.serializer()))

    suspend fun interfaces(): List<NetInterface> = call("interface.query", ListSerializer(NetInterface.serializer()))

    suspend fun audit(limit: Int = 100, filters: JsonArray = JsonArray(emptyList())): List<AuditEntry> = call(
        "audit.query",
        ListSerializer(AuditEntry.serializer()),
        params {
            addJsonObject {
                putJsonArray("services") { add("MIDDLEWARE") }
                put("query-filters", filters)
                putJsonObject("query-options") {
                    put("limit", limit)
                    putJsonArray("order_by") { add("-message_timestamp") }
                }
            }
        },
    )

    /** Graph names and instances the server can report on (cpu, interface/eno1, disk/sda, ...). */
    suspend fun reportingGraphs(): List<JsonObject> =
        (rpc.call("reporting.netdata_graphs") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    suspend fun reportingData(graphs: List<Pair<String, String?>>, unit: String): List<Series> {
        val result = rpc.call(
            "reporting.netdata_get_data",
            params {
                addJsonArray { graphs.forEach { (name, id) -> addJsonObject { put("name", name); put("identifier", id) } } }
                addJsonObject { put("unit", unit); put("aggregate", true) }
            },
            timeoutMs = 60_000,
        )
        return (result as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(Series::parse) }
    }

    // ---- Storage ----

    suspend fun pools(): List<Pool> = call("pool.query", ListSerializer(Pool.serializer()))

    fun poolsLive(): Flow<List<Pool>> = live("pool.query", Pool.serializer())

    suspend fun disks(): List<Disk> = call(
        "disk.query",
        ListSerializer(Disk.serializer()),
        params {
            add(JsonArray(emptyList()))
            addJsonObject { putJsonObject("extra") { put("pools", true) } }
        },
    )

    /** Celsius by disk name. Values can be plain numbers or objects depending on version. */
    suspend fun diskTemperatures(names: List<String>): Map<String, Double> {
        if (names.isEmpty()) return emptyMap()
        val result = rpc.call("disk.temperatures", params { add(JsonArray(names.map(::JsonPrimitive))) })
        return (result as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            val t = when (v) {
                is JsonPrimitive -> v.contentOrNull?.toDoubleOrNull()
                is JsonObject -> (v["temperature"] ?: v["temp"])?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                else -> null
            }
            t?.let { k to it }
        }.toMap()
    }

    /** Pool scrub/resilver progress pushed by the server. */
    fun poolScans(): Flow<Pair<String, PoolScan>> = rpc.subscribe("pool.scan").mapNotNull { e ->
        val f = e.fields ?: return@mapNotNull null
        val name = (f["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val scan = (f["scan"] as? JsonObject)?.let { json.decodeFromJsonElement(PoolScan.serializer(), it) } ?: return@mapNotNull null
        name to scan
    }

    suspend fun datasets(): List<Dataset> = call(
        "pool.dataset.query",
        ListSerializer(Dataset.serializer()),
        params {
            add(JsonArray(emptyList()))
            addJsonObject {
                putJsonObject("extra") {
                    put("flat", true)
                    put("retrieve_children", false)
                    put("retrieve_user_props", false)
                    putJsonArray("properties") { DATASET_PROPS.forEach { add(it) } }
                }
                putJsonArray("order_by") { add("name") }
            }
        },
        timeoutMs = 60_000,
    )

    suspend fun snapshots(dataset: String?, recursive: Boolean = false): List<Snapshot> = call(
        "zfs.resource.snapshot.query",
        ListSerializer(Snapshot.serializer()),
        params {
            addJsonObject {
                putJsonArray("paths") { dataset?.let { add(it) } }
                put("recursive", recursive)
                putJsonArray("properties") { add("used"); add("referenced"); add("creation") }
                put("get_holds", true)
            }
        },
        timeoutMs = 60_000,
    ).sortedByDescending { it.createtxg }

    suspend fun snapshotTasks(): List<SnapshotTask> = call("pool.snapshottask.query", ListSerializer(SnapshotTask.serializer()))

    // select keeps credentials (SSH keys, cloud secrets) from ever being sent to the phone.
    suspend fun replicationTasks(): List<ReplicationTask> = call(
        "replication.query", ListSerializer(ReplicationTask.serializer()),
        selectOnly("id", "name", "direction", "transport", "source_datasets", "target_dataset", "enabled", "auto", "state", "job"),
    )

    suspend fun cloudSyncTasks(): List<CloudSyncTask> = call(
        "cloudsync.query", ListSerializer(CloudSyncTask.serializer()),
        selectOnly("id", "description", "path", "direction", "transfer_mode", "enabled", "schedule", "job", "credentials.name"),
    )

    suspend fun scrubTasks(): List<ScrubTask> = call("pool.scrub.query", ListSerializer(ScrubTask.serializer()))

    suspend fun smbShares(): List<SmbShare> = call(
        "sharing.smb.query", ListSerializer(SmbShare.serializer()),
        selectOnly("id", "name", "path", "purpose", "enabled", "comment", "readonly", "locked"),
    )

    suspend fun nfsShares(): List<NfsShare> = call(
        "sharing.nfs.query", ListSerializer(NfsShare.serializer()),
        selectOnly("id", "path", "comment", "enabled", "ro", "networks", "hosts", "locked"),
    )

    /** One share's full record, for editing. Share records hold no secrets. */
    suspend fun smbShare(id: Long): JsonObject = byId("sharing.smb.query", id)

    suspend fun nfsShare(id: Long): JsonObject = byId("sharing.nfs.query", id)

    private suspend fun byId(method: String, id: Long): JsonObject =
        (rpc.call(method, params { addJsonArray { addJsonArray { add("id"); add("="); add(id) } } }) as? JsonArray)
            ?.firstOrNull() as? JsonObject ?: throw NoSuchElementException("Not found")

    /** Subdirectories of [path], for picking a share path. */
    suspend fun listDirs(path: String): List<String> {
        val result = rpc.call(
            "filesystem.listdir",
            params {
                add(path)
                addJsonArray { addJsonArray { add("type"); add("="); add("DIRECTORY") } }
                addJsonObject { putJsonArray("order_by") { add("name") } }
            },
        ) as? JsonArray
        return result.orEmpty().mapNotNull { ((it as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull }
    }

    // Password hashes and SSH keys are left out on purpose.
    suspend fun users(): List<UserAccount> = call(
        "user.query", ListSerializer(UserAccount.serializer()),
        selectOnly("id", "uid", "username", "full_name", "builtin", "locked", "smb", "password_disabled", "twofactor_auth_configured", "roles", "api_keys"),
    )

    // ---- Alerts ----

    suspend fun alerts(): List<Alert> = call("alert.list", ListSerializer(Alert.serializer()))

    fun alertsLive(): Flow<List<Alert>> = live("alert.list", Alert.serializer(), idOf = { (it["uuid"] as? JsonPrimitive)?.contentOrNull })

    // ---- Apps ----

    /** [hostIp] replaces a wildcard bind address in portal URLs, so links point at the server we use. */
    fun appsLive(hostIp: String? = null): Flow<List<App>> = live(
        "app.query", App.serializer(),
        params = params {
            add(JsonArray(emptyList()))
            addJsonObject { putJsonObject("extra") { hostIp?.let { put("host_ip", it) } } }
        },
        idOf = { (it["name"] as? JsonPrimitive)?.contentOrNull },
    )

    suspend fun app(name: String): App = call(
        "app.query",
        ListSerializer(App.serializer()),
        params { addJsonArray { addJsonArray { add("name"); add("="); add(name) } } },
    ).firstOrNull() ?: throw NoSuchElementException("App $name not found")

    /**
     * The app with its full settings and form schema, for editing. The settings include secrets
     * (passwords, tokens), so this is only loaded on the edit screen and never cached.
     */
    suspend fun appForEdit(name: String): AppEditData {
        val result = rpc.call(
            "app.query",
            params {
                addJsonArray { addJsonArray { add("name"); add("="); add(name) } }
                addJsonObject { putJsonObject("extra") { put("retrieve_config", true); put("include_app_schema", true) } }
            },
        ) as? JsonArray
        val app = result?.firstOrNull() as? JsonObject ?: throw NoSuchElementException("App $name not found")
        val custom = (app["custom_app"] as? JsonPrimitive)?.contentOrNull == "true"
        val config = app["config"] as? JsonObject ?: JsonObject(emptyMap())
        var schema = ((app["version_details"] as? JsonObject)?.get("schema") as? JsonObject)
        if (schema == null && !custom) schema = catalogSchema(app)
        return AppEditData(name, custom, config, schema?.let(AppForm::parse))
    }

    // Older servers leave the schema out of app.query; the catalog has it per version.
    private suspend fun catalogSchema(app: JsonObject): JsonObject? = runCatching {
        val meta = app["metadata"] as? JsonObject
        val catalogName = (meta?.get("name") as? JsonPrimitive)?.contentOrNull ?: return null
        val train = (meta["train"] as? JsonPrimitive)?.contentOrNull ?: return null
        val version = (app["version"] as? JsonPrimitive)?.contentOrNull ?: return null
        val details = rpc.call("catalog.get_app_details", params { add(catalogName); addJsonObject { put("train", train) } }) as? JsonObject
        (((details?.get("versions") as? JsonObject)?.get(version) as? JsonObject)?.get("schema") as? JsonObject)
    }.getOrNull()

    fun appStats(intervalSeconds: Int = 2): Flow<Map<String, AppStats>> =
        rpc.subscribe("app.stats:{\"interval\": $intervalSeconds}").map { e -> AppStats.parseAll(e.data) }

    suspend fun appContainers(name: String): List<AppContainer> {
        val result = rpc.call("app.container_ids", params { add(name); addJsonObject { put("alive_only", false) } })
        return (result as? JsonObject).orEmpty().values.mapNotNull { v ->
            runCatching { json.decodeFromJsonElement(AppContainer.serializer(), v) }.getOrNull()
        }
    }

    fun appLogs(app: String, containerId: String, tail: Int = 500): Flow<LogLine> {
        val args = buildJsonObject { put("app_name", app); put("container_id", containerId); put("tail_lines", tail) }
        return rpc.subscribe("app.container_log_follow:$args").mapNotNull { e ->
            val f = e.fields ?: return@mapNotNull null
            LogLine((f["data"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null, (f["timestamp"] as? JsonPrimitive)?.contentOrNull)
        }
    }

    suspend fun upgradeSummary(name: String): AppUpgradeSummary =
        call("app.upgrade_summary", AppUpgradeSummary.serializer(), params { add(name) })

    suspend fun rollbackVersions(name: String): List<String> =
        call("app.rollback_versions", ListSerializer(String.serializer()), params { add(name) })

    // ---- Virtualization ----

    suspend fun vms(): List<Vm> = call(
        "vm.query", ListSerializer(Vm.serializer()),
        selectOnly("id", "name", "description", "vcpus", "cores", "threads", "memory", "autostart", "bootloader", "status"),
    )

    fun vmsLive(): Flow<List<Vm>> = live(
        "vm.query", Vm.serializer(),
        params = selectOnly("id", "name", "description", "vcpus", "cores", "threads", "memory", "autostart", "bootloader", "status"),
    )

    suspend fun containers(): List<LxcContainer> = call(
        "container.query", ListSerializer(LxcContainer.serializer()),
        selectOnly("id", "name", "description", "autostart", "dataset", "status"),
    )

    fun containerMetrics(): Flow<Map<String, ContainerMetrics>> =
        rpc.subscribe("container.metrics").map { ContainerMetrics.parseAll(it.fields) }

    // ---- Writes ----

    /** Runs [action]; job methods are followed by the job tracker until they finish. */
    suspend fun perform(action: Action): JsonElement? =
        if (action.isJob) jobs.run(action.title, action.method, action.params) else rpc.call(action.method, action.params, timeoutMs = 120_000)

    // ---- Helpers ----

    private fun <T> live(
        method: String,
        item: KSerializer<T>,
        params: JsonArray = JsonArray(emptyList()),
        idOf: (JsonObject) -> String? = { it["id"].asKey() },
    ): Flow<List<T>> = rpc.liveQuery(method, params, idOf = idOf).map { list ->
        list.mapNotNull { runCatching { json.decodeFromJsonElement(item, it) }.getOrNull() }
    }

    private suspend fun <T> call(
        method: String,
        de: DeserializationStrategy<T>,
        params: JsonArray = JsonArray(emptyList()),
        timeoutMs: Long = 30_000,
    ): T = json.decodeFromJsonElement(de, rpc.call(method, params, timeoutMs))

    companion object {
        private val DATASET_PROPS = listOf(
            "used", "available", "usedbysnapshots", "quota", "refquota", "compression", "compressratio",
            "readonly", "deduplication", "recordsize", "mountpoint", "volsize",
        )

        fun params(block: JsonArrayBuilder.() -> Unit): JsonArray = buildJsonArray(block)

        fun selectOnly(vararg fields: String): JsonArray = params {
            add(JsonArray(emptyList()))
            addJsonObject { putJsonArray("select") { fields.forEach { add(it) } } }
        }

        fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
    }
}
