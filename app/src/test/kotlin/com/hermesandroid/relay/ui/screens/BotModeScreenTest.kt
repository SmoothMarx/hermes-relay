package com.hermesandroid.relay.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.BotGroupMessage
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.data.BotGroupRoom
import com.hermesandroid.relay.data.BotModeRoster
import com.hermesandroid.relay.data.BotModeState
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.BotSessionSummary
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.SessionActivityOwner
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.ui.components.botModeActivityKey
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.botModeActivitySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-432dpi")
class BotModeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `tabs filter bots and read only groups without changing workspace`() {
        render()

        compose.onNodeWithText("Lucy").assertExists()
        compose.onNodeWithText("Launch Council").assertExists()

        compose.onNodeWithText("Groups").performClick()

        compose.onNodeWithText("Lucy").assertDoesNotExist()
        compose.onNodeWithText("Launch Council").assertExists()
        compose.onNodeWithText("Read only").assertExists()
    }

    @Test
    fun `bot and group rows dispatch their exact owners`() {
        var botOwner: String? = null
        var groupOwner: String? = null
        render(
            onOpenBot = { botOwner = it.profile.name },
            onOpenGroup = { groupOwner = it.key },
        )

        compose.onNodeWithText("Lucy").performClick()
        compose.onNodeWithText("Launch Council").performClick()

        assertEquals("default", botOwner)
        assertEquals("id:launch", groupOwner)
    }

    @Test
    fun `gateway scope filters union without switching active connection`() {
        render(selectedGatewayId = "lab")

        compose.onNodeWithText("Researcher").assertExists()
        compose.onNodeWithText("Lucy").assertDoesNotExist()
    }

    @Test
    fun `same named bots on different gateways render and open their own conversation`() {
        val opened = mutableListOf<String>()
        render(onOpenBot = { opened += it.route!!.connectionId })

        compose.onNodeWithText("Lucy").performClick()
        compose.onNodeWithText("Researcher").performClick()

        assertEquals(listOf("home", "lab"), opened)
    }

    @Test
    fun `same named active bots on different gateways render and open their own owner`() {
        val opened = mutableListOf<String>()
        render(nowMs = NOW, onOpenBot = { opened += it.route!!.connectionId })

        compose.onAllNodesWithText("Lucy")[0].performClick()
        compose.onAllNodesWithText("Researcher")[0].performClick()

        assertEquals(listOf("home", "lab"), opened)
    }

    @Test
    fun `opening progress only replaces the selected owners preview`() {
        render(openingRoute = BotGatewayRouteKey("home", "default"))

        compose.onNodeWithText("Drafted a rollout plan").assertDoesNotExist()
        compose.onNodeWithText("Findings ready").assertExists()
    }

    @Test
    fun `item identity survives presentation refresh and separates delimiter containing owners`() {
        val bot = state().roster.bots.first()
        val refreshed = bot.copy(
            displayName = "Renamed",
            handle = "new-handle",
            stale = true,
            route = BotGatewayRoute(bot.route!!.key, "New label", "new-install-metadata"),
            canonicalSession = BotSessionSummary(id = "compressed-tip"),
        )
        assertEquals(bot.lazyItemKey, refreshed.lazyItemKey)
        assertNotEquals(
            bot.copy(route = BotGatewayRoute(BotGatewayRouteKey("a:b", "c"), "Same label")).lazyItemKey,
            bot.copy(route = BotGatewayRoute(BotGatewayRouteKey("a", "b:c"), "Same label")).lazyItemKey,
        )
        assertNotEquals(
            bot.lazyItemKey,
            bot.copy(route = BotGatewayRoute(BotGatewayRouteKey("home", "other"), "Hermes")).lazyItemKey,
        )
        assertNotEquals(bot.lazyItemKey, bot.copy(route = null).lazyItemKey)
    }

    @Test
    fun `a profile row with an attributable state shows the state instead of recency`() {
        render(
            screenState = state(bots = listOf(lucy()), groups = emptyList()),
            activityStates = mapOf(
                botModeActivityKey("default", "bot-root")!! to SessionActivityState.NeedsInput,
            ),
        )

        compose.onNodeWithContentDescription("Needs input").assertExists()
        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a profile row with nothing attributable shows recency and no state`() {
        render(screenState = state(bots = listOf(lucy()), groups = emptyList()))

        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Needs input").assertCountEquals(0)
    }

    @Test
    fun `a profile owned by another connection is never lit`() {
        render(
            activityStates = mapOf(
                botModeActivityKey("default", "bot-root")!! to SessionActivityState.NeedsInput,
                botModeActivityKey("default", "researcher-root")!! to SessionActivityState.Working,
            ),
        )

        compose.onNodeWithContentDescription("Needs input").assertExists()
        compose.onAllNodesWithContentDescription("Working").assertCountEquals(0)
    }

    @Test
    fun `a state outside the profile vocabulary leaves the row on recency`() {
        render(
            screenState = state(bots = listOf(lucy()), groups = emptyList()),
            activityStates = mapOf(
                botModeActivityKey("default", "bot-root")!! to SessionActivityState.BackgroundWork,
            ),
        )

        compose.onAllNodesWithContentDescription("Background work").assertCountEquals(0)
        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(1)
    }

    @Test
    fun `a stale row keeps the offline marker and is never lit`() {
        render(
            screenState = state(
                bots = listOf(
                    lucy(stale = true),
                    builder(),
                    researcher(stale = true),
                ),
                groups = emptyList(),
            ),
            activityStates = mapOf(
                botModeActivityKey("default", "bot-root")!! to SessionActivityState.NeedsInput,
                botModeActivityKey("builder", "builder-bot-chat")!! to SessionActivityState.Working,
            ),
        )

        // The marker the surface already had, kept on a stale row of the active connection and on a
        // stale row of another gateway; the light is withheld on both, including where a state keyed
        // to that row's own profile and id is in the snapshot (the same fixtures without `stale` are
        // lit — see `a profile row with an attributable state shows the state instead of recency`).
        // The non-stale Builder row is the control: the light path still reaches a row in this same
        // render, so the two absences above are the guard and not a broken fixture.
        compose.onAllNodesWithText("Offline").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Needs input").assertCountEquals(0)
        compose.onNodeWithContentDescription("Working").assertExists()
        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(2)
    }

    @Test
    fun `the screen lights a row from the active connections own snapshot`() {
        // The snapshot half of T2.3 end to end: the producer's own snapshot builder (T1.4) → the
        // surface's scope → the row's guard and its status policy — so a state the app can actually
        // publish is what lights the row, not a hand-built map. The screen's own two-line wire
        // (`BotModeScreen`: collect the flow, hand `botModeActivityStates` to the content) is not
        // reachable from a test that does not build a real `ConnectionViewModel`; a lit row in the
        // running app stays the §7 device row.
        val snapshot = botModeActivitySnapshot(
            connectionId = "home",
            states = mapOf(
                SessionActivityOwner.of("home", "default", "bot-root") to SessionActivityState.NeedsInput,
            ),
            ambiguous = false,
            complete = true,
        )

        render(
            screenState = state(bots = listOf(lucy()), groups = emptyList()),
            activityStates = botModeActivityStates(snapshot, "home"),
        )

        compose.onNodeWithContentDescription("Needs input").assertExists()
        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a snapshot stated for another connection lights nothing`() {
        // The snapshot is scoped to the connection whose chat published it (T1.4) and D3 states
        // status only for the connection that has one, so a snapshot left over from a previous
        // active connection is not offered to the rows — two connections may carry the same profile
        // name and a session id key would otherwise match across gateways.
        val snapshot = botModeActivitySnapshot(
            connectionId = "lab",
            states = mapOf(
                SessionActivityOwner.of("lab", "default", "bot-root") to SessionActivityState.NeedsInput,
            ),
            ambiguous = false,
            complete = true,
        )

        val states = botModeActivityStates(snapshot, "home")
        render(
            screenState = state(bots = listOf(lucy()), groups = emptyList()),
            activityStates = states,
        )

        assertEquals(emptyMap<String, SessionActivityState>(), states)
        compose.onAllNodesWithContentDescription("Needs input").assertCountEquals(0)
        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(1)
    }

    @Test
    fun `no snapshot or no active connection states nothing`() {
        val snapshot = botModeActivitySnapshot(
            connectionId = "home",
            states = mapOf(
                SessionActivityOwner.of("home", "default", "bot-root") to SessionActivityState.NeedsInput,
            ),
            ambiguous = false,
            complete = true,
        )

        assertEquals(emptyMap<String, SessionActivityState>(), botModeActivityStates(null, "home"))
        assertEquals(emptyMap<String, SessionActivityState>(), botModeActivityStates(snapshot, null))
        assertEquals(emptyMap<String, SessionActivityState>(), botModeActivityStates(snapshot, "  "))
    }

    @Test
    fun `a settled and ambiguous pass shows the disclosure above the rows`() {
        // T2.4 — the disclosure's trigger, rendered: a completed pass that could not attribute every
        // live row. The rows keep their own honesty beside it (here neither profile row is
        // attributable, so both show recency), which is exactly what the line exists to qualify.
        render(
            screenState = state(bots = listOf(lucy()), groups = emptyList()),
            activityComplete = true,
            activityAmbiguous = true,
        )

        compose.onNodeWithText(context.getString(R.string.bot_mode_activity_disclosure)).assertExists()
        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Needs input").assertCountEquals(0)
    }

    @Test
    fun `a pass that never completed shows no disclosure`() {
        // Finding 0a — `complete` and `ambiguous` are two axes: an unsettled picture (a transient
        // failure, or a host without the active-list RPC) states nothing at all, not even the caveat.
        render(activityComplete = false, activityAmbiguous = true)

        compose.onAllNodesWithText(
            context.getString(R.string.bot_mode_activity_disclosure),
        ).assertCountEquals(0)
    }

    @Test
    fun `a complete pass that attributed every live row shows no disclosure`() {
        // The other axis: when every live row was attributed, a lightless row is authoritative and
        // needs no caveat, so the header stays clean.
        render(
            activityStates = mapOf(
                botModeActivityKey("default", "bot-root")!! to SessionActivityState.NeedsInput,
            ),
            activityComplete = true,
            activityAmbiguous = false,
        )

        compose.onAllNodesWithText(
            context.getString(R.string.bot_mode_activity_disclosure),
        ).assertCountEquals(0)
        compose.onNodeWithContentDescription("Needs input").assertExists()
    }

    @Test
    fun `the disclosure is dismissible`() {
        render(
            screenState = state(bots = listOf(lucy(), builder()), groups = emptyList()),
            activityComplete = true,
            activityAmbiguous = true,
        )

        val disclosure = context.getString(R.string.bot_mode_activity_disclosure)
        compose.onNodeWithText(disclosure).assertExists()

        compose.onNodeWithContentDescription(context.getString(R.string.common_dismiss)).performClick()

        compose.onAllNodesWithText(disclosure).assertCountEquals(0)
        // Dismissing the caveat leaves the rows alone: the light path and the recency are unchanged.
        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(2)
    }

    @Test
    fun `the disclosure is scoped to the active connection and needs both axes`() {
        // The wire half of T2.4, as pure assertions: the two facts are read off the snapshot stated
        // for the connection on screen (the same D3 scope rule the states map already applies — a
        // snapshot left over from a previous active connection settles nothing here either), and the
        // rule itself needs `complete` **and** `ambiguous` (never one alone).
        val snapshot = botModeActivitySnapshot(
            connectionId = "home",
            states = mapOf(
                SessionActivityOwner.of("home", "default", "bot-root") to SessionActivityState.NeedsInput,
            ),
            ambiguous = true,
            complete = true,
        )

        assertEquals(snapshot, botModeActivitySnapshotScope(snapshot, "home"))
        assertNull(botModeActivitySnapshotScope(snapshot, "lab"))
        assertNull(botModeActivitySnapshotScope(snapshot, null))
        assertNull(botModeActivitySnapshotScope(snapshot, "  "))
        assertNull(botModeActivitySnapshotScope(null, "home"))

        assertTrue(botModeActivityDisclosureVisible(complete = true, ambiguous = true))
        assertFalse(botModeActivityDisclosureVisible(complete = true, ambiguous = false))
        assertFalse(botModeActivityDisclosureVisible(complete = false, ambiguous = true))
        assertFalse(botModeActivityDisclosureVisible(complete = false, ambiguous = false))
    }

    private fun render(
        onOpenBot: (BotRosterEntry) -> Unit = {},
        onOpenGroup: (BotGroupRoom) -> Unit = {},
        selectedGatewayId: String? = null,
        nowMs: Long = NOW + 200_000L,
        openingRoute: BotGatewayRouteKey? = null,
        screenState: BotModeState = state(),
        activityStates: Map<String, SessionActivityState> = emptyMap(),
        activityComplete: Boolean = false,
        activityAmbiguous: Boolean = false,
    ) {
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                BotModeContent(
                    state = screenState,
                    connections = listOf(connection(), labConnection()),
                    activeConnection = connection(),
                    selectedGatewayId = selectedGatewayId,
                    openingRoute = openingRoute,
                    onBack = {},
                    onRefresh = {},
                    onSelectGateway = {},
                    onOpenBot = onOpenBot,
                    onOpenGroup = onOpenGroup,
                    onNewBot = {},
                    nowMs = nowMs,
                    activityStates = activityStates,
                    activityComplete = activityComplete,
                    activityAmbiguous = activityAmbiguous,
                )
            }
        }
    }

    private fun state(
        bots: List<BotRosterEntry> = listOf(lucy(), researcher()),
        groups: List<BotGroupRoom> = listOf(launchCouncil()),
    ) = BotModeState(
        roster = BotModeRoster(
            bots = bots,
            groups = groups,
        ),
    )

    private fun lucy(stale: Boolean = false) = BotRosterEntry(
        profile = Profile(name = "default", model = "gpt-5.6", description = "Operator"),
        displayName = "Lucy",
        route = com.hermesandroid.relay.data.BotGatewayRoute(
            key = com.hermesandroid.relay.data.BotGatewayRouteKey("home", "default"),
            connectionLabel = "Hermes",
        ),
        stale = stale,
        canonicalSession = BotSessionSummary(
            id = "bot-root",
            preview = "Drafted a rollout plan",
            lastActiveAtMs = NOW - 10_000L,
        ),
    )

    /** A row on the active connection whose state is attributable — the control beside the stale rows. */
    private fun builder() = BotRosterEntry(
        profile = Profile(name = "builder", model = "gpt-5.6", description = "Builds"),
        displayName = "Builder",
        route = BotGatewayRoute(
            key = BotGatewayRouteKey("home", "builder"),
            connectionLabel = "Hermes",
        ),
        canonicalSession = BotSessionSummary(
            id = "builder-bot-chat",
            preview = "Build complete. 3 tests added.",
            lastActiveAtMs = NOW - 5_000L,
        ),
    )

    private fun researcher(stale: Boolean = false) = BotRosterEntry(
        profile = Profile(name = "default", model = "gpt-5.6", description = "Research"),
        displayName = "Researcher",
        route = com.hermesandroid.relay.data.BotGatewayRoute(
            key = com.hermesandroid.relay.data.BotGatewayRouteKey("lab", "default"),
            connectionLabel = "Lab server",
        ),
        stale = stale,
        canonicalSession = BotSessionSummary(
            id = "researcher-root",
            preview = "Findings ready",
            lastActiveAtMs = NOW - 30_000L,
        ),
    )

    private fun launchCouncil() = BotGroupRoom(
        key = "id:launch",
        roomId = "launch",
        name = "Launch Council",
        messages = listOf(
            BotGroupMessage(
                id = "message-1",
                senderName = "Lucy",
                senderKind = "member",
                text = "Rollout is clear",
                atMs = NOW - 20_000L,
            ),
        ),
        sourceConnectionIds = setOf("home", "lab"),
    )

    private fun connection() = Connection(
        id = "home",
        label = "Hermes",
        apiServerUrl = "",
        relayUrl = "",
        dashboardUrl = "https://example.invalid",
        tokenStoreKey = "test",
    )

    private fun labConnection() = Connection(
        id = "lab",
        label = "Lab server",
        apiServerUrl = "",
        relayUrl = "",
        dashboardUrl = "https://lab.invalid",
        tokenStoreKey = "test-lab",
    )

    private companion object {
        const val NOW = 1_777_000_000_000L
    }
}
