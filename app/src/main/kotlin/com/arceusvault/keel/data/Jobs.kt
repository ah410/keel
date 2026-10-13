package com.arceusvault.keel.data

import com.arceusvault.keel.core.network.RpcChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

class JobFailedException(val job: TrackedJob, message: String) : Exception(message)

/** A middleware job started from this app, as last reported by the server. */
data class TrackedJob(
    val id: Long,
    val title: String,
    val method: String,
    val state: String = "WAITING",
    val percent: Double? = null,
    val progress: String? = null,
    val error: String? = null,
    val result: JsonElement? = null,
    val started: Instant = Instant.now(),
) {
    val finished get() = state in TERMINAL
    val succeeded get() = state == "SUCCESS"

    fun merge(fields: JsonObject): TrackedJob {
        val p = fields["progress"] as? JsonObject
        return copy(
            state = fields.str("state") ?: state,
            percent = (p?.get("percent") as? JsonPrimitive)?.doubleOrNull ?: percent,
            progress = (p?.get("description") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: progress,
            error = fields.str("error") ?: error,
            result = fields["result"]?.takeIf { it != JsonNull } ?: result,
        )
    }

    private fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull

    companion object {
        val TERMINAL = setOf("SUCCESS", "FAILED", "ABORTED")
    }
}

/**
 * Starts jobs and follows them through `core.get_jobs` events, like the web UI's task manager.
 * Lives as long as the session, so a job keeps being tracked when the user leaves the screen.
 */
class JobTracker(private val rpc: RpcChannel, private val scope: CoroutineScope) {
    private val _jobs = MutableStateFlow<List<TrackedJob>>(emptyList())
    /** Jobs started this session, newest first. */
    val jobs: StateFlow<List<TrackedJob>> = _jobs

    private var watcher: Job? = null

    /** Calls a job method and suspends until the job finishes. Returns the job's result. */
    suspend fun run(title: String, method: String, params: JsonArray = JsonArray(emptyList())): JsonElement? {
        ensureWatching()
        val id = rpc.call(method, params).jsonPrimitive.longOrNull
            ?: throw IllegalStateException("$method did not return a job id")
        _jobs.value = listOf(TrackedJob(id, title, method)) + _jobs.value
        val done = await(id)
        if (!done.succeeded) throw JobFailedException(done, done.error?.lineSequence()?.firstOrNull() ?: "Job ${done.state.lowercase()}")
        return done.result
    }

    suspend fun abort(id: Long) {
        rpc.call("core.job_abort", buildJsonArray { add(JsonPrimitive(id)) })
    }

    fun clearFinished() {
        _jobs.value = _jobs.value.filterNot { it.finished }
    }

    private suspend fun await(id: Long): TrackedJob {
        refresh(id)
        while (true) {
            // Events normally finish this; the periodic refresh covers one missed while subscribing.
            withTimeoutOrNull(5_000) { _jobs.mapNotNull { list -> list.find { it.id == id && it.finished } }.first() }
                ?.let { return it }
            refresh(id)
        }
    }

    private suspend fun refresh(id: Long) {
        val filter = buildJsonArray { add(buildJsonArray { add(buildJsonArray { add("id"); add("="); add(id) }) }) }
        val found = runCatching { rpc.call("core.get_jobs", filter) as? JsonArray }.getOrNull()
        (found?.firstOrNull() as? JsonObject)?.let { update(id, it) }
    }

    private fun update(id: Long, fields: JsonObject) {
        _jobs.value = _jobs.value.map { if (it.id == id) it.merge(fields) else it }
    }

    private fun ensureWatching() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            runCatching {
                rpc.subscribe("core.get_jobs").collect { e ->
                    val id = e.key?.toLongOrNull() ?: return@collect
                    e.fields?.let { update(id, it) }
                }
            }
        }
    }

    fun close() {
        watcher?.cancel()
    }
}
