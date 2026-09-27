package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BotConversation
import com.hermesandroid.relay.data.SessionActivityOwner
import com.hermesandroid.relay.data.SessionActivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Bot Mode status policy contract: one derivation shared by the profile row, the tab and the
 * chip. Pins the priority order, the states that may never light a profile row, the unlit answer
 * when nothing is attributable, the full conversation vocabulary, and the exact key form the
 * activity projection publishes.
 */
class BotModeStatusPolicyTest {

    /**
     * The profile half the activity projection holds for a profile name: the owner it builds from
     * the app's wire name, with the projection's own server-default mapping applied.
     */
    private fun projectedProfile(profileName: String?): String =
        SessionActivityOwner.of(
            connectionId = "conn-a",
            profile = AgentDisplay.profileSessionKey(profileName),
            storedSessionId = "s1",
        ).profile.takeUnless { it == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY } ?: "default"

    @Test
    fun `needs input outranks starting and working on a profile row`() {
        assertEquals(
            SessionActivityState.NeedsInput,
            profileLight(
                states = mapOf(
                    "research:a" to SessionActivityState.Working,
                    "research:b" to SessionActivityState.Starting,
                    "research:c" to SessionActivityState.NeedsInput,
                ),
                profileKey = "research",
                keys = listOf("a", "b", "c"),
            ),
        )
        assertEquals(
            SessionActivityState.Starting,
            profileLight(
                states = mapOf(
                    "research:a" to SessionActivityState.Working,
                    "research:b" to SessionActivityState.Starting,
                ),
                profileKey = "research",
                keys = listOf("a", "b"),
            ),
        )
        assertEquals(
            SessionActivityState.Working,
            profileLight(
                states = mapOf("research:a" to SessionActivityState.Working),
                profileKey = "research",
                keys = listOf("a"),
            ),
        )
    }

    @Test
    fun `a quiet sibling never weakens the light another conversation earned`() {
        assertEquals(
            SessionActivityState.Working,
            profileLight(
                states = mapOf("research:a" to SessionActivityState.Working),
                profileKey = "research",
                keys = listOf("a", "b", "c"),
            ),
        )
    }

    @Test
    fun `repeating one owner states what it is and invents nothing`() {
        assertEquals(
            SessionActivityState.NeedsInput,
            profileLight(
                states = mapOf("research:a" to SessionActivityState.NeedsInput),
                profileKey = "research",
                keys = listOf("a", "a"),
            ),
        )
    }

    @Test
    fun `background work never lights a profile row`() {
        assertNull(
            profileLight(
                states = mapOf("research:a" to SessionActivityState.BackgroundWork),
                profileKey = "research",
                keys = listOf("a"),
            ),
        )
        // A foreground state on another conversation still wins: background work is simply not
        // stated at profile level.
        assertEquals(
            SessionActivityState.Working,
            profileLight(
                states = mapOf(
                    "research:a" to SessionActivityState.BackgroundWork,
                    "research:b" to SessionActivityState.Working,
                ),
                profileKey = "research",
                keys = listOf("a", "b"),
            ),
        )
    }

    @Test
    fun `transport states never light a profile row`() {
        listOf(SessionActivityState.Checking, SessionActivityState.Unavailable).forEach { state ->
            assertNull(
                profileLight(
                    states = mapOf("research:a" to state),
                    profileKey = "research",
                    keys = listOf("a"),
                ),
            )
        }
    }

    @Test
    fun `a missing owner leaves the row unlit`() {
        assertNull(
            profileLight(
                states = mapOf("research:a" to SessionActivityState.NeedsInput),
                profileKey = "research",
                keys = listOf(null, "", "   "),
            ),
        )
        assertNull(profileLight(emptyMap<String, SessionActivityState>(), "research", emptyList<String?>()))
        assertNull(
            profileLight(
                states = mapOf("other:shared" to SessionActivityState.NeedsInput),
                profileKey = "research",
                keys = listOf("shared"),
            ),
        )
    }

    @Test
    fun `a duplicated owner leaves the row unlit instead of guessing`() {
        // The attribution split's ambiguity shape: a stored id with two client-side owners is
        // attributed to neither, so the projection publishes no key for it at all.
        assertNull(
            profileLight(
                states = mapOf("research:sibling" to SessionActivityState.NeedsInput),
                profileKey = "research",
                keys = listOf("shared"),
            ),
        )
    }

    @Test
    fun `a bare session id never matches a light or a chip`() {
        assertNull(
            profileLight(
                states = mapOf("a" to SessionActivityState.NeedsInput),
                profileKey = "research",
                keys = listOf("a"),
            ),
        )
        // The chip refuses a key with no profile half even when the map happens to hold that
        // literal key: a bare id is never a conversation identity.
        assertNull(conversationChip(mapOf("a" to SessionActivityState.Working), "a"))
        assertNull(conversationChip(mapOf(":a" to SessionActivityState.Working), ":a"))
        assertNull(conversationChip(mapOf("a" to SessionActivityState.Working), "a:b"))
        assertEquals(
            SessionActivityState.Working,
            conversationChip(mapOf("research:a" to SessionActivityState.Working), "research:a"),
        )
    }

    @Test
    fun `the conversation chip keeps every reachable state, checking included`() {
        SessionActivityState.entries.forEach { state ->
            assertEquals(state, conversationChip(mapOf("default:s" to state), "default:s"))
        }
        assertNull(conversationChip(mapOf("default:s" to SessionActivityState.Working), "default:t"))
        assertNull(conversationChip(emptyMap(), "default:s"))
        assertNull(conversationChip(mapOf("default:s" to SessionActivityState.Working), null))
    }

    @Test
    fun `the composite key is the one the projection publishes`() {
        val names = listOf(
            "research",
            "  Research ",
            "BOT",
            "default",
            AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            "",
            "   ",
            null,
        )
        names.forEach { name ->
            val key = botModeActivityKey(name, " s1 ")
            assertEquals("${projectedProfile(name)}:s1", key)
            // The same form the conversation model publishes, so a row, a tab and a chip can never
            // key one conversation differently.
            assertEquals(
                BotConversation(
                    connectionId = "conn-a",
                    profileName = name.orEmpty(),
                    storedSessionId = "s1",
                    title = "Weekly digest",
                ).key(),
                key,
            )
        }
    }

    @Test
    fun `the wire default and a profile named default project the same way`() {
        // The projection's behaviour, mirrored rather than reinterpreted: both addresses resolve to
        // the one published key, so a state found through either name is the same state.
        assertEquals("default:s1", botModeActivityKey(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, "s1"))
        assertEquals("default:s1", botModeActivityKey(null, "s1"))
        assertEquals("default:s1", botModeActivityKey("default", "s1"))

        val states = mapOf("default:s1" to SessionActivityState.NeedsInput)
        assertEquals(
            SessionActivityState.NeedsInput,
            profileLight(states, AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, listOf("s1")),
        )
        assertEquals(SessionActivityState.NeedsInput, profileLight(states, "default", listOf("s1")))
        assertEquals(
            SessionActivityState.NeedsInput,
            conversationChip(states, botModeActivityKey(null, "s1")),
        )

        // A session id that cannot name a conversation yields no key at all.
        assertNull(botModeActivityKey("research", null))
        assertNull(botModeActivityKey("research", "   "))
        assertNull(conversationChip(states, null))
    }
}
