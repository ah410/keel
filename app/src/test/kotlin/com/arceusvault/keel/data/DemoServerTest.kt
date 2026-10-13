package com.arceusvault.keel.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DemoServerTest {
    private val asset = File("src/main/assets/demo/server.json").readText()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun `timestamps move to the present and other numbers stay put`() {
        val gen = 2_000_000_000L
        val src = Json.parseToJsonElement(
            """{"generatedAt":$gen,"a":"${Instant.ofEpochSecond(gen - 7200)}","b":{"${'$'}date":${(gen - 60) * 1000}},"c":${gen - 30},"size":35184372088832,"snap":"tank@auto-2033-05-17_00-00"}""",
        ).jsonObject
        val out = DemoServer.shiftTimes(src, 1000 * 86_400L).jsonObject
        assertEquals(Instant.ofEpochSecond(gen - 7200 + 1000 * 86_400L).toString(), (out["a"] as JsonPrimitive).content)
        assertEquals((gen - 60 + 1000 * 86_400L) * 1000, (out["b"]!!.jsonObject["\$date"] as JsonPrimitive).content.toLong())
        assertEquals(gen - 30 + 1000 * 86_400L, (out["c"] as JsonPrimitive).content.toLong())
        assertEquals(35184372088832, (out["size"] as JsonPrimitive).content.toLong())
        assertEquals("tank@auto-2036-02-11_00-00", (out["snap"] as JsonPrimitive).content)
    }

    @Test
    fun `screens can read the demo data through the real api`() = runTest {
        val server = DemoServer.load(asset, backgroundScope)
        val api = TrueNasApi(server, json, JobTracker(server, backgroundScope))
        assertEquals("keel-demo", api.systemInfo().hostname)
        assertTrue(api.pools().isNotEmpty())
        assertTrue(api.smbShares().isNotEmpty())
        val edit = api.appForEdit("jellyfin")
        assertTrue(edit.form != null && edit.form.fields.isNotEmpty())
        assertTrue(api.appForEdit("qbittorrent").custom)
    }

    @Test
    fun `jobs finish and edits are kept`() = runTest {
        val server = DemoServer.load(asset, backgroundScope)
        val api = TrueNasApi(server, json, JobTracker(server, backgroundScope))
        val stop = Actions.appStop("jellyfin")
        val run = backgroundScope.async { api.perform(stop) }
        advanceTimeBy(10_000)
        run.await()
        assertEquals("STOPPED", api.app("jellyfin").state)

        api.perform(Actions.shareSmbCreate(buildJsonObject { put("name", "docs"); put("path", "/mnt/tank/media") }))
        assertTrue(api.smbShares().any { it.name == "docs" })
        val dirs = api.listDirs("/mnt/tank")
        assertTrue("/mnt/tank/media" in dirs)
    }

    @Test
    fun `unknown methods fail like a real server`() = runTest {
        val server = DemoServer.load(asset, backgroundScope)
        val e = runCatching { server.call("vmware.query", JsonArray(emptyList())) }.exceptionOrNull()
        assertTrue(e is com.arceusvault.keel.core.network.RpcException)
        assertTrue(server.call("core.subscribe", buildJsonArray { add(JsonPrimitive("reporting.realtime")) }) is JsonPrimitive)
        assertTrue((server.call("alert.list", JsonArray(emptyList())) as JsonArray).first() is JsonObject)
    }
}

