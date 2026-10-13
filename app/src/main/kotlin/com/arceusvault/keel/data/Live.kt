package com.arceusvault.keel.data

import com.arceusvault.keel.core.network.RpcChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The server refused or ended a subscription, typically because the key lacks the event's role. */
class SubscriptionException(val collection: String, message: String) : Exception(message)

/** One `collection_update` message. */
class CollectionEvent(val msg: String, val collection: String, val id: JsonElement?, val data: JsonElement?) {
    /** Most events carry an object; a few (app.stats) send an array, available as [data]. */
    val fields: JsonObject? get() = data as? JsonObject
    val key: String? get() = id.asKey() ?: fields?.get("id").asKey()

    companion object {
        fun parse(o: JsonObject) = CollectionEvent(
            msg = o["msg"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "",
            collection = o["collection"]?.jsonPrimitive?.contentOrNull ?: "",
            id = o["id"],
            data = o["fields"],
        )
    }
}

internal fun JsonElement?.asKey(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> toString()
}

// Dynamic sources report the collection bare or with parameters, which the server may re-serialize
// differently than we sent them, so only the part before the colon is compared.
internal fun matches(collection: String, name: String) =
    collection.substringBefore(':') == name.substringBefore(':')

/**
 * Subscribes to [name] for as long as the flow is collected and unsubscribes when it stops,
 * so leaving a screen doesn't leave the server streaming to us.
 */
fun RpcChannel.subscribe(name: String): Flow<CollectionEvent> = channelFlow {
    // UNDISPATCHED so both collectors are registered before the server can send anything.
    launch(start = CoroutineStart.UNDISPATCHED) {
        events.collect { e -> CollectionEvent.parse(e).takeIf { matches(it.collection, name) }?.let { send(it) } }
    }
    launch(start = CoroutineStart.UNDISPATCHED) {
        unsubscribed.filter { matches(it["collection"]?.jsonPrimitive?.contentOrNull ?: "", name) }.collect {
            val reason = ((it["error"] as? JsonObject)?.get("reason"))?.jsonPrimitive?.contentOrNull
            throw SubscriptionException(name, reason ?: "The server ended the $name subscription")
        }
    }
    val id = call("core.subscribe", buildJsonArray { add(JsonPrimitive(name)) }).jsonPrimitive.contentOrNull
    try {
        awaitCancellation()
    } finally {
        if (id != null) withContext(NonCancellable) {
            runCatching { call("core.unsubscribe", buildJsonArray { add(JsonPrimitive(id)) }, timeoutMs = 5_000) }
        }
    }
}

/**
 * A query kept current by its collection's events. Emits the full list after the initial query
 * and after every change. Falls back to polling every [pollMs] if the subscription is refused.
 */
fun RpcChannel.liveQuery(
    method: String,
    params: JsonArray = JsonArray(emptyList()),
    collection: String = method,
    pollMs: Long = 30_000,
    idOf: (JsonObject) -> String? = { it["id"].asKey() },
): Flow<List<JsonObject>> = channelFlow {
    val pending = Channel<CollectionEvent>(Channel.UNLIMITED)
    var live = true
    // Subscribe first and buffer, so nothing that happens during the initial query is lost.
    val sub = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            subscribe(collection).collect { pending.send(it) }
        } catch (e: SubscriptionException) {
            live = false
            pending.close()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            live = false
            pending.close()
        }
    }
    val items = LinkedHashMap<String, JsonObject>()
    suspend fun reload() {
        items.clear()
        (call(method, params) as? JsonArray)?.forEach { el ->
            (el as? JsonObject)?.let { o -> items[idOf(o) ?: items.size.toString()] = o }
        }
        send(items.values.toList())
    }
    reload()
    fun apply(e: CollectionEvent) {
        val key = e.key ?: return
        when (e.msg) {
            "added" -> e.fields?.let { items[key] = it }
            "changed" -> e.fields?.let { f -> items[key] = items[key]?.let { JsonObject(it + f) } ?: f }
            "removed" -> items.remove(key)
        }
    }
    for (first in pending) {
        apply(first)
        // Coalesce bursts (e.g. many jobs changing at once) into one emission.
        while (true) apply(pending.tryReceive().getOrNull() ?: break)
        send(items.values.toList())
    }
    sub.cancel()
    if (!live) while (true) {
        delay(pollMs)
        reload()
    }
}
