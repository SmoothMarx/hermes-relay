package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation key contract: the composite `profile:storedSessionId` form the activity
 * projection publishes — never a title, a handle or a bare session id — with the owner
 * triple staying distinct across connections.
 */
class BotConversationKeyTest {
    private fun conversation(
        connectionId: String = "conn-a",
        profileName: String = "research",
        storedSessionId: String = "stored-1",
        resolvedSessionId: String = storedSessionId,
        title: String = "Weekly digest",
        activityTimestamp: Long? = 1_700_000_000_000L,
        pinned: Boolean = false,
        archived: Boolean = false,
        messageCount: Int? = 12,
    ) = BotConversation(
        connectionId = connectionId,
        profileName = profileName,
        storedSessionId = storedSessionId,
        resolvedSessionId = resolvedSessionId,
        title = title,
        activityTimestamp = activityTimestamp,
        pinned = pinned,
        archived = archived,
        messageCount = messageCount,
    )

    @Test
    fun `key is the projection's profile colon stored session id`() {
        assertEquals("research:stored-1", conversation().key())
        assertEquals("research", conversation().profileKey())
    }

    @Test
    fun `key never carries a title, a handle or a bare session id`() {
        val row = conversation()
        assertEquals(row.key(), row.copy(title = "Renamed while unattended").key())
        assertFalse(row.key() == row.storedSessionId)
        assertFalse(row.key().contains("Weekly digest"))
        assertTrue(row.key().endsWith(":${row.storedSessionId}"))
    }

    @Test
    fun `key follows identity only, not presentation`() {
        val row = conversation()
        val refreshed = row.copy(
            title = "Renamed",
            activityTimestamp = null,
            pinned = true,
            archived = true,
            messageCount = null,
        )
        assertEquals(row.key(), refreshed.key())
    }

    @Test
    fun `the profile half is trimmed and lowercased like the wire`() {
        assertEquals("research:stored-1", conversation(profileName = "  Research ").key())
        assertEquals("bot:stored-1", conversation(profileName = "BOT").key())
    }

    @Test
    fun `the server default profile projects as default`() {
        listOf(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, "default", "", "   ").forEach { name ->
            val row = conversation(profileName = name)
            assertEquals("default:stored-1", row.key())
            assertEquals("default", row.profileKey())
        }
    }

    @Test
    fun `distinct conversations in one profile keep distinct keys`() {
        assertNotEquals(
            conversation(storedSessionId = "stored-1").key(),
            conversation(storedSessionId = "stored-2").key(),
        )
    }

    @Test
    fun `the owner triple stays distinct across connections`() {
        val here = conversation(connectionId = "conn-a")
        val elsewhere = conversation(connectionId = "conn-b")
        // The profile/session half is deliberately connection-free: it is the key the active
        // connection's projection publishes. The connection is carried beside it, and the pair
        // (connectionId, key) is what separates the two owners.
        assertEquals(here.key(), elsewhere.key())
        assertNotEquals(here.connectionId, elsewhere.connectionId)
        assertNotEquals(here, elsewhere)
        assertEquals(1, setOf(here.connectionId to here.key(), elsewhere.connectionId to elsewhere.key()).size)
    }

    @Test
    fun `the stored id is the default open id and the chat target carries both`() {
        val plain = conversation()
        assertEquals(plain.storedSessionId, plain.resolvedSessionId)
        assertEquals(
            BotChatTarget(storedSessionId = "stored-1", resolvedSessionId = "stored-1"),
            plain.chatTarget(),
        )

        val compressed = conversation(resolvedSessionId = "tip-9")
        assertEquals("tip-9", compressed.chatTarget().resolvedSessionId)
        assertEquals("stored-1", compressed.chatTarget().storedSessionId)
        // Opening follows the tip; the key stays on the stored id.
        assertEquals("research:stored-1", compressed.key())
    }
}
