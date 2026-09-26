package com.hermesandroid.relay.network.shared

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Pure tailnet address predicates for "Always connect via Tailscale" (ADR 75).
 *
 * A tailnet address is inferred ONLY from the address itself:
 *  - a MagicDNS name ending in [MAGIC_DNS_SUFFIX] with a non-empty label before it,
 *  - an IPv4 literal in the CGNAT range 100.64.0.0/10,
 *  - an IPv6 literal in the Tailscale range fd7a:115c:a1e0::/48.
 *
 * Nothing in this object performs a DNS lookup. IP literals are parsed by hand and turned
 * into [InetAddress] objects with [InetAddress.getByAddress], which never resolves names.
 */
object TailnetAddresses {
    const val MAGIC_DNS_SUFFIX = ".ts.net"

    private val TAILNET_IPV6_PREFIX = intArrayOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)

    /** True iff [host] is a MagicDNS name or a tailnet IP literal. Never resolves [host]. */
    fun isTailnetHost(host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (normalized.endsWith(MAGIC_DNS_SUFFIX)) {
            val label = normalized.removeSuffix(MAGIC_DNS_SUFFIX)
            return label.isNotEmpty() && !label.startsWith(".") && !label.endsWith(".")
        }
        val literal = ipLiteralOrNull(normalized) ?: return false
        return isTailnetAddress(literal)
    }

    /** True iff [address] is in 100.64.0.0/10 (IPv4) or fd7a:115c:a1e0::/48 (IPv6). */
    fun isTailnetAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isTailnetIpv4(address.address)
        is Inet6Address -> isTailnetIpv6(address.address)
        else -> false
    }

    /**
     * True iff [url] parses and its host [isTailnetHost]. `ws://` and `wss://` are mapped to
     * `http://` and `https://` before parsing, the same mapping `Endpoint.kt` uses.
     * Any parse failure, a missing host, null or blank input returns false.
     */
    fun isTailnetUrl(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val httpUrl = when {
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            else -> trimmed
        }
        val host = runCatching { URI(httpUrl).host }.getOrNull() ?: return false
        return isTailnetHost(host)
    }

    /**
     * Returns the [InetAddress] for an IPv4 or IPv6 literal (brackets, one trailing dot and an
     * IPv6 zone suffix are tolerated), or null when [host] is not an IP literal.
     * Never performs a DNS lookup.
     */
    fun ipLiteralOrNull(host: String?): InetAddress? {
        val normalized = normalizeHost(host) ?: return null
        val bytes = parseIpv4(normalized)
            ?: if (normalized.contains(':')) parseIpv6(normalized) else null
        return bytes?.let { InetAddress.getByAddress(it) }
    }

    private fun normalizeHost(host: String?): String? {
        var value = host?.trim() ?: return null
        if (value.startsWith("[") && value.endsWith("]") && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        if (value.endsWith(".")) value = value.dropLast(1)
        value = value.lowercase()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun isTailnetIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }

    private fun isTailnetIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        return TAILNET_IPV6_PREFIX.indices.all { index ->
            bytes[index].toInt() and 0xff == TAILNET_IPV6_PREFIX[index]
        }
    }

    internal fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (index in 0 until 4) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            out[index] = value.toByte()
        }
        return out
    }

    internal fun parseIpv6(text: String): ByteArray? {
        val literal = text.substringBefore('%')
        if (literal.isEmpty()) return null
        val doubleColon = literal.indexOf("::")
        if (doubleColon >= 0 && literal.indexOf("::", doubleColon + 1) >= 0) return null
        val groups: List<Int> = if (doubleColon < 0) {
            val all = parseIpv6Groups(literal, allowTrailingIpv4 = true) ?: return null
            if (all.size != 8) return null
            all
        } else {
            val head = parseIpv6Groups(literal.substring(0, doubleColon), allowTrailingIpv4 = false)
                ?: return null
            val tail = parseIpv6Groups(literal.substring(doubleColon + 2), allowTrailingIpv4 = true)
                ?: return null
            val missing = 8 - head.size - tail.size
            if (missing < 1) return null
            head + List(missing) { 0 } + tail
        }
        val out = ByteArray(16)
        groups.forEachIndexed { index, group ->
            out[2 * index] = (group shr 8).toByte()
            out[2 * index + 1] = (group and 0xff).toByte()
        }
        return out
    }

    private fun parseIpv6Groups(text: String, allowTrailingIpv4: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val pieces = text.split(':')
        val groups = ArrayList<Int>(8)
        for (index in pieces.indices) {
            val piece = pieces[index]
            if (allowTrailingIpv4 && index == pieces.lastIndex && piece.contains('.')) {
                val v4 = parseIpv4(piece) ?: return null
                groups.add(((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff))
                groups.add(((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff))
            } else {
                if (piece.isEmpty() || piece.length > 4) return null
                if (!piece.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                groups.add(piece.toInt(16))
            }
        }
        return groups
    }
}
