package com.arceusvault.keel.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobsTest {
    @Test
    fun `job result arrives through core get_jobs events`() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeRpc { method, _ -> if (method == "app.stop") JsonPrimitive(42) else JsonArray(emptyList()) }
        val tracker = JobTracker(rpc, backgroundScope)
        val result = async { tracker.run("Stop plex", "app.stop", JsonArray(listOf(JsonPrimitive("plex")))) }

        rpc.push("core.get_jobs", "changed", JsonPrimitive(42), buildJsonObject {
            put("state", "RUNNING")
            put("progress", buildJsonObject { put("percent", 50); put("description", "Stopping") })
        })
        assertEquals(50.0, tracker.jobs.value.single().percent!!, 0.0)
        assertEquals("Stopping", tracker.jobs.value.single().progress)

        rpc.push("core.get_jobs", "changed", JsonPrimitive(42), buildJsonObject { put("state", "SUCCESS"); put("result", "ok") })
        assertEquals(JsonPrimitive("ok"), result.await())
        assertTrue(tracker.jobs.value.single().finished)
    }

    @Test
    fun `failed job throws with the server's error`() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeRpc { method, _ -> if (method == "app.upgrade") JsonPrimitive(7) else JsonArray(emptyList()) }
        val tracker = JobTracker(rpc, backgroundScope)
        val result = async { runCatching { tracker.run("Upgrade", "app.upgrade") } }
        rpc.push("core.get_jobs", "changed", JsonPrimitive(7), buildJsonObject {
            put("state", "FAILED")
            put("error", "[EFAULT] Image pull failed\nTraceback...")
        })
        val e = result.await().exceptionOrNull()
        if (e !is JobFailedException) fail("expected JobFailedException, got $e")
        assertEquals("[EFAULT] Image pull failed", e!!.message)
    }

    @Test
    fun `job that finished before events is picked up by the catch-up query`() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeRpc { method, _ ->
            when (method) {
                "replication.run" -> JsonPrimitive(9)
                "core.get_jobs" -> JsonArray(listOf(buildJsonObject { put("id", 9); put("state", "SUCCESS") }))
                else -> JsonArray(emptyList())
            }
        }
        val tracker = JobTracker(rpc, backgroundScope)
        tracker.run("Replicate", "replication.run")
        assertEquals("SUCCESS", tracker.jobs.value.single().state)
    }
}
