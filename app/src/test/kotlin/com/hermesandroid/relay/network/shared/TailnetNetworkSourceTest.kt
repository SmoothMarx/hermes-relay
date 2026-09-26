package com.hermesandroid.relay.network.shared

import android.net.Network
import io.mockk.mockk
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test double for [TailnetNetworkSource], shared by the tailnet tests in this package.
 * [bindSocket] and [lookup] behave like the production source: absent network fails closed.
 */
internal class FakeTailnetNetworkSource : TailnetNetworkSource {
    val mutableStatus = MutableStateFlow<TailnetNetworkStatus>(TailnetNetworkStatus.Absent)
    override val status: StateFlow<TailnetNetworkStatus> = mutableStatus
    val dnsTable = mutableMapOf<String, List<InetAddress>>()
    var bindCalls = 0
    var lookupCalls = 0
    var startCalls = 0
    var stopCalls = 0

    fun setPresent() {
        mutableStatus.value = TailnetNetworkStatus.Present(
            network = mockk<Network>(relaxed = true),
            linkAddresses = listOf(TailnetAddresses.ipLiteralOrNull("100.64.0.2")!!),
        )
    }

    fun setAbsent() {
        mutableStatus.value = TailnetNetworkStatus.Absent
    }

    override fun start() {
        startCalls += 1
    }

    override fun stop() {
        stopCalls += 1
    }

    override fun bindSocket(socket: Socket) {
        if (mutableStatus.value is TailnetNetworkStatus.Absent) {
            throw TailnetUnavailableException(TailnetBlockReason.TailnetNetworkAbsent, "absent")
        }
        bindCalls += 1
    }

    override fun lookup(host: String): List<InetAddress> {
        lookupCalls += 1
        val resolver: ((String) -> List<InetAddress>)? =
            if (mutableStatus.value is TailnetNetworkStatus.Present) ::resolveFromTable else null
        return TailnetLookupRules.lookup(host, resolver)
    }

    private fun resolveFromTable(name: String): List<InetAddress> =
        dnsTable[name] ?: throw UnknownHostException(name)
}

class TailnetNetworkSourceTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun ip(literal: String): InetAddress = TailnetAddresses.ipLiteralOrNull(literal)!!

    @Test
    fun `tailnet literal is returned without calling the resolver`() {
        var calls = 0
        val result = TailnetLookupRules.lookup("100.64.0.9") { calls += 1; emptyList() }
        assertEquals(listOf(ip("100.64.0.9")), result)
        assertEquals(0, calls)
        assertEquals(listOf(ip("100.64.0.9")), TailnetLookupRules.lookup("100.64.0.9", null))
    }

    @Test
    fun `non tailnet literal is refused`() {
        val error = assertThrows(TailnetUnavailableException::class.java) {
            TailnetLookupRules.lookup("10.0.0.20") { listOf(ip("10.0.0.20")) }
        }
        assertEquals(TailnetBlockReason.HostNotOnTailnet, error.reason)
    }

    @Test
    fun `name without a tailnet network fails closed`() {
        val error = assertThrows(TailnetUnavailableException::class.java) {
            TailnetLookupRules.lookup("host.tail0000.ts.net", null)
        }
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, error.reason)
    }

    @Test
    fun `resolver answers are filtered to tailnet addresses`() {
        val result = TailnetLookupRules.lookup("host.tail0000.ts.net") {
            listOf(ip("203.0.113.7"), ip("100.64.0.9"), ip("fd7a:115c:a1e0::9"))
        }
        assertEquals(listOf(ip("100.64.0.9"), ip("fd7a:115c:a1e0::9")), result)
    }

    @Test
    fun `resolver answers without a tailnet address are refused`() {
        val error = assertThrows(TailnetUnavailableException::class.java) {
            TailnetLookupRules.lookup("host.tail0000.ts.net") { listOf(ip("203.0.113.7")) }
        }
        assertEquals(TailnetBlockReason.HostNotOnTailnet, error.reason)
    }

    @Test
    fun `failed resolution is reported as not on the tailnet`() {
        val error = assertThrows(TailnetUnavailableException::class.java) {
            TailnetLookupRules.lookup("host.tail0000.ts.net") { throw UnknownHostException("nope") }
        }
        assertEquals(TailnetBlockReason.HostNotOnTailnet, error.reason)
    }

    @Test
    fun `tailnet exception is an io exception`() {
        val error: Throwable = TailnetUnavailableException(TailnetBlockReason.HostUnreachable, "x")
        assertTrue(error is IOException)
    }

    @Test
    fun `deferred source fails closed before attach`() {
        val deferred = DeferredTailnetNetworkSource(scope)
        assertFalse(deferred.isAttached)
        assertEquals(TailnetNetworkStatus.Absent, deferred.status.value)
        Socket().use { socket ->
            val error = assertThrows(TailnetUnavailableException::class.java) { deferred.bindSocket(socket) }
            assertEquals(TailnetBlockReason.TailnetNetworkAbsent, error.reason)
        }
        val lookupError = assertThrows(TailnetUnavailableException::class.java) {
            deferred.lookup("host.tail0000.ts.net")
        }
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, lookupError.reason)
        assertEquals(listOf(ip("100.64.0.9")), deferred.lookup("100.64.0.9"))
    }

    @Test
    fun `deferred source delegates and mirrors status after attach`() {
        val deferred = DeferredTailnetNetworkSource(scope)
        val fake = FakeTailnetNetworkSource()
        deferred.attach(fake)
        assertTrue(deferred.isAttached)
        fake.setPresent()
        assertSame(fake.status.value, deferred.status.value)
        Socket().use { socket -> deferred.bindSocket(socket) }
        assertEquals(1, fake.bindCalls)
        fake.dnsTable["host.tail0000.ts.net"] = listOf(ip("100.64.0.9"))
        assertEquals(listOf(ip("100.64.0.9")), deferred.lookup("host.tail0000.ts.net"))
        deferred.start()
        deferred.stop()
        assertEquals(1, fake.startCalls)
        assertEquals(1, fake.stopCalls)
        fake.setAbsent()
        assertEquals(TailnetNetworkStatus.Absent, deferred.status.value)
    }

    @Test
    fun `second attach is ignored`() {
        val deferred = DeferredTailnetNetworkSource(scope)
        val first = FakeTailnetNetworkSource()
        val second = FakeTailnetNetworkSource()
        deferred.attach(first)
        deferred.attach(second)
        first.setPresent()
        Socket().use { socket -> deferred.bindSocket(socket) }
        assertEquals(1, first.bindCalls)
        assertEquals(0, second.bindCalls)
    }
}
