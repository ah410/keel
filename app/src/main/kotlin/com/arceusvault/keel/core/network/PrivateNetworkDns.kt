package com.arceusvault.keel.core.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class PublicAddressException(host: String) :
    UnknownHostException("$host resolves to a public address. Secure mode only connects over LAN or a VPN tunnel.")

/** Secure mode's DNS: only private, loopback, link-local, CGNAT (Tailscale) or ULA addresses are used. */
class PrivateNetworkDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        return all.filter(::isPrivate).ifEmpty { throw PublicAddressException(hostname) }
    }

    companion object {
        fun isPrivate(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress) return true
            val b = a.address
            return when (a) {
                // 100.64.0.0/10, carrier-grade NAT, which is where Tailscale addresses live.
                is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
                // fc00::/7 unique local addresses.
                is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
                else -> false
            }
        }
    }
}
