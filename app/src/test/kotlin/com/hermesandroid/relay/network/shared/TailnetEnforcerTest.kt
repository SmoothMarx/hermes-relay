package com.hermesandroid.relay.network.shared

import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetEnforcerTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val source = FakeTailnetNetworkSource()
    private val enforcer = TailnetEnforcer.create(source, scope)

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun ip(literal: String): InetAddress = TailnetAddresses.ipLiteralOrNull(literal)!!

    private class CountingDns(private val answer: List<InetAddress>) : Dns {
        var calls = 0

        override fun lookup(hostname: String): List<InetAddress> {
            calls += 1
            return answer
        }
    }

    @Test
    fun `policy off is a pass through`() {
        assertFalse(enforcer.isEnforcing())
        enforcer.socketFactory().createSocket().use { }
        assertEquals(0, source.bindCalls)
        val fallback = CountingDns(listOf(ip("10.0.0.20")))
        assertEquals(listOf(ip("10.0.0.20")), enforcer.dns(fallback).lookup("hermes.example.com"))
        assertEquals(1, fallback.calls)
        assertEquals(0, source.lookupCalls)
        assertNull(enforcer.checkUrl("http://10.0.0.20:8642"))
        assertNull(enforcer.checkUrl(null))
    }

    @Test
    fun `enabled without an active connection does not enforce`() {
        enforcer.setPolicy(null, true)
        assertFalse(enforcer.isEnforcing())
        assertFalse(enforcer.enforcing.value)
    }

    @Test
    fun `policy changes bump the generation once per change`() {
        assertEquals(0L, enforcer.generation.value)
        enforcer.setPolicy("conn-a", true)
        assertTrue(enforcer.isEnforcing())
        assertTrue(enforcer.enforcing.value)
        assertEquals("conn-a", enforcer.activeConnectionId)
        assertEquals(1L, enforcer.generation.value)
        enforcer.setPolicy("conn-a", true)
        assertEquals(1L, enforcer.generation.value)
        enforcer.setPolicy("conn-b", true)
        assertEquals(2L, enforcer.generation.value)
        enforcer.setPolicy("conn-b", false)
        assertFalse(enforcer.isEnforcing())
        assertEquals(3L, enforcer.generation.value)
    }

    @Test
    fun `network status changes bump the generation`() {
        assertEquals(0L, enforcer.generation.value)
        source.setPresent()
        assertEquals(1L, enforcer.generation.value)
        assertTrue(enforcer.networkStatus.value is TailnetNetworkStatus.Present)
        source.setAbsent()
        assertEquals(2L, enforcer.generation.value)
    }

    @Test
    fun `enforcing with no tailnet network refuses sockets and names`() {
        enforcer.setPolicy("conn-a", true)
        val socketError = assertThrows(TailnetUnavailableException::class.java) {
            enforcer.socketFactory().createSocket()
        }
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, socketError.reason)
        val fallback = CountingDns(listOf(ip("10.0.0.20")))
        val dnsError = assertThrows(TailnetUnavailableException::class.java) {
            enforcer.dns(fallback).lookup("host.tail0000.ts.net")
        }
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, dnsError.reason)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `enforcing with a tailnet network binds every socket`() {
        source.setPresent()
        enforcer.setPolicy("conn-a", true)
        enforcer.socketFactory().createSocket().use { }
        enforcer.socketFactory().createSocket().use { }
        assertEquals(2, source.bindCalls)
    }

    @Test
    fun `enforcing dns returns only tailnet answers and never the fallback`() {
        source.setPresent()
        source.dnsTable["host.tail0000.ts.net"] = listOf(ip("203.0.113.7"), ip("100.64.0.9"))
        enforcer.setPolicy("conn-a", true)
        val fallback = CountingDns(listOf(ip("10.0.0.20")))
        assertEquals(listOf(ip("100.64.0.9")), enforcer.dns(fallback).lookup("host.tail0000.ts.net"))
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `toggle is honoured per call by the same factory and dns`() {
        source.setPresent()
        val factory = enforcer.socketFactory()
        val fallback = CountingDns(listOf(ip("10.0.0.20")))
        val dns = enforcer.dns(fallback)
        enforcer.setPolicy("conn-a", true)
        factory.createSocket().use { }
        assertEquals(1, source.bindCalls)
        enforcer.setPolicy("conn-a", false)
        factory.createSocket().use { }
        assertEquals(1, source.bindCalls)
        assertEquals(listOf(ip("10.0.0.20")), dns.lookup("hermes.example.com"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `check url table while enforcing`() {
        enforcer.setPolicy("conn-a", true)
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, enforcer.checkUrl("https://host.tail0000.ts.net"))
        assertEquals(TailnetBlockReason.HostNotOnTailnet, enforcer.checkUrl("http://10.0.0.20:8642"))
        source.setPresent()
        assertNull(enforcer.checkUrl("https://host.tail0000.ts.net"))
        assertNull(enforcer.checkUrl("ws://100.64.0.9:8767"))
        assertEquals(TailnetBlockReason.HostNotOnTailnet, enforcer.checkUrl("https://hermes.example.com"))
        assertEquals(TailnetBlockReason.HostNotOnTailnet, enforcer.checkUrl(null))
    }

    @Test
    fun `process enforcer is a singleton and register returns the same client`() {
        assertSame(TailnetEnforcer.get(), TailnetEnforcer.get())
        val client = OkHttpClient()
        assertSame(client, enforcer.register(client))
    }
}
