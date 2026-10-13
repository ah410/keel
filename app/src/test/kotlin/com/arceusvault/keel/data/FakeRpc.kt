package com.arceusvault.keel.data

import com.arceusvault.keel.core.network.RpcChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Records calls and lets a test push collection_update events. */
class FakeRpc(private val handler: suspend (String, JsonArray) -> JsonElement) : RpcChannel {
    override val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    override val unsubscribed = MutableSharedFlow<JsonObject>(extraBufferCapacity = 8)
    val calls = mutableListOf<Pair<String, JsonArray>>()

    override suspend fun call(method: String, params: JsonArray, timeoutMs: Long): JsonElement {
        calls += method to params
        return when (method) {
            "core.subscribe" -> JsonPrimitive("sub-${calls.size}")
            "core.unsubscribe" -> JsonPrimitive(null as String?)
            else -> handler(method, params)
        }
    }

    suspend fun push(collection: String, msg: String, id: JsonElement, fields: JsonObject? = null) {
        events.emit(buildJsonObject {
            put("msg", msg)
            put("collection", collection)
            put("id", id)
            fields?.let { put("fields", it) }
        })
    }
}
