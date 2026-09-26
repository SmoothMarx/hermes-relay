package com.hermesandroid.relay.network.shared

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetAddressesTest {

    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    @Test
    fun `magic dns names are tailnet hosts`() {
        assertTrue(TailnetAddresses.isTailnetHost("host.tail0000.ts.net"))
        assertTrue(TailnetAddresses.isTailnetHost("HOST.TAIL0000.TS.NET."))
        assertTrue(TailnetAddresses.isTailnetHost("  host.example.ts.net  "))
    }

    @Test
    fun `suffix alone and lookalike names are not tailnet hosts`() {
        assertFalse(TailnetAddresses.isTailnetHost("ts.net"))
        assertFalse(TailnetAddresses.isTailnetHost(".ts.net"))
        assertFalse(TailnetAddresses.isTailnetHost("hostts.net"))
        assertFalse(TailnetAddresses.isTailnetHost("host.ts.net.example.com"))
        assertFalse(TailnetAddresses.isTailnetHost("hermes.example.com"))
        assertFalse(TailnetAddresses.isTailnetHost(""))
        assertFalse(TailnetAddresses.isTailnetHost("   "))
        assertFalse(TailnetAddresses.isTailnetHost(null))
    }

    @Test
    fun `cgnat range boundaries`() {
        assertTrue(TailnetAddresses.isTailnetHost("100.64.0.0"))
        assertTrue(TailnetAddresses.isTailnetHost("100.64.0.1"))
        assertTrue(TailnetAddresses.isTailnetHost("100.127.255.255"))
        assertFalse(TailnetAddresses.isTailnetHost("100.63.255.255"))
        assertFalse(TailnetAddresses.isTailnetHost("100.128.0.0"))
        assertFalse(TailnetAddresses.isTailnetHost("10.0.0.20"))
        assertFalse(TailnetAddresses.isTailnetHost("203.0.113.7"))
    }

    @Test
    fun `malformed ipv4 literals are rejected`() {
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0.1.5"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0.256"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.-1.1"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0.1a"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0.0001"))
    }

    @Test
    fun `tailscale ipv6 range`() {
        assertTrue(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0::1"))
        assertTrue(TailnetAddresses.isTailnetHost("[fd7a:115c:a1e0::1]"))
        assertTrue(TailnetAddresses.isTailnetHost("FD7A:115C:A1E0:AB12:4843:CD96:6258:B240"))
        assertTrue(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0::1%tun0"))
        assertTrue(TailnetAddresses.isTailnetHost("::ffff:100.64.0.1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e1::1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c::1"))
        assertFalse(TailnetAddresses.isTailnetHost("::1"))
    }

    @Test
    fun `malformed ipv6 literals are rejected`() {
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0:::1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0::1::2"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0:1:2:3:4:5:6"))
        assertFalse(TailnetAddresses.isTailnetHost("gd7a:115c:a1e0::1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0:12345::1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0:-1::1"))
    }

    @Test
    fun `tailnet address objects`() {
        assertTrue(TailnetAddresses.isTailnetAddress(v4(100, 64, 0, 1)))
        assertTrue(TailnetAddresses.isTailnetAddress(v4(100, 127, 255, 255)))
        assertFalse(TailnetAddresses.isTailnetAddress(v4(100, 128, 0, 0)))
        assertFalse(TailnetAddresses.isTailnetAddress(v4(10, 0, 0, 20)))
        val tailnetV6 = TailnetAddresses.ipLiteralOrNull("fd7a:115c:a1e0::5")
        assertTrue(tailnetV6 is Inet6Address)
        assertTrue(TailnetAddresses.isTailnetAddress(tailnetV6!!))
        val otherV6 = TailnetAddresses.ipLiteralOrNull("fd7a:115c:a1e1::5")
        assertFalse(TailnetAddresses.isTailnetAddress(otherV6!!))
    }

    @Test
    fun `tailnet urls`() {
        assertTrue(TailnetAddresses.isTailnetUrl("wss://host.tail0000.ts.net:10443/relay"))
        assertTrue(TailnetAddresses.isTailnetUrl("WSS://HOST.TAIL0000.TS.NET/"))
        assertTrue(TailnetAddresses.isTailnetUrl("ws://100.100.1.2:8767"))
        assertTrue(TailnetAddresses.isTailnetUrl("http://[fd7a:115c:a1e0::1]:9119/"))
        assertTrue(TailnetAddresses.isTailnetUrl("https://host.example.ts.net"))
        assertFalse(TailnetAddresses.isTailnetUrl("https://hermes.example.com"))
        assertFalse(TailnetAddresses.isTailnetUrl("http://10.0.0.20:8642"))
        assertFalse(TailnetAddresses.isTailnetUrl("https://host.ts.net.example.com/"))
        assertFalse(TailnetAddresses.isTailnetUrl("not a url"))
        assertFalse(TailnetAddresses.isTailnetUrl("100.64.0.1:8767"))
        assertFalse(TailnetAddresses.isTailnetUrl(""))
        assertFalse(TailnetAddresses.isTailnetUrl(null))
    }

    @Test
    fun `ip literal parsing never resolves names`() {
        val started = System.nanoTime()
        assertNull(TailnetAddresses.ipLiteralOrNull("nonexistent.invalid"))
        assertNull(TailnetAddresses.ipLiteralOrNull("host.tail0000.ts.net"))
        assertFalse(TailnetAddresses.isTailnetHost("nonexistent.invalid"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("predicates must not do DNS (took ${elapsedMs}ms)", elapsedMs < 1_000)
        assertTrue(TailnetAddresses.ipLiteralOrNull("100.64.0.1") is Inet4Address)
        assertTrue(TailnetAddresses.ipLiteralOrNull("[fd7a:115c:a1e0::1]") is Inet6Address)
    }
}
