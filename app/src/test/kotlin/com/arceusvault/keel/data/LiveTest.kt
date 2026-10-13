package com.arceusvault.keel.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTest {
    private fun pool(id: Int, status: String) = buildJsonObject { put("id", id); put("name", "p$id"); put("status", status) }

    @Test
    fun `live query applies added, changed and removed events`() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeRpc { _, _ -> JsonArray(listOf(pool(1, "ONLINE"), pool(2, "ONLINE"))) }
        val seen = mutableListOf<List<JsonObject>>()
        val job = launch { rpc.liveQuery("pool.query").take(4).toList(seen) }

        rpc.push("pool.query", "changed", JsonPrimitive(1), buildJsonObject { put("status", "DEGRADED") })
        rpc.push("pool.query", "added", JsonPrimitive(3), pool(3, "ONLINE"))
        rpc.push("pool.query", "removed", JsonPrimitive(2))
        job.join()

        assertEquals(2, seen[0].size)
        // A partial change merges into the existing item instead of replacing it.
        assertEquals("DEGRADED", seen[1].first()["status"]!!.jsonPrimitive.content)
        assertEquals("p1", seen[1].first()["name"]!!.jsonPrimitive.content)
        assertEquals(3, seen[2].size)
        assertEquals(listOf("p1", "p3"), seen[3].map { it["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `events for other collections are ignored`() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeRpc { _, _ -> JsonArray(listOf(pool(1, "ONLINE"))) }
        val seen = mutableListOf<List<JsonObject>>()
        val job = launch { rpc.liveQuery("pool.query").take(2).toList(seen) }
        rpc.push("app.query", "removed", JsonPrimitive(1))
        rpc.push("pool.query", "added", JsonPrimitive(2), pool(2, "ONLINE"))
        job.join()
        assertEquals(2, seen[1].size)
    }

    @Test
    fun `subscription unsubscribes when collection stops`() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeRpc { _, _ -> JsonArray(emptyList()) }
        val job = launch { rpc.subscribe("alert.list").collect {} }
        job.cancel()
        job.join()
        assertTrue(rpc.calls.any { it.first == "core.subscribe" })
        assertTrue(rpc.calls.any { it.first == "core.unsubscribe" })
    }

    @Test
    fun `parametrised subscriptions match their bare collection name`() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeRpc { _, _ -> JsonArray(emptyList()) }
        val got = mutableListOf<CollectionEvent>()
        val job = launch { rpc.subscribe("reporting.realtime:{\"interval\": 2}").take(1).toList(got) }
        rpc.push("reporting.realtime", "added", JsonPrimitive(null as String?), buildJsonObject { put("x", 1) })
        job.join()
        assertEquals(1, got.size)
    }

    @Test
    fun `refused subscription surfaces as an error`() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeRpc { _, _ -> JsonArray(emptyList()) }
        val result = async { runCatching { rpc.subscribe("app.stats").collect {} } }
        rpc.unsubscribed.emit(buildJsonObject {
            put("collection", "app.stats")
            put("error", buildJsonObject { put("reason", "Not authorized") })
        })
        val e = result.await().exceptionOrNull()
        assertTrue("got $e", e is SubscriptionException)
        assertEquals("Not authorized", e!!.message)
    }
}

class CollectionMatchTest {
    @Test
    fun `parameters are ignored when matching collections`() {
        assertTrue(matches("app.container_log_follow:{\"app_name\": \"a\"}", "app.container_log_follow:{\"app_name\":\"a\"}"))
        assertTrue(matches("reporting.realtime", "reporting.realtime:{\"interval\": 2}"))
        assertTrue(!matches("app.query", "app.stats"))
    }
}
