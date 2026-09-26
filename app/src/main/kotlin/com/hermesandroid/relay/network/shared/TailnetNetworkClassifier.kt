package com.hermesandroid.relay.network.shared

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Plain-data view of one Android network, built by [AndroidTailnetNetworkSource] from the
 * network's `NetworkCapabilities` and `LinkProperties`.
 *
 * The interface name is deliberately NOT part of this shape: on Android the VPN interface is
 * a generic `tunN`, so a name match cannot identify the tailnet. Identification uses addresses.
 */
data class NetworkSnapshot(
    /** `Network.getNetworkHandle()`. */
    val handle: Long,
    /** `NetworkCapabilities.hasTransport(TRANSPORT_VPN)`. */
    val isVpn: Boolean,
    /** `LinkProperties.getLinkAddresses()` mapped to their addresses. */
    val linkAddresses: List<InetAddress>,
    /** `LinkProperties.getDnsServers()`. */
    val dnsServers: List<InetAddress>,
    /** Elapsed-realtime millis when the network was first seen by the source. */
    val firstSeenElapsedMs: Long,
)

/**
 * Pure classification of which network, if any, carries the tailnet (ADR 75).
 *
 * A network is the tailnet iff it is a VPN-transport network AND at least one of its link
 * addresses is in a tailnet range (100.64.0.0/10 or fd7a:115c:a1e0::/48). This infers a
 * "tailnet-style VPN"; it cannot prove the VPN app is Tailscale (the platform hides the VPN
 * owner from third-party apps).
 */
object TailnetNetworkClassifier {

    fun isTailnet(snapshot: NetworkSnapshot): Boolean =
        snapshot.isVpn && snapshot.linkAddresses.any { TailnetAddresses.isTailnetAddress(it) }

    /**
     * Among the tailnet networks in [snapshots], picks one; null when none qualifies.
     * Tie-break order (only between networks that already qualify):
     *  1. has a link address in fd7a:115c:a1e0::/48,
     *  2. lists the MagicDNS resolver 100.100.100.100 as a DNS server,
     *  3. most recently first seen,
     *  4. highest handle.
     */
    fun pick(snapshots: List<NetworkSnapshot>): NetworkSnapshot? =
        snapshots.filter { isTailnet(it) }.maxWithOrNull(pickOrder)

    private val pickOrder: Comparator<NetworkSnapshot> = Comparator { left, right ->
        compareValuesBy(
            left,
            right,
            { snapshot -> hasTailnetIpv6(snapshot) },
            { snapshot -> hasMagicDnsResolver(snapshot) },
            { snapshot -> snapshot.firstSeenElapsedMs },
            { snapshot -> snapshot.handle },
        )
    }

    private fun hasTailnetIpv6(snapshot: NetworkSnapshot): Boolean =
        snapshot.linkAddresses.any { it is Inet6Address && TailnetAddresses.isTailnetAddress(it) }

    private fun hasMagicDnsResolver(snapshot: NetworkSnapshot): Boolean =
        snapshot.dnsServers.any { address ->
            address is Inet4Address && address.address.all { it.toInt() and 0xff == 100 }
        }
}
