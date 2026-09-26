package com.hermesandroid.relay.network.shared

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesClientsTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val source = FakeTailnetNetworkSource()
    private val enforcer = TailnetEnforcer.create(source, scope)
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        job.cancel()
    }

    private fun client(): OkHttpClient = HermesClients.build(
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false),
        enforcer,
    )

    private fun guardCount(builder: OkHttpClient.Builder): Int =
        builder.networkInterceptors().count { it is TailnetAddressGuard }

    @Test
    fun `decoration is idempotent`() {
        val builder = OkHttpClient.Builder()
            .enforceTailnetPolicy(enforcer)
            .enforceTailnetPolicy(enforcer)
        assertEquals(1, guardCount(builder))
    }

    @Test
    fun `derived builders keep the decoration without duplicating it`() {
        val base = client()
        assertTrue(base.socketFactory is TailnetSocketFactory)
        assertTrue(base.dns is TailnetDns)
        val derived = base.newBuilder()
        assertEquals(1, guardCount(derived))
        assertEquals(1, guardCount(derived.enforceTailnetPolicy(enforcer)))
        val derivedClient = derived.build()
        assertTrue(derivedClient.socketFactory is TailnetSocketFactory)
        assertTrue(derivedClient.dns is TailnetDns)
    }

    @Test
    fun `policy off leaves requests unchanged`() {
        server.enqueue(MockResponse().setBody("ok"))
        client().newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `policy on rejects a non tailnet peer before any request is sent`() {
        source.setPresent()
        enforcer.setPolicy("conn-a", true)
        server.enqueue(MockResponse().setBody("ok"))
        val literalUrl = "http://127.0.0.1:${server.port}/"
        assertThrows(IOException::class.java) {
            client().newCall(Request.Builder().url(literalUrl).build()).execute().close()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `policy on refuses names that do not resolve on the tailnet`() {
        source.setPresent()
        enforcer.setPolicy("conn-a", true)
        server.enqueue(MockResponse().setBody("ok"))
        assertThrows(IOException::class.java) {
            client().newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the same client follows the toggle per request`() {
        val shared = client()
        source.setPresent()
        enforcer.setPolicy("conn-a", true)
        assertThrows(IOException::class.java) {
            shared.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/").build()).execute().close()
        }
        enforcer.setPolicy("conn-a", false)
        server.enqueue(MockResponse().setBody("ok"))
        shared.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `tailnet dns answers are used while enforcing`() {
        source.setPresent()
        source.dnsTable["host.tail0000.ts.net"] = listOf(InetAddress.getByAddress(byteArrayOf(100, 64, 0, 9)))
        enforcer.setPolicy("conn-a", true)
        assertEquals(
            listOf(InetAddress.getByAddress(byteArrayOf(100, 64, 0, 9))),
            client().dns.lookup("host.tail0000.ts.net"),
        )
    }
}
