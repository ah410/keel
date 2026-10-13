package com.arceusvault.keel.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolesTest {
    @Test
    fun `readonly admin can read but not write`() {
        val r = Roles(listOf("READONLY_ADMIN"), INCLUDES)
        assertTrue(r.has("APPS_READ"))
        assertTrue(r.has("POOL_SCRUB_READ"))
        assertFalse(r.has("APPS_WRITE"))
        assertFalse(Actions.appStop("plex").allowedFor(r))
    }

    @Test
    fun `included roles are expanded transitively`() {
        val r = Roles(listOf("POOL_WRITE"), INCLUDES)
        assertTrue(r.has("POOL_READ"))
        assertTrue(r.has("POOL_SCRUB_WRITE"))
        assertTrue(r.has("POOL_SCRUB_READ"))
        assertTrue(Actions.scrubStart("tank").allowedFor(r))
    }

    @Test
    fun `full admin can do everything including power`() {
        val r = Roles(listOf("FULL_ADMIN"), INCLUDES)
        assertTrue(Actions.reboot("nas").allowedFor(r))
        assertTrue(r.has("ANYTHING_AT_ALL"))
    }

    @Test
    fun `snapshot delete is separate from snapshot write`() {
        val r = Roles(listOf("SNAPSHOT_WRITE"), INCLUDES)
        assertTrue(Actions.snapshotCreate("tank/a", "s", false).allowedFor(r))
        assertFalse(Actions.snapshotDestroy("tank/a@s").allowedFor(r))
    }

    @Test
    fun `service control accepts any of its alternative roles`() {
        assertTrue(Actions.serviceControl("cifs", "STOP").allowedFor(Roles(listOf("SHARING_SMB_WRITE"), INCLUDES)))
        assertTrue(Actions.serviceControl("cifs", "STOP").allowedFor(Roles(listOf("SHARING_ADMIN"), INCLUDES)))
    }

    @Test
    fun `roles are read from login user_info`() {
        val user = Json.parseToJsonElement("""
            {"pw_name":"phone","privilege":{"roles":{"${'$'}set":["READONLY_ADMIN","APPS_WRITE"]},"web_shell":false},
             "attributes":{"preferences":{"userTheme":"dracula"}}}
        """).jsonObject
        assertEquals(listOf("READONLY_ADMIN", "APPS_WRITE"), Connector.rolesOf(user))
        assertEquals(listOf("FULL_ADMIN"), Connector.rolesOf(Json.parseToJsonElement("""{"privilege":{"roles":["FULL_ADMIN"]}}""").jsonObject))
    }

    @Test
    fun `dangerous actions get the strict tiers`() {
        assertEquals(Tier.CRITICAL, Actions.reboot("nas").tier)
        assertEquals("nas", Actions.reboot("nas").typeToConfirm)
        assertEquals(Tier.CRITICAL, Actions.snapshotRollback("tank/data@s1").tier)
        assertEquals("tank/data", Actions.snapshotRollback("tank/data@s1").typeToConfirm)
        assertEquals(Tier.DESTRUCTIVE, Actions.snapshotDestroy("tank/data@s1").tier)
        assertEquals(Tier.DESTRUCTIVE, Actions.appUpgrade("plex", "1.2.3", true).tier)
        assertEquals(Tier.BENIGN, Actions.alertDismiss("u").tier)
    }

    @Test
    fun `identical calls share a key and different targets do not`() {
        assertEquals(Actions.alertDismiss("a").key, Actions.alertDismiss("a").key)
        assertFalse(Actions.alertDismiss("a").key == Actions.alertDismiss("b").key)
    }

    private companion object {
        // A slice of the table privilege.roles returns, enough for these checks.
        val INCLUDES = mapOf(
            "READONLY_ADMIN" to listOf("APPS_READ", "POOL_READ", "ALERT_LIST_READ"),
            "APPS_READ" to listOf("CATALOG_READ"),
            "POOL_READ" to listOf("POOL_SCRUB_READ"),
            "POOL_WRITE" to listOf("POOL_READ", "POOL_SCRUB_WRITE"),
            "POOL_SCRUB_WRITE" to listOf("POOL_SCRUB_READ"),
            "SNAPSHOT_WRITE" to listOf("SNAPSHOT_READ"),
            "SHARING_ADMIN" to listOf("READONLY_ADMIN", "SHARING_WRITE", "SERVICE_READ"),
            "SHARING_WRITE" to listOf("SHARING_SMB_WRITE", "SHARING_NFS_WRITE"),
        )
    }
}
