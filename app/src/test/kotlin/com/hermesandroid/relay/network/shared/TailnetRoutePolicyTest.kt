package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.BrokerEndpoint
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.ProxyEndpoint
import com.hermesandroid.relay.data.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetRoutePolicyTest {

    private fun candidate(
        role: String,
        priority: Int = 0,
        api: ApiEndpoint? = null,
        relay: RelayEndpoint? = null,
        dashboard: DashboardEndpoint? = null,
        proxy: ProxyEndpoint? = null,
        broker: BrokerEndpoint? = null,
        experimental: Boolean = false,
    ): EndpointCandidate = EndpointCandidate(
        role = role,
        priority = priority,
        api = api,
        relay = relay,
        dashboard = dashboard,
        proxy = proxy,
        broker = broker,
        experimental = experimental,
    )

    private val tailnetDashboard = DashboardEndpoint("https://host.tail0000.ts.net")
    private val tailnetRelay = RelayEndpoint("wss://host.tail0000.ts.net:10443/relay")
    private val lanRelay = RelayEndpoint("ws://10.0.0.20:8767")

    @Test
    fun `tailscale route with only tailnet urls is eligible`() {
        assertTrue(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", dashboard = tailnetDashboard, relay = tailnetRelay),
            ),
        )
    }

    @Test
    fun `lan role pointing at a cgnat address is eligible`() {
        assertTrue(
            TailnetRoutePolicy.isEligible(
                candidate(
                    "lan",
                    api = ApiEndpoint(host = "100.64.0.7", port = 8642),
                    relay = RelayEndpoint("ws://100.64.0.7:8767"),
                ),
            ),
        )
    }

    @Test
    fun `bracketed tailscale ipv6 api host is eligible`() {
        assertTrue(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", api = ApiEndpoint(host = "[fd7a:115c:a1e0::7]", port = 8642)),
            ),
        )
    }

    @Test
    fun `tailscale role pointing at a lan address is not eligible`() {
        assertFalse(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", api = ApiEndpoint(host = "10.0.0.20", port = 8642)),
            ),
        )
    }

    @Test
    fun `public funnel route is never eligible`() {
        assertFalse(TailnetRoutePolicy.isEligible(candidate("public", dashboard = tailnetDashboard)))
        assertFalse(TailnetRoutePolicy.isEligible(candidate("PUBLIC", dashboard = tailnetDashboard)))
    }

    @Test
    fun `mixed tailnet and lan surfaces are not eligible`() {
        assertFalse(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", dashboard = tailnetDashboard, relay = lanRelay),
            ),
        )
    }

    @Test
    fun `experimental route is not eligible`() {
        assertFalse(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", dashboard = tailnetDashboard, experimental = true),
            ),
        )
    }

    @Test
    fun `broker on a public host is not eligible`() {
        val broker = BrokerEndpoint(
            url = "wss://broker.example.com/v1/connect",
            hostId = "AAAAAAAAAAAAAAAAAAAAAA",
            credentialKind = "route",
            token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )
        assertFalse(
            TailnetRoutePolicy.isEligible(
                candidate(
                    "outbound_broker",
                    proxy = ProxyEndpoint(url = "https://host.tail0000.ts.net:8443"),
                    broker = broker,
                ),
            ),
        )
    }

    @Test
    fun `route without any url is not eligible`() {
        assertFalse(TailnetRoutePolicy.isEligible(candidate("tailscale")))
    }

    @Test
    fun `blank urls are ignored`() {
        assertTrue(
            TailnetRoutePolicy.isEligible(
                candidate(
                    "tailscale",
                    dashboard = DashboardEndpoint("   "),
                    relay = tailnetRelay,
                ),
            ),
        )
    }

    @Test
    fun `filter keeps only eligible routes in their original order`() {
        val first = candidate("tailscale", priority = 0, dashboard = tailnetDashboard)
        val lan = candidate("lan", priority = 0, relay = lanRelay)
        val second = candidate("lan", priority = 1, relay = RelayEndpoint("ws://100.64.0.9:8767"))
        val public = candidate("public", priority = 2, dashboard = DashboardEndpoint("https://hermes.example.com"))
        val third = candidate("tailscale", priority = 0, relay = tailnetRelay)
        assertEquals(
            listOf(first, second, third),
            TailnetRoutePolicy.filter(listOf(first, lan, second, public, third)),
        )
    }

    @Test
    fun `has eligible route`() {
        val lan = candidate("lan", relay = lanRelay)
        val tailnet = candidate("tailscale", dashboard = tailnetDashboard)
        assertTrue(TailnetRoutePolicy.hasEligibleRoute(listOf(lan, tailnet)))
        assertFalse(TailnetRoutePolicy.hasEligibleRoute(listOf(lan)))
        assertFalse(TailnetRoutePolicy.hasEligibleRoute(emptyList()))
    }
}
