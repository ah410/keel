package com.arceusvault.keel.core.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/**
 * The vector is a real exchange produced by TrueNAS's own SCRAM library (truenas_scram, the code the
 * middleware runs), with key material derived the way middlewared derives it for API keys.
 */
class ScramTest {
    private val secret = "aB3dE5gH7jK9mN1pQ3sT5vW7yZ9bC1eF3hJ5kL7nP9rS1uV3xY5zA7cE9gI1kM3o"
    private val clientNonce = "XeP5bICxOD1bwuosSrK8TQcMW5bjOoxm3gwZXfN5Y1g="
    private val clientFirst = "n,,n=truenas_admin:7,r=$clientNonce"
    private val serverFirst = "r=XeP5bICxOD1bwuosSrK8TQcMW5bjOoxm3gwZXfN5Y1gmQXUalYuzE5G0MoXHxf5JLczqG0cPlijzPUo0HtOlZQ==," +
        "s=WHk3Li9RejNMbTlwUnQyVw==,i=500000"
    private val clientFinal = "c=biws,r=XeP5bICxOD1bwuosSrK8TQcMW5bjOoxm3gwZXfN5Y1gmQXUalYuzE5G0MoXHxf5JLczqG0cPlijzPUo0HtOlZQ==," +
        "p=YnumZXELVpD8fblzqHmgB4VGN4bC7nw/ivfQhqV5PdOdeBtYUuJYbTzMYpP/uJSWZVHc2YX+UEhjz247lyDQWQ=="
    private val serverFinal = "v=eW6R2YTBCWkVsijpUSrUhxZrduxLIYPKeFQZBSBeJrtJa5QtF6c1TAfDFWP0vBw/jlCkKpmBoYtOpzMGwU5KIg=="
    private val clientKey = "0ZVgCyrpZ6qA9oR3u6bAPbhJaq4KFsZDlI0A9ZoZU23PQ6k52sXENuNpNMZBwOdjoaamorzjjnO+YfYckDVwGg=="
    private val serverKey = "3mdTINOamc66sBN7FB7CCVY//Cn71/cOrPyH1mQNoohOTavbneimfy8HBvUv1/9hR5CM/NG5JC113DG83lq7Wg=="

    private val b64 = Base64.getDecoder()

    // PBKDF2 at 500k iterations is slow, so derive once for the whole class.
    private val keys by lazy { Scram.deriveKeys(secret.toCharArray(), "WHk3Li9RejNMbTlwUnQyVw==", 500_000) }

    @Test
    fun `client first message matches TrueNAS format`() {
        assertEquals(clientFirst, Scram.start("truenas_admin", 7, clientNonce).clientFirst)
    }

    @Test
    fun `generated nonce is the 32 bytes TrueNAS requires`() {
        repeat(20) { assertEquals(32, b64.decode(Scram.newNonce()).size) }
    }

    @Test
    fun `derives the same keys as the middleware`() {
        assertArrayEquals(b64.decode(clientKey), keys.clientKey)
        assertArrayEquals(b64.decode(serverKey), keys.serverKey)
    }

    @Test
    fun `full exchange reproduces TrueNAS transcript and verifies server`() {
        val session = Scram.start("truenas_admin", 7, clientNonce)
        val sf = Scram.parseServerFirst(session, serverFirst)
        val final = Scram.clientFinal(session, sf, keys)
        assertEquals(clientFinal, final.message)
        Scram.verifyServerFinal(final, serverFinal)
    }

    @Test
    fun `forged server signature is rejected`() {
        val session = Scram.start("truenas_admin", 7, clientNonce)
        val final = Scram.clientFinal(session, Scram.parseServerFirst(session, serverFirst), keys)
        val forged = "v=" + Base64.getEncoder().encodeToString(ByteArray(64))
        assertThrows(Scram.ScramException::class.java) { Scram.verifyServerFinal(final, forged) }
        assertThrows(Scram.ScramException::class.java) { Scram.verifyServerFinal(final, "e=invalid-proof") }
    }

    @Test
    fun `server nonce must extend client nonce`() {
        val session = Scram.start("truenas_admin", 7, "abc")
        assertThrows(Scram.ScramException::class.java) { Scram.parseServerFirst(session, "r=xyz123,s=AAAA,i=500000") }
        assertThrows(Scram.ScramException::class.java) { Scram.parseServerFirst(session, "r=abc,s=AAAA,i=500000") }
    }

    @Test
    fun `weak or absurd iteration counts are rejected`() {
        val session = Scram.start("u", 1, "abc")
        assertThrows(Scram.ScramException::class.java) { Scram.parseServerFirst(session, "r=abcd,s=AAAA,i=4096") }
        assertThrows(Scram.ScramException::class.java) { Scram.parseServerFirst(session, "r=abcd,s=AAAA,i=99999999") }
    }

    @Test
    fun `stored keys for a different salt are refused`() {
        val session = Scram.start("u", 1, "abc")
        val sf = Scram.parseServerFirst(session, "r=abcd,s=BBBB,i=500000")
        assertThrows(Scram.ScramException::class.java) { Scram.clientFinal(session, sf, keys) }
    }

    @Test
    fun `username is escaped per RFC 5802`() {
        assertEquals("n,,n=a=2Cb=3Dc:1,r=n", Scram.start("a,b=c", 1, "n").clientFirst)
    }

    @Test
    fun `raw api key parsing`() {
        val k = RawApiKey.parse("  7-$secret\n")!!
        assertEquals(7L, k.id)
        assertEquals(secret, String(k.secret))
        k.wipe()
        assert(k.secret.all { it == '\u0000' })
        assertNull(RawApiKey.parse(secret))
        assertNull(RawApiKey.parse("x-$secret"))
        assertNull(RawApiKey.parse("7-short"))
        assertNull(RawApiKey.parse("7-" + secret.dropLast(1) + "é"))
    }
}
