package com.arceusvault.keel.core.security

import com.arceusvault.keel.domain.CertTrust
import com.arceusvault.keel.domain.ConnectionMode
import com.arceusvault.keel.domain.ServerProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class VaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val contents = VaultContents(
        profile = ServerProfile("192.168.0.78", 4443, ConnectionMode.SECURE, CertTrust.Pinned("spki", "AA:BB"), "truenas_admin", 7),
        scramSalt = "c2FsdA==",
        scramIterations = 500_000,
        clientKey = "Y2xpZW50LWtleS1zZWNyZXQ=",
        serverKey = "c2VydmVyLWtleQ==",
    )

    @Test
    fun `round trip`() {
        val vault = Vault.single(tmp.root.resolve("vault.bin"), { key }, json)
        assertNull(vault.read())
        vault.write(contents)
        assertEquals(contents, vault.read())
    }

    @Test
    fun `nothing is stored in plaintext`() {
        val file = tmp.root.resolve("vault.bin")
        Vault.single(file, { key }, json).write(contents)
        val raw = file.readText()
        listOf("192.168.0.78", "truenas_admin", contents.clientKey, contents.serverKey).forEach {
            assertFalse("leaked $it", raw.contains(it))
        }
    }

    @Test
    fun `tampering is detected`() {
        val file = tmp.root.resolve("vault.bin")
        val vault = Vault.single(file, { key }, json)
        vault.write(contents)
        val text = file.readText()
        val flipped = text.substring(0, 20) + (if (text[20] == 'A') 'B' else 'A') + text.substring(21)
        file.writeText(flipped)
        assertThrows(AEADBadTagException::class.java) { vault.read() }
    }

    @Test
    fun `wrong key cannot read`() {
        val file = tmp.root.resolve("vault.bin")
        Vault.single(file, { key }, json).write(contents)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(AEADBadTagException::class.java) { Vault.single(file, { other }, json).read() }
    }

    @Test
    fun `a single-server vault from before multi-server support still opens`() {
        val file = tmp.root.resolve("vault.bin")
        Vault.single(file, { key }, json).write(contents)
        assertEquals(VaultData(listOf(contents)), Vault.servers(file, { key }, json).read())
    }

    @Test
    fun `multi-server round trip`() {
        val file = tmp.root.resolve("vault.bin")
        val second = contents.copy(profile = contents.profile.copy(host = "100.64.0.5", port = 443))
        val vault = Vault.servers(file, { key }, json)
        vault.write(VaultData(listOf(contents, second)))
        assertEquals(listOf(contents, second), vault.read()!!.servers)
    }

    @Test
    fun `adding the same server again replaces it instead of duplicating`() {
        val second = contents.copy(profile = contents.profile.copy(host = "100.64.0.5"))
        val (list, index) = com.arceusvault.keel.data.withServer(listOf(contents), second)
        assertEquals(2, list.size)
        assertEquals(1, index)
        val rekeyed = contents.copy(clientKey = "bmV3")
        val (again, at) = com.arceusvault.keel.data.withServer(list, rekeyed)
        assertEquals(2, again.size)
        assertEquals(0, at)
        assertEquals("bmV3", again[0].clientKey)
    }
}
