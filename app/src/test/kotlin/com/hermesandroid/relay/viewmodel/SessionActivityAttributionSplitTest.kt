package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayClientHarness
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.upstream.models.MessageItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Bot Mode probe: how much of a process-wide `session.active_list` snapshot the
 * client can actually attribute for conversations it is **not** attached to.
 *
 * The per-row state a sibling tab or sub-menu row would show is derived from
 * [ChatViewModel.backgroundSessionActivityStates], which is projected from the
 * activity registry. That registry is fed by `resolveGatewayActiveSessions`
 * (`ChatViewModel.kt`, resolver) plus the directory the app hands it through
 * [ChatViewModel.updateSessionActivityDirectory]. The resolver is deliberately
 * fail-closed: a row is attributed only when ownership is exact, and the split
 * measured here is
 *
 *  - **attributed** — the attached conversation row without explicit `profile`
 *    metadata (its stored id has exactly one client-side owner and that owner is
 *    the attached one), and a sibling row that carries explicit `profile`
 *    metadata whose stored id has exactly one client-side owner;
 *  - **unresolved** — a sibling row **without** `profile` metadata even though
 *    the client's directory knows exactly one owner for it, a row whose stored id
 *    is ambiguous in the directory (two owners for one stored id), and a row with
 *    no directory owner at all. Unresolved rows contribute **no** key: the app
 *    never invents an owner, so no badge/light can be lit for them.
 *
 * A snapshot containing an unresolved row is also incomplete, and an incomplete
 * snapshot must not settle (remove) rows that *were* unambiguously owned before
 * — otherwise one unattributable row would silently blank whole tabs.
 *
 * Finally, [SessionActivityState.BackgroundWork] is a **single-slot** overlay:
 * it is projected only for the conversation bound to
 * `chatHandler.currentSessionId` and only while
 * `gatewayProcessController.ownsSnapshot(sessionId, …)` holds for that exact
 * session. A running process list therefore reaches the attached conversation's
 * key and can never describe a non-attached one; it also survives that
 * conversation's turn settling to idle, because the overlay is process
 * evidence, not turn evidence.
 *
 * All three properties are read through the app's own published seams
 * ([ChatViewModel.updateSessionActivityDirectory],
 * [ChatViewModel.backgroundSessionActivityStates], [ChatViewModel.backgroundProcesses])
 * against a scripted [GatewayClientHarness], so the probe adds no production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionActivityAttributionSplitTest {

    private lateinit var gatewayHarness: GatewayClientHarness
    private lateinit var apiServer: MockWebServer
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var gatewayClient: GatewayChatClient
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        gatewayHarness = GatewayClientHarness()
        apiServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(404)
            }
            start()
        }
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
        // [ATTACHED] is the conversation this client is bound to; every
        // assertion about "a conversation that is not the attached one" is
        // relative to it.
        handler = ChatHandler().also { it.setSessionId(ATTACHED) }
        viewModel = ChatViewModel().also {
            it.initialize(HermesApiClient(apiServer.url("/").toString(), "test-key"), handler)
            it.streamingEndpoint = "gateway"
            it.setSelectedProfileProvider { Profile(name = PROFILE, model = "probe-model") }
            it.setSessionProfileNameProvider { PROFILE }
            it.setProfileMessageLoader { Result.success(emptyList<MessageItem>()) }
            it.switchProfileContext(PROFILE_CONTEXT, ATTACHED)
            it.updateGatewayClient(gatewayClient)
        }
        // Bind the attached conversation's live session: this is what makes the
        // process snapshot the attached conversation's (and what admits the
        // passive `session.active_list` observation without a readiness barrier,
        // which is the T0.2 probe's subject, not this one).
        assertTrue(runBlocking { gatewayClient.prewarmAwait(ATTACHED) })
        gatewayHarness.awaitServerSocket()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        viewModel.updateGatewayClient(null)
        gatewayClient.shutdown()
        gatewayScope.cancel()
        gatewayHarness.shutdown()
        apiServer.shutdown()
    }

    @Test
    fun siblingRowsSplitIntoAttributedAndUnresolvedWithNoInventedOwner() {
        gatewayHarness.activeSessionListPayload = activeSessions(
            // The attached conversation, no `profile` metadata and a runtime id
            // the client does not know: its stored id has exactly one
            // client-side owner and that owner is the current one, so the
            // resolver's current-owner fallback attributes it.
            activeRow(runtimeId = "live-attached", storedSessionId = ATTACHED, status = "working"),
            // A sibling that does carry its `profile`: exactly one directory
            // owner for that stored id, so it is attributed.
            activeRow(
                runtimeId = "live-sibling",
                storedSessionId = SIBLING,
                status = "waiting",
                profile = PROFILE,
            ),
            // A sibling row with NO `profile` metadata: the client's directory
            // knows exactly one owner for it, but it is not the attached owner,
            // so the resolver stays closed and the row is unresolved.
            activeRow(runtimeId = "live-no-metadata", storedSessionId = NAMED, status = "working"),
            // Two directory owners share this stored id: ambiguous, unresolved.
            activeRow(runtimeId = "live-shared", storedSessionId = SHARED, status = "working"),
            // No directory owner at all, even with `profile` metadata: unresolved.
            activeRow(
                runtimeId = "live-unlisted",
                storedSessionId = UNLISTED,
                status = "working",
                profile = PROFILE,
            ),
        )

        viewModel.updateSessionActivityDirectory(attributionDirectory())
        viewModel.setChatVisible(true)

        awaitCondition {
            viewModel.backgroundSessionActivityStates.value == mapOf(
                "$PROFILE:$ATTACHED" to SessionActivityState.Working,
                "$PROFILE:$SIBLING" to SessionActivityState.NeedsInput,
            )
        }

        // The split, read off the projection the UI consumes: exactly the two
        // attributed rows, each with the state its runtime status maps to.
        val states = viewModel.backgroundSessionActivityStates.value
        assertEquals(SessionActivityState.Working, states["$PROFILE:$ATTACHED"])
        assertEquals(SessionActivityState.NeedsInput, states["$PROFILE:$SIBLING"])
        // The three unresolved rows have no key: an unattributable row shows
        // nothing, and no owner is invented for it.
        assertNull(states["$PROFILE:$NAMED"])
        assertNull(states["$PROFILE:$SHARED"])
        assertNull(states["$PROFILE:$UNLISTED"])
        assertEquals(2, states.size)
    }

    @Test
    fun incompleteSnapshotNeverRemovesUnambiguouslyOwnedRows() {
        val attributed = mapOf(
            "$PROFILE:$ATTACHED" to SessionActivityState.Working,
            "$PROFILE:$SIBLING" to SessionActivityState.NeedsInput,
        )
        gatewayHarness.activeSessionListPayload = activeSessions(
            activeRow(runtimeId = "live-attached", storedSessionId = ATTACHED, status = "working"),
            activeRow(
                runtimeId = "live-sibling",
                storedSessionId = SIBLING,
                status = "waiting",
                profile = PROFILE,
            ),
        )

        viewModel.updateSessionActivityDirectory(attributionDirectory())
        viewModel.setChatVisible(true)
        awaitCondition { viewModel.backgroundSessionActivityStates.value == attributed }

        // Next snapshot: the two owned rows are gone from upstream and one
        // unattributable row remains. Because one row is unresolved the
        // snapshot is incomplete, so it must not settle the rows it no longer
        // mentions.
        gatewayHarness.activeSessionListPayload = activeSessions(
            activeRow(
                runtimeId = "live-unlisted",
                storedSessionId = UNLISTED,
                status = "working",
                profile = PROFILE,
            ),
        )
        val pollsBeforeAmbiguous = gatewayHarness.rpcLog.count { it.first == "session.active_list" }
        viewModel.requestSessionActivityRefresh()
        awaitNextPoll(pollsBeforeAmbiguous)

        awaitCondition { viewModel.backgroundSessionActivityStates.value == attributed }
        assertEquals(attributed, viewModel.backgroundSessionActivityStates.value)
        assertNull(viewModel.backgroundSessionActivityStates.value["$PROFILE:$UNLISTED"])

        // Control: the very same "the owned rows are absent" snapshot, but with
        // every row attributable, is complete. Now the absent rows DO settle —
        // which is how the assertion above stays honest: the survival in the
        // incomplete snapshot is fail-closed completeness, not a poll that
        // never ran. The sibling, which no process snapshot can describe, loses
        // its key outright; the attached conversation settles its *turn* to idle
        // and keeps a key only because its own session still has a running
        // background process.
        gatewayHarness.activeSessionListPayload = activeSessions(
            activeRow(
                runtimeId = "live-named",
                storedSessionId = NAMED,
                status = "working",
                profile = PROFILE,
            ),
        )
        val pollsBeforeComplete = gatewayHarness.rpcLog.count { it.first == "session.active_list" }
        viewModel.requestSessionActivityRefresh()
        awaitNextPoll(pollsBeforeComplete)

        awaitCondition {
            viewModel.backgroundSessionActivityStates.value == mapOf(
                "$PROFILE:$NAMED" to SessionActivityState.Working,
                "$PROFILE:$ATTACHED" to SessionActivityState.BackgroundWork,
            )
        }
        assertNull(viewModel.backgroundSessionActivityStates.value["$PROFILE:$SIBLING"])
    }

    @Test
    fun backgroundWorkNeverDescribesANonAttachedConversation() {
        viewModel.updateSessionActivityDirectory(attributionDirectory())
        viewModel.setChatVisible(true)

        // A running process is genuinely in the attached session's snapshot.
        awaitCondition {
            gatewayHarness.rpcLog.any { (method, _) -> method == "process.list" } &&
                viewModel.backgroundProcesses.value.any { it.isRunning }
        }
        val processList = gatewayHarness.rpcLog.first { it.first == "process.list" }.second
        assertEquals(
            LIVE_SESSION_ID,
            (processList["session_id"] as? JsonPrimitive)?.contentOrNull,
        )
        assertEquals(LIVE_SESSION_ID, gatewayClient.currentLiveSessionId(ATTACHED))

        // Both the attached conversation and a sibling row are attributable,
        // and both are idle: only the attached one may be described by the
        // single-slot background-process overlay.
        gatewayHarness.activeSessionListPayload = activeSessions(
            activeRow(runtimeId = "live-attached", storedSessionId = ATTACHED, status = "idle"),
            activeRow(
                runtimeId = "live-sibling",
                storedSessionId = SIBLING,
                status = "idle",
                profile = PROFILE,
            ),
            activeRow(
                runtimeId = "live-no-metadata",
                storedSessionId = NAMED,
                status = "working",
            ),
        )
        viewModel.requestSessionActivityRefresh()
        val pollsBeforeSplit = gatewayHarness.rpcLog.count { it.first == "session.active_list" }
        awaitNextPoll(pollsBeforeSplit)

        awaitCondition {
            viewModel.backgroundSessionActivityStates.value["$PROFILE:$ATTACHED"] ==
                SessionActivityState.BackgroundWork
        }

        // A's key carries BackgroundWork — so the projection really ran and
        // really targeted the attached conversation.
        val states = viewModel.backgroundSessionActivityStates.value
        assertEquals(SessionActivityState.BackgroundWork, states["$PROFILE:$ATTACHED"])
        // B is attributable and idle, so a leaked process overlay would have
        // created its key as BackgroundWork. It did not: answered **no**, a
        // non-attached conversation cannot be described by BackgroundWork.
        assertNull(states["$PROFILE:$SIBLING"])
        assertNull(states["$PROFILE:$NAMED"])
        assertNull(states["$PROFILE:$UNLISTED"])
        assertNull(states["$PROFILE:$SHARED"])
        assertEquals(mapOf("$PROFILE:$ATTACHED" to SessionActivityState.BackgroundWork), states)
        assertFalse(
            gatewayHarness.rpcLog.any { (method, params) ->
                method == "process.list" &&
                    (params["session_id"] as? JsonPrimitive)?.contentOrNull != LIVE_SESSION_ID
            },
        )
    }

    private fun activeSessions(vararg rows: JsonObject) = buildJsonObject {
        put("sessions", buildJsonArray { rows.forEach { add(it) } })
    }

    private fun activeRow(
        runtimeId: String,
        storedSessionId: String,
        status: String,
        profile: String? = null,
    ) = buildJsonObject {
        put("id", runtimeId)
        put("session_key", storedSessionId)
        put("status", status)
        put("last_active", 1.0)
        if (profile != null) put("profile", profile)
    }

    /**
     * The client's own directory for this connection: the attached
     * conversation, two singly-owned siblings, and one stored id owned by two
     * different profiles.
     */
    private fun attributionDirectory(): List<Pair<String, String>> = listOf(
        PROFILE to ATTACHED,
        PROFILE to SIBLING,
        PROFILE to NAMED,
        PROFILE to SHARED,
        OTHER_PROFILE to SHARED,
    )

    private fun awaitNextPoll(previousPolls: Int) {
        awaitCondition {
            gatewayHarness.rpcLog.count { it.first == "session.active_list" } > previousPolls
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            // Advance Robolectric's paused main clock so the activity poll can
            // resume as it does on-device.
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(
            "condition not met; states=${viewModel.backgroundSessionActivityStates.value}",
            condition(),
        )
    }

    private companion object {
        const val CONNECTION_ID = "connection-a"
        const val PROFILE = "default"
        const val OTHER_PROFILE = "research"
        const val ATTACHED = "stored-attached"
        const val SIBLING = "stored-sibling"
        const val NAMED = "stored-named"
        const val SHARED = "stored-shared"
        const val UNLISTED = "stored-unlisted"
        const val LIVE_SESSION_ID = "live-resumed"
        val PROFILE_CONTEXT = AgentDisplay.profileContextKey(CONNECTION_ID, PROFILE)
    }
}
