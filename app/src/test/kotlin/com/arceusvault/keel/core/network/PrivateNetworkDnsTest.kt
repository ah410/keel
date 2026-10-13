package com.arceusvault.keel.core.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PrivateNetworkDnsTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `private ranges`() {
        listOf("192.168.0.78", "10.1.2.3", "172.16.0.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "100.101.102.103",
            "100.127.255.254", "fd7a:115c:a1e0::1", "fe80::1", "::1")
            .forEach { assertTrue(it, PrivateNetworkDns.isPrivate(ip(it))) }
    }

    @Test
    fun `public ranges`() {
        listOf("8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "1.1.1.1", "2606:4700::1111")
            .forEach { assertFalse(it, PrivateNetworkDns.isPrivate(ip(it))) }
    }

    @Test
    fun `mixed answers keep only private addresses`() {
        val dns = PrivateNetworkDns { listOf(ip("8.8.8.8"), ip("192.168.0.78")) }
        assertEquals(listOf(ip("192.168.0.78")), dns.lookup("nas"))
    }

    @Test
    fun `public-only answer refuses to connect`() {
        val dns = PrivateNetworkDns(Dns { listOf(ip("8.8.8.8")) })
        assertThrows(PublicAddressException::class.java) { dns.lookup("nas.example.com") }
    }
}
