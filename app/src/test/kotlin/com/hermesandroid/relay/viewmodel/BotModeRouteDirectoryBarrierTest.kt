package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayClientHarness
import com.hermesandroid.relay.network.upstream.HermesApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Bot Mode probe: the route's own session directory and the Dashboard readiness
 * barrier that admits Gateway socket work.
 *
 * The Bot Mode conversation route is not the active connection. Its lister is
 * built against **that route's** Dashboard client, so the profile-scoped
 * `GET /api/sessions?profile=` read must be served by the route's own Dashboard
 * while the active connection's Dashboard is never asked for a directory. The
 * same wiring is what arms [ChatViewModel]'s readiness barrier: until the exact
 * owner's directory read succeeds, a cold client must not mint a WebSocket
 * ticket and must not run the `session.active_list` poll, because a lister that
 * publishes success for the wrong owner keeps the barrier closed and no status
 * can ever appear.
 *
 * Both directions are asserted through the app's own published seams
 * ([ChatViewModel.setProfileSessionLister], [ChatViewModel.sessionDirectoryReadyEvents],
 * [ChatViewModel.ownsSessionDirectoryReadyEvent]), so the probe needs no production
 * change: owner identity is read off the route's own directory-ready event together
 * with the predicate by which the ViewModel itself decides an event belongs to the
 * state it published.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotModeRouteDirectoryBarrierTest {

    private lateinit var gatewayHarness: GatewayClientHarness
    private lateinit var routeDashboard: MockWebServer
    private lateinit var apiServer: MockWebServer
    private lateinit var routeDashboardClient: DashboardApiClient
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var gatewayClient: GatewayChatClient
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        gatewayHarness = GatewayClientHarness()
        routeDashboard = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return if (path.startsWith("/api/sessions")) {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(routeDirectoryBody())
                    } else {
                        // Session enrichment (PR decoration) is optional host
                        // metadata and must not fail the directory read.
                        MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        apiServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(404)
            }
            start()
        }
        routeDashboardClient = DashboardApiClient(
            baseUrl = routeDashboard.url("/").toString().trimEnd('/'),
            okHttpClient = OkHttpClient(),
        )
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        gatewayClient = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                baseUrl = gatewayHarness.server.url("/").toString().trimEnd('/'),
                okHttpClient = OkHttpClient(),
            ),
            okHttpClient = OkHttpClient(),
            // Match production ordering: Gateway callbacks are posted from the
            // OkHttp WebSocket thread onto Android's main looper.
            callbackDispatcher = { block ->
                Handler(Looper.getMainLooper()).post(block)
            },
            scope = gatewayScope,
        )
        // The route's owner has no session bound yet: this is the fresh-draft
        // edge, where the directory read — not a stored session — is what
        // decides whether Gateway work is admitted.
        handler = ChatHandler()
        viewModel = ChatViewModel().also {
            it.initialize(HermesApiClient(apiServer.url("/").toString(), "test-key"), handler)
            it.streamingEndpoint = "gateway"
            it.updateGatewayClient(gatewayClient)
        }
    }

    @After
    fun tearDown() {
        viewModel.updateGatewayClient(null)
        gatewayClient.shutdown()
        gatewayScope.cancel()
        gatewayHarness.shutdown()
        routeDashboard.shutdown()
        apiServer.shutdown()
    }

    @Test
    fun routeDirectoryReadIsServedByTheRoutesOwnDashboardNeverTheActiveConnections() {
        val listedProfiles = ConcurrentLinkedQueue<String?>()

        viewModel.setSelectedProfileProvider { routeProfile() }
        viewModel.setSessionProfileNameProvider { ROUTE_PROFILE }
        viewModel.setProfileSessionLister { profileName ->
            listedProfiles += profileName
            routeDashboardClient.listSessions(profile = profileName)
        }
        viewModel.switchProfileContext(routeContextKey(), sessionId = null)
        viewModel.setChatVisible(true)
        viewModel.refreshSessions()

        awaitCondition { viewModel.sessions.value.any { it.sessionId == ROUTE_SESSION_ID } }
        awaitCondition { gatewayHarness.ticketMints.get() >= 1 }

        assertEquals(
            listOf(ROUTE_SESSION_ID),
            viewModel.sessions.value.map { it.sessionId },
        )
        assertTrue("lister was never asked", listedProfiles.isNotEmpty())
        assertTrue(
            "lister was asked for a foreign profile: $listedProfiles",
            listedProfiles.all { it == ROUTE_PROFILE },
        )

        val routePaths = recordedPaths(routeDashboard)
        assertTrue(
            "route dashboard never served the profile-scoped read: $routePaths",
            routePaths.any {
                it.startsWith("/api/sessions") && it.contains("profile=$ROUTE_PROFILE")
            },
        )
        // The active connection's Dashboard is the same host that minted the
        // WebSocket ticket, so it was reachable and in use — it was simply
        // never asked for a directory.
        val activePaths = recordedPaths(gatewayHarness.server)
        assertTrue(
            "active connection's dashboard was asked for a session directory: $activePaths",
            activePaths.any { it.startsWith("/api/auth/ws-ticket") } &&
                activePaths.none { it.startsWith("/api/sessions") },
        )
    }

    @Test
    fun coldClientAdmitsSocketObservationAndActivityPollOnlyAfterTheRouteDirectorySucceeds() {
        val listedProfiles = ConcurrentLinkedQueue<String?>()
        val readyEvents = ConcurrentLinkedQueue<Pair<SessionDirectoryReadyEvent, Boolean>>()
        val readStarted = CompletableDeferred<Unit>()
        val readGate = CompletableDeferred<Unit>()
        // Ownership is evaluated where it is meaningful — when the event is
        // published — so a later directory generation cannot mask the owner
        // this event actually carried.
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.sessionDirectoryReadyEvents.collect { event ->
                readyEvents += event to viewModel.ownsSessionDirectoryReadyEvent(event)
            }
        }

        try {
            viewModel.setSelectedProfileProvider { routeProfile() }
            viewModel.setSessionProfileNameProvider { ROUTE_PROFILE }
            viewModel.setProfileSessionLister { profileName ->
                listedProfiles += profileName
                readStarted.complete(Unit)
                val rows = routeDashboardClient.listSessions(profile = profileName)
                // Hold the refresh in flight: the barrier must still be closed
                // while the route's own read has not succeeded.
                readGate.await()
                rows
            }
            viewModel.switchProfileContext(routeContextKey(), sessionId = null)

            // Cold client, visible Chat, lister installed: every Gateway edge
            // that could open the socket is deferred by the directory barrier.
            viewModel.setChatVisible(true)
            viewModel.prewarmGateway()
            viewModel.requestSessionActivityRefresh()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("cold client minted a WebSocket ticket", 0, gatewayHarness.ticketMints.get())
            assertTrue("cold client opened a socket", gatewayHarness.serverSockets.isEmpty())
            assertTrue(
                "gated client ran an activity poll: ${gatewayHarness.rpcLog.map { it.first }}",
                gatewayHarness.rpcLog.none { it.first == "session.active_list" },
            )
            assertTrue(viewModel.sessions.value.isEmpty())

            // Route-scoped read in flight — still deferred.
            viewModel.refreshSessions()
            awaitCondition { readStarted.isCompleted }
            assertEquals(
                "in-flight directory read released Gateway work early",
                0,
                gatewayHarness.ticketMints.get(),
            )
            assertTrue(gatewayHarness.rpcLog.none { it.first == "session.active_list" })

            // The exact owner's read succeeds: the barrier clears.
            readGate.complete(Unit)
            awaitCondition { gatewayHarness.ticketMints.get() >= 1 }
            gatewayHarness.awaitServerSocket()
            awaitCondition { gatewayHarness.rpcLog.any { it.first == "session.active_list" } }
            awaitCondition { readyEvents.isNotEmpty() }

            assertTrue("lister was never asked", listedProfiles.isNotEmpty())
            assertTrue(
                "lister was asked for a foreign profile: $listedProfiles",
                listedProfiles.all { it == ROUTE_PROFILE },
            )
            val (event, owned) = readyEvents.first()
            assertEquals(routeContextKey(), event.contextKey)
            assertEquals(ROUTE_PROFILE, event.profileName)
            assertTrue("directory-ready event named a foreign owner", owned)
        } finally {
            collectorScope.cancel()
        }
    }

    private fun routeContextKey(): String =
        AgentDisplay.profileContextKey(ROUTE_CONNECTION_ID, ROUTE_PROFILE)

    /** The route's own Dashboard answer to the profile-scoped directory read. */
    private fun routeDirectoryBody(): String = buildJsonObject {
        put("sessions", buildJsonArray {
            add(buildJsonObject {
                put("id", ROUTE_SESSION_ID)
                put("title", "Route conversation")
                put("profile", ROUTE_PROFILE)
            })
        })
    }.toString()

    private fun routeProfile(): Profile =
        Profile(name = ROUTE_PROFILE, model = "probe-model", description = "Route probe")

    /** Drains a mock dashboard's recorded requests so absent paths are provable. */
    private fun recordedPaths(server: MockWebServer): List<String> {
        val paths = mutableListOf<String>()
        while (true) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: break
            paths += request.path.orEmpty()
        }
        return paths
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            // Advance Robolectric's paused main clock so the directory refresh
            // and the activity poll can resume as they do on-device.
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met; last sessions=${viewModel.sessions.value}", condition())
    }

    private companion object {
        const val ROUTE_CONNECTION_ID = "connection-route"
        const val ROUTE_PROFILE = "routebot"
        const val ROUTE_SESSION_ID = "route-session-1"
    }
}
