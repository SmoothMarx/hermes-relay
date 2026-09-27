package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.SessionActivityOwner
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.ui.components.botModeActivityKey
import com.hermesandroid.relay.ui.components.conversationChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bot Mode activity bridge (T1.4 / B3): the connection-scoped snapshot the profile list reads,
 * its key form, the two facts about the pass behind it, and the one-publisher rule.
 *
 * These are the failure modes that are silent on screen — a snapshot carrying another connection's
 * rows, a snapshot whose absent light claims "nothing is running" while the pass never completed,
 * and a Bot route overwriting the main chat's status — so each is asserted directly, together with
 * the surface lookups (`conversationChip`, the form T1.2 pins to the projection) that must hit the
 * keys this bridge publishes.
 *
 * The install line itself is `ChatViewModel.setBotModeActivityBridge` =
 * `bridge.takeIf { it.claim(this) }`, i.e. a refused installer ends up with no bridge at all and
 * therefore cannot publish; the claim/publish refusals below are the whole of that mechanism.
 */
class BotModeActivityBridgeTest {

    private val rowsOfOneConnection: Map<SessionActivityOwner, SessionActivityState> = mapOf(
        SessionActivityOwner.of("conn-a", "Default", "s-default") to
            SessionActivityState.NeedsInput,
        SessionActivityOwner.of(
            "conn-a",
            AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            "s-sentinel",
        ) to SessionActivityState.Working,
        SessionActivityOwner.of("conn-a", "research", "s-research") to
            SessionActivityState.BackgroundWork,
        SessionActivityOwner.of("conn-b", "research", "s-other-connection") to
            SessionActivityState.Working,
    )

    @Test
    fun `snapshot keys are the composite form the surface looks up`() {
        val snapshot = botModeActivitySnapshot(
            connectionId = "conn-a",
            states = rowsOfOneConnection,
            ambiguous = false,
            complete = true,
        )

        assertEquals("conn-a", snapshot?.connectionId)
        // Never a bare session id, and the server-default profile lands on the literal `default`.
        assertEquals(
            setOf("default:s-default", "default:s-sentinel", "research:s-research"),
            snapshot?.states?.keys,
        )
        // The keys are only correct if the surface's own lookups hit them.
        assertEquals(
            SessionActivityState.NeedsInput,
            conversationChip(snapshot!!.states, botModeActivityKey("default", "s-default")),
        )
        assertEquals(
            SessionActivityState.Working,
            conversationChip(
                snapshot.states,
                botModeActivityKey(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, "s-sentinel"),
            ),
        )
        assertEquals(
            SessionActivityState.BackgroundWork,
            conversationChip(snapshot.states, botModeActivityKey("Research", "s-research")),
        )
        // A bare id never matches, and another connection's row is not there at all (D3).
        assertNull(conversationChip(snapshot.states, "s-research"))
        assertNull(
            conversationChip(snapshot.states, botModeActivityKey("research", "s-other-connection")),
        )
    }

    @Test
    fun `an unsettled pass propagates as incomplete and an unattributable one as ambiguous`() {
        val settled = botModeActivitySnapshot(
            connectionId = "conn-a",
            states = emptyMap(),
            ambiguous = false,
            complete = true,
        )
        assertEquals(false, settled?.ambiguous)
        assertEquals(true, settled?.complete)

        // The pass completed but could not attribute every live row the host reported: the rows it
        // could not attribute carry no key, so only the disclosure may describe them (T2.4).
        val unattributable = botModeActivitySnapshot(
            connectionId = "conn-a",
            states = emptyMap(),
            ambiguous = true,
            complete = true,
        )
        assertEquals(true, unattributable?.ambiguous)
        assertEquals(true, unattributable?.complete)

        // A transient failure or an unsupported host: nothing may be stated from it, and the
        // disclosure is withheld because the picture it would qualify is not settled.
        val unsettled = botModeActivitySnapshot(
            connectionId = "conn-a",
            states = rowsOfOneConnection,
            ambiguous = true,
            complete = false,
        )
        assertFalse(unsettled!!.complete)
    }

    @Test
    fun `no active connection states nothing`() {
        assertNull(
            botModeActivitySnapshot(
                connectionId = null,
                states = rowsOfOneConnection,
                ambiguous = false,
                complete = true,
            ),
        )
        assertNull(
            botModeActivitySnapshot(
                connectionId = "   ",
                states = rowsOfOneConnection,
                ambiguous = false,
                complete = true,
            ),
        )
    }

    @Test
    fun `the snapshot clears when the connection changes`() {
        val bridge = BotModeActivityBridge(warn = {})
        val mainChat = Any()
        bridge.claim(mainChat)
        bridge.publish(
            mainChat,
            botModeActivitySnapshot(
                "conn-a",
                rowsOfOneConnection,
                ambiguous = false,
                complete = true,
            ),
        )
        assertEquals(
            setOf("default:s-default", "default:s-sentinel", "research:s-research"),
            bridge.snapshot.value?.states?.keys,
        )
        assertEquals("conn-a", bridge.snapshot.value?.connectionId)

        // The app re-scopes to conn-b: the previous connection's rows are gone even though the
        // registry still holds them, and the new scope has no completed pass yet.
        bridge.publish(
            mainChat,
            botModeActivitySnapshot(
                "conn-b",
                rowsOfOneConnection,
                ambiguous = false,
                complete = false,
            ),
        )
        assertEquals("conn-b", bridge.snapshot.value?.connectionId)
        // Only the new connection's row is there: no key of conn-a's survives the re-scope.
        assertEquals(setOf("research:s-other-connection"), bridge.snapshot.value!!.states.keys)
        assertNull(bridge.snapshot.value!!.states["default:s-default"])
        assertNull(bridge.snapshot.value!!.states["default:s-sentinel"])
        assertNull(bridge.snapshot.value!!.states["research:s-research"])
        assertFalse(bridge.snapshot.value!!.complete)
    }

    @Test
    fun `a second publisher cannot overwrite the main chat's snapshot`() {
        val refusals = mutableListOf<String>()
        val bridge = BotModeActivityBridge(warn = { line -> refusals += line })
        val mainChat = Any()
        val botRouteChat = Any()
        val owned = botModeActivitySnapshot(
            "conn-a",
            rowsOfOneConnection,
            ambiguous = false,
            complete = true,
        )
        val fromRoute = botModeActivitySnapshot(
            "conn-b",
            mapOf(
                SessionActivityOwner.of("conn-b", "research", "s-other-connection") to
                    SessionActivityState.NeedsInput,
            ),
            ambiguous = false,
            complete = true,
        )

        assertTrue(bridge.claim(mainChat))
        // The same publisher may be installed again (a re-bind is not a second publisher).
        assertTrue(bridge.claim(mainChat))
        bridge.publish(mainChat, owned)
        assertEquals(owned, bridge.snapshot.value)

        // A fake publisher installed for a Bot route loses both ways: it cannot claim, and its
        // snapshot is dropped — never silently, always with a log line.
        assertFalse(bridge.claim(botRouteChat))
        bridge.publish(botRouteChat, fromRoute)
        assertEquals(owned, bridge.snapshot.value)
        assertEquals(2, refusals.size)
        assertTrue(refusals.all { it.contains("Bot Mode activity") })
    }
}
