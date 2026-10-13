package com.arceusvault.keel.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Samples are trimmed from v26.0.0-shaped responses, including fields the app ignores. */
class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun `system info`() {
        val info = json.decodeFromString(SystemInfo.serializer(), """
            {"version":"TrueNAS-26.0.0-BETA.1","buildtime":"2026-08-01T00:00:00","hostname":"truenas","physmem":67108864000,
             "model":"AMD Ryzen 7 5700G","cores":16,"physical_cores":8,"loadavg":[0.5,0.42,0.4],"uptime":"3 days",
             "uptime_seconds":273600.5,"system_serial":null,"system_product":null,"license":null,"ecc_memory":false,
             "timezone":"America/Phoenix"}
        """)
        assertEquals("truenas", info.hostname)
        assertEquals(16, info.cores)
        assertEquals(3, info.loadavg.size)
        assertNull(info.systemProduct)
    }

    @Test
    fun `pool with nested topology and null scan fields`() {
        val pools = json.decodeFromString(ListSerializer(Pool.serializer()), """
            [{"id":1,"name":"media","guid":"123","status":"DEGRADED","path":"/mnt/media","healthy":false,"warning":true,
              "status_code":"CORRUPT_DATA","status_detail":"One or more devices has experienced an error",
              "size":8000000000000,"allocated":5000000000000,"free":3000000000000,"fragmentation":"3",
              "scan":{"function":"SCRUB","state":"FINISHED","start_time":"2026-10-05T00:00:00","end_time":null,
                      "percentage":100.0,"bytes_to_process":1,"bytes_processed":1,"bytes_issued":1,"pause":null,
                      "errors":0,"total_secs_left":null},
              "topology":{"data":[{"name":"mirror-0","type":"MIRROR","status":"DEGRADED","stats":{"read_errors":0,"write_errors":0,"checksum_errors":2},
                  "children":[{"name":"sda1","type":"DISK","disk":"sda","status":"ONLINE","children":[]},
                              {"name":"sdb1","type":"DISK","disk":"sdb","status":"FAULTED","children":[]}]}],
                "log":[],"cache":[],"spare":[],"special":[],"dedup":[]}}]
        """)
        val p = pools.single()
        assertEquals("DEGRADED", p.status)
        assertEquals(2, p.topology!!.data.single().children.size)
        assertEquals(2L, p.topology.data.single().stats!!.checksumErrors)
        assertEquals("sdb", p.topology.data.single().children[1].disk)
        assertEquals("SCRUB", p.scan!!.function)
    }

    @Test
    fun `alert dates in both formats and html stripped`() {
        val alerts = json.decodeFromString(ListSerializer(Alert.serializer()), """
            [{"uuid":"a","klass":"ZpoolCapacity","level":"WARNING","text":"plain","formatted":"Pool <b>media</b> is 85%&nbsp;full",
              "datetime":"2026-10-10T12:00:00Z","dismissed":false,"args":{},"node":"A","key":"k","mail":null,"one_shot":false},
             {"uuid":"b","level":"INFO","text":"old style","datetime":{"${'$'}date":1700000000000},"dismissed":true}]
        """)
        assertEquals("Pool media is 85% full", alerts[0].message)
        assertEquals(Instant.parse("2026-10-10T12:00:00Z"), alerts[0].time)
        assertEquals(Instant.ofEpochMilli(1700000000000), alerts[1].time)
        assertEquals("old style", alerts[1].message)
    }

    @Test
    fun `realtime prefers aggregate cpu and reads memory`() {
        val rt = Realtime.parse(json.parseToJsonElement("""
            {"cpu":{"cpu0":{"usage":10.0},"cpu1":{"usage":30.0},"cpu":{"usage":20.5,"temp":45}},
             "memory":{"physical_memory_total":64000,"physical_memory_available":16000,"arc_size":30000}}
        """).jsonObject)
        assertEquals(20.5, rt.cpuPercent!!, 0.0)
        assertEquals(64000L, rt.memTotal)
        assertEquals(16000L, rt.memAvailable)
        assertEquals(30000L, rt.arcSize)
    }

    @Test
    fun `realtime falls back to averaging cores`() {
        val rt = Realtime.parse(json.parseToJsonElement("""{"cpu":{"cpu0":{"usage":10.0},"cpu1":{"usage":30.0}}}""").jsonObject)
        assertEquals(20.0, rt.cpuPercent!!, 0.0)
        assertNull(rt.memTotal)
    }
}
