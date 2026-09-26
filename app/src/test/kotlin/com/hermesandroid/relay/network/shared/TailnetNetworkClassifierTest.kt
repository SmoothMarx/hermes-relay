package com.hermesandroid.relay.network.shared

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetNetworkClassifierTest {

    private fun ip(literal: String): InetAddress = TailnetAddresses.ipLiteralOrNull(literal)!!

    private fun snapshot(
        handle: Long,
        isVpn: Boolean = true,
        linkAddresses: List<String> = emptyList(),
        dnsServers: List<String> = emptyList(),
        firstSeenElapsedMs: Long = 0L,
    ): NetworkSnapshot = NetworkSnapshot(
        handle = handle,
        isVpn = isVpn,
        linkAddresses = linkAddresses.map(::ip),
        dnsServers = dnsServers.map(::ip),
        firstSeenElapsedMs = firstSeenElapsedMs,
    )

    @Test
    fun `non vpn network is never the tailnet`() {
        assertFalse(TailnetNetworkClassifier.isTailnet(snapshot(1, isVpn = false, linkAddresses = listOf("100.64.0.5"))))
    }

    @Test
    fun `vpn without tailnet addresses is not the tailnet`() {
        assertFalse(TailnetNetworkClassifier.isTailnet(snapshot(1, linkAddresses = listOf("10.8.0.2"))))
        assertFalse(TailnetNetworkClassifier.isTailnet(snapshot(1, dnsServers = listOf("100.100.100.100"))))
    }

    @Test
    fun `vpn with a cgnat or tailscale ipv6 link address is the tailnet`() {
        assertTrue(TailnetNetworkClassifier.isTailnet(snapshot(1, linkAddresses = listOf("100.64.0.5"))))
        assertTrue(TailnetNetworkClassifier.isTailnet(snapshot(1, linkAddresses = listOf("fd7a:115c:a1e0::5"))))
        assertTrue(TailnetNetworkClassifier.isTailnet(snapshot(1, linkAddresses = listOf("10.8.0.2", "100.64.0.5"))))
    }

    @Test
    fun `pick returns null when nothing qualifies`() {
        assertNull(TailnetNetworkClassifier.pick(emptyList()))
        assertNull(
            TailnetNetworkClassifier.pick(
                listOf(
                    snapshot(1, isVpn = false, linkAddresses = listOf("100.64.0.5")),
                    snapshot(2, linkAddresses = listOf("10.8.0.2")),
                ),
            ),
        )
    }

    @Test
    fun `tailscale ipv6 beats ipv4 only`() {
        val ipv4Only = snapshot(1, linkAddresses = listOf("100.64.0.5"), firstSeenElapsedMs = 900L)
        val dualStack = snapshot(2, linkAddresses = listOf("100.64.0.6", "fd7a:115c:a1e0::6"), firstSeenElapsedMs = 100L)
        assertEquals(2L, TailnetNetworkClassifier.pick(listOf(ipv4Only, dualStack))?.handle)
    }

    @Test
    fun `magic dns resolver breaks a tie`() {
        val plain = snapshot(1, linkAddresses = listOf("100.64.0.5"), firstSeenElapsedMs = 900L)
        val magicDns = snapshot(
            2,
            linkAddresses = listOf("100.64.0.6"),
            dnsServers = listOf("100.100.100.100"),
            firstSeenElapsedMs = 100L,
        )
        assertEquals(2L, TailnetNetworkClassifier.pick(listOf(plain, magicDns))?.handle)
    }

    @Test
    fun `most recent then highest handle break remaining ties`() {
        val older = snapshot(7, linkAddresses = listOf("100.64.0.5"), firstSeenElapsedMs = 100L)
        val newer = snapshot(3, linkAddresses = listOf("100.64.0.6"), firstSeenElapsedMs = 200L)
        assertEquals(3L, TailnetNetworkClassifier.pick(listOf(older, newer))?.handle)
        val sameTimeLow = snapshot(4, linkAddresses = listOf("100.64.0.5"), firstSeenElapsedMs = 200L)
        val sameTimeHigh = snapshot(9, linkAddresses = listOf("100.64.0.6"), firstSeenElapsedMs = 200L)
        assertEquals(9L, TailnetNetworkClassifier.pick(listOf(sameTimeLow, sameTimeHigh))?.handle)
    }
}
