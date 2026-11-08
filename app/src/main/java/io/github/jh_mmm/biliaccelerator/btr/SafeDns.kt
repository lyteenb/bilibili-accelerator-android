package io.github.jh_mmm.biliaccelerator.btr

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS wrapper for every BTR upstream request.
 *
 * The local proxy is only a transport optimization. It must never become a
 * primitive for reaching loopback, RFC1918, CGNAT, link-local or IPv6 ULA
 * addresses. If a hostname resolves to a mix of public and non-public
 * addresses, the whole lookup is rejected to avoid DNS-rebinding style
 * bypasses.
 */
object SafeDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty()) {
            throw UnknownHostException("No address returned for $hostname")
        }
        if (addresses.any { !PublicAddressPolicy.isPublicAddress(it) }) {
            throw UnknownHostException("Blocked non-public upstream address for $hostname")
        }
        return addresses
    }
}

object PublicAddressPolicy {
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address
        return when {
            address is Inet4Address || bytes.size == 4 -> isPublicIpv4(bytes)
            address is Inet6Address || bytes.size == 16 -> isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        val b2 = bytes[2].toInt() and 0xff
        return when {
            b0 == 0 || b0 == 10 || b0 == 127 -> false
            b0 == 100 && b1 in 64..127 -> false // shared address space / CGNAT
            b0 == 169 && b1 == 254 -> false // link-local
            b0 == 172 && b1 in 16..31 -> false // RFC1918
            b0 == 192 && b1 == 168 -> false // RFC1918
            b0 == 192 && b1 == 0 && b2 in 0..2 -> false // IETF/docs
            b0 == 198 && (b1 == 18 || b1 == 19) -> false // benchmarking
            b0 == 198 && b1 == 51 && b2 == 100 -> false // documentation
            b0 == 203 && b1 == 0 && b2 == 113 -> false // documentation
            b0 >= 224 -> false // multicast/reserved/broadcast
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false

        // IPv4-mapped IPv6, e.g. ::ffff:127.0.0.1
        if (bytes.take(10).all { it.toInt() == 0 } &&
            (bytes[10].toInt() and 0xff) == 0xff &&
            (bytes[11].toInt() and 0xff) == 0xff
        ) {
            return isPublicIpv4(bytes.copyOfRange(12, 16))
        }

        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff

        // ::, ::1 and IPv4-compatible addresses need special handling.
        // NAT64 64:ff9b::/96 is allowed because it embeds a normal public IPv4
        // address and is used by IPv6-only carrier networks.
        if (first == 0x00) {
            if (bytes.all { it.toInt() == 0 }) return false
            if (bytes.take(15).all { it.toInt() == 0 } && (bytes[15].toInt() and 0xff) == 1) return false
            if (bytes.take(12).all { it.toInt() == 0 }) {
                return isPublicIpv4(bytes.copyOfRange(12, 16))
            }
            if (second == 0x64 && (bytes[2].toInt() and 0xff) == 0xff && (bytes[3].toInt() and 0xff) == 0x9b) {
                return isPublicIpv4(bytes.copyOfRange(12, 16))
            }
            return true
        }

        return when {
            (first and 0xfe) == 0xfc -> false // fc00::/7 unique local
            first == 0xfe && (second and 0xc0) == 0x80 -> false // fe80::/10 link-local
            first == 0xff -> false // multicast
            else -> true
        }
    }
}
