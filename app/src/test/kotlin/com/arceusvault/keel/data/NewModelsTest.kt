package com.arceusvault.keel.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Shapes follow the v26.0.0 docs; fields the app ignores are included to prove they're tolerated. */
class NewModelsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun `realtime parses cores, temperature, interfaces and service memory`() {
        val rt = Realtime.parse(json.parseToJsonElement("""
            {"cpu":{"cpu":{"usage":12.5,"temp":48.0},"cpu0":{"usage":10.0},"cpu1":{"usage":15.0},"cpu10":{"usage":1.0}},
             "memory":{"physical_memory_total":1000,"physical_memory_available":300,"arc_size":400,"arc_free_memory":1},
             "interfaces":{"eno1":{"received_bytes_rate":1250000.0,"sent_bytes_rate":125.0,"link_state":"LINK_STATE_UP"}},
             "disks":{},"zfs":{},"pools":{}}
        """).jsonObject)
        assertEquals(12.5, rt.cpuPercent!!, 0.0)
        assertEquals(48.0, rt.cpuTemp!!, 0.0)
        // Numeric order, not string order: cpu10 comes after cpu1.
        assertEquals(listOf(10.0, 15.0, 1.0), rt.cores)
        assertEquals(300L, rt.servicesMemory)
        assertEquals(1250000.0, rt.interfaces["eno1"]!!.rx, 0.0)
        assertTrue(rt.interfaces["eno1"]!!.linkUp!!)
    }

    @Test
    fun `app with workloads and portals`() {
        val app = json.decodeFromString(App.serializer(), """
            {"name":"jellyfin","id":"jellyfin","state":"RUNNING","upgrade_available":true,"latest_version":"1.2.0",
             "image_updates_available":false,"custom_app":false,"migrated":false,"human_version":"10.10.7_1.1.0","version":"1.1.0",
             "metadata":{"title":"Jellyfin","train":"community","description":"Media server"},
             "active_workloads":{"containers":1,"used_ports":[{"container_port":8096,"protocol":"tcp","host_ports":[{"host_port":30013,"host_ip":"0.0.0.0"}]}],
               "used_host_ips":[],"container_details":[{"id":"abc123","service_name":"jellyfin","image":"jellyfin/jellyfin:10.10.7",
               "port_config":[],"state":"running","volume_mounts":[]}],"volumes":[{"source":"/mnt/tank/media","destination":"/media","mode":"ro","type":"bind"}],
               "images":["jellyfin/jellyfin:10.10.7"],"networks":[]},
             "notes":null,"portals":{"Web UI":"http://0.0.0.0:30013/"},"version_details":null,"config":null}
        """)
        assertEquals("Jellyfin", app.title)
        assertEquals("community", app.train)
        assertEquals(listOf("Web UI" to "http://0.0.0.0:30013/"), app.portalLinks)
        assertEquals("jellyfin", app.workloads!!.containerDetails.single().serviceName)
        assertEquals(30013, app.workloads!!.usedPorts.single().hostPorts.single().hostPort)
    }

    @Test
    fun `app stats sum network interfaces`() {
        val stats = AppStats.parseAll(json.parseToJsonElement("""
            [{"app_name":"plex","cpu_usage":7,"memory":104857600,"networks":[{"interface_name":"eth0","rx_bytes":100,"tx_bytes":50},
              {"interface_name":"eth1","rx_bytes":1,"tx_bytes":2}],"blkio":{"read":0,"write":0}}]
        """))
        assertEquals(7.0, stats["plex"]!!.cpuPercent, 0.0)
        assertEquals(101L, stats["plex"]!!.rxRate)
        assertEquals(52L, stats["plex"]!!.txRate)
    }

    @Test
    fun `dataset properties`() {
        val ds = json.decodeFromString(ListSerializer(Dataset.serializer()), """
            [{"id":"tank/media","type":"FILESYSTEM","name":"tank/media","pool":"tank","encrypted":true,"encryption_root":"tank/media",
              "key_loaded":true,"children":[],"locked":false,
              "used":{"parsed":5000,"rawvalue":"5000","value":"4.88K","source":"NONE"},
              "available":{"parsed":10000,"rawvalue":"10000","value":"9.77K","source":"NONE"},
              "compressratio":{"parsed":"1.45","rawvalue":"1.45","value":"1.45x","source":"NONE"},
              "readonly":{"parsed":false,"rawvalue":"off","value":"OFF","source":"DEFAULT"},
              "mountpoint":"/mnt/tank/media"}]
        """).single()
        assertEquals(5000L, ds.used!!.long)
        assertEquals("1.45x", ds.compressratio!!.text)
        assertEquals(false, ds.readonly!!.bool)
        assertEquals(1, ds.depth)
        assertEquals("media", ds.shortName)
        assertEquals("tank", ds.parent)
    }

    @Test
    fun `snapshot properties and holds`() {
        val s = json.decodeFromString(Snapshot.serializer(), """
            {"createtxg":123,"guid":1,"name":"tank/media@auto-2026-10-10","pool":"tank","dataset":"tank/media",
             "snapshot_name":"auto-2026-10-10","type":"SNAPSHOT","holds":{"keep":1},
             "properties":{"used":{"raw":"2048","source":{"type":"NONE","value":null},"value":2048},
                           "creation":{"raw":"1760000000","source":null,"value":1760000000}}}
        """)
        assertEquals(2048L, s.used)
        assertEquals(Instant.ofEpochSecond(1760000000), s.created)
        assertTrue(s.held)
        assertFalse(s.copy(holds = json.parseToJsonElement("[]")).held)
    }

    @Test
    fun `task states from zettarepl state and from last job`() {
        val t = json.decodeFromString(SnapshotTask.serializer(), """
            {"id":1,"dataset":"tank","recursive":true,"lifetime_value":2,"lifetime_unit":"WEEK","enabled":true,
             "naming_schema":"auto-%Y","schedule":{"minute":"0","hour":"0","dom":"*","month":"*","dow":"*","begin":"00:00","end":"23:59"},
             "state":{"state":"ERROR","datetime":{"${'$'}date":1760000000000},"error":"dataset busy"}}
        """)
        assertEquals("ERROR", t.state!!.state)
        assertEquals("dataset busy", t.state!!.error)
        assertEquals(Instant.ofEpochMilli(1760000000000), t.state!!.time)

        val c = json.decodeFromString(CloudSyncTask.serializer(), """
            {"id":3,"description":"B2 backup","path":"/mnt/tank","direction":"PUSH","transfer_mode":"SYNC","enabled":true,
             "schedule":{"minute":"0","hour":"3","dom":"*","month":"*","dow":"*"},"credentials":{"name":"Backblaze"},
             "job":{"state":"RUNNING","time_started":"2026-10-10T03:00:00Z","progress":{"percent":40,"description":"x"}}}
        """)
        assertEquals("RUNNING", c.state!!.state)
        assertEquals(40.0, c.percent!!, 0.0)
        assertEquals("Backblaze", c.provider)
    }

    @Test
    fun `update status with a new version`() {
        val u = json.decodeFromString(UpdateStatus.serializer(), """
            {"code":"NORMAL","status":{"current_version":{"train":"TrueNAS-SCALE-Goldeye","profile":"GENERAL","matches_profile":true},
              "new_version":{"version":"26.0.1","manifest":{},"release_notes":"Fixes","release_notes_url":"https://example.invalid"}},
             "error":null,"update_download_progress":null}
        """)
        assertEquals("26.0.1", u.status!!.newVersion!!.version)
        assertNull(u.download)
    }

    @Test
    fun `reporting series`() {
        val s = Series.parse(json.parseToJsonElement("""
            {"name":"cpu","identifier":null,"data":[[1,10.0,2.0],[2,null,3.0]],"aggregations":{},"start":1,"end":2,
             "legend":["time","user","system"]}
        """).jsonObject)
        assertEquals(listOf("time", "user", "system"), s.legend)
        assertTrue(s.points[1][1].isNaN())
        assertEquals(3.0, s.points[1][2], 0.0)
    }

    @Test
    fun `interface addresses prefer live state`() {
        val nic = json.decodeFromString(NetInterface.serializer(), """
            {"id":"eno1","name":"eno1","type":"PHYSICAL","mtu":1500,"ipv4_dhcp":true,"aliases":[],
             "state":{"link_state":"LINK_STATE_UP","link_address":"aa:bb","active_media_subtype":"1000baseT",
               "aliases":[{"type":"LINK","address":"aa:bb"},{"type":"INET","address":"192.168.0.78","netmask":24}]}}
        """)
        assertEquals(listOf("192.168.0.78/24"), nic.addresses)
        assertEquals("1000baseT", nic.activeMedia)
    }

    @Test
    fun `vm state and cpu count`() {
        val vm = json.decodeFromString(Vm.serializer(), """
            {"id":1,"name":"win","vcpus":1,"cores":4,"threads":2,"memory":8192,"autostart":false,
             "status":{"state":"RUNNING","pid":123,"domain_state":"RUNNING"}}
        """)
        assertEquals("RUNNING", vm.state)
        assertEquals(8, vm.cpuCount)
    }

    @Test
    fun `service display names match the web UI`() {
        assertEquals("SMB", serviceName("cifs"))
        assertEquals("SSH", serviceName("ssh"))
    }
}
