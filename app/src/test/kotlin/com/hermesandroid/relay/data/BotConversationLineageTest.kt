package com.hermesandroid.relay.data

import com.hermesandroid.relay.network.upstream.models.SessionItem
import com.hermesandroid.relay.network.upstream.models.SessionListResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lineage contract: a directory row's `id` is a compression-chain **tip**, and the durable
 * registry row it belongs to is only on the wire as `_lineage_root_id`. Both ids have a job — the
 * tip is opened, the root is the badge/selection key — so both must survive the wire and the
 * mapping into [BotConversation], while an uncompressed row keeps exactly one identity.
 *
 * The decode uses the read path's own configuration (`DashboardApiClient.listSessions`), so a
 * field name that only works in a test would fail here.
 */
class BotConversationLineageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun row(payload: String): SessionItem = json.decodeFromString<SessionItem>(payload)

    /** A row upstream projected onto its chain tip (`_project_compression_tips`). */
    private fun compressionRow(): SessionItem = row(
        """
        {
          "id": "tip-3",
          "title": "Weekly digest",
          "message_count": 41,
          "pinned": 1,
          "archived": 0,
          "last_active": 1700000950.0,
          "_lineage_root_id": "root-1",
          "_lineage_ids": ["root-1", "mid-2", "tip-3"],
          "continuation_kind": "compression"
        }
        """.trimIndent(),
    )

    private fun conversation(of: SessionItem, profileName: String = "research"): BotConversation =
        BotConversation.fromDirectoryRow(
            connectionId = "conn-a",
            profileName = profileName,
            row = of,
        )

    @Test
    fun `the lineage wire keys decode onto the row`() {
        val item = compressionRow()
        assertEquals("tip-3", item.id)
        assertEquals("root-1", item.lineageRootId)
        assertEquals(listOf("root-1", "mid-2", "tip-3"), item.lineageIds)
        assertEquals("compression", item.continuationKind)
    }

    @Test
    fun `a compressed row carries both ids and they differ`() {
        val bot = conversation(compressionRow())
        assertEquals("root-1", bot.storedSessionId)
        assertEquals("tip-3", bot.resolvedSessionId)
        assertNotEquals(bot.storedSessionId, bot.resolvedSessionId)
        // The badge/selection key is the stored id; the open pair carries the tip.
        assertEquals("research:root-1", bot.key())
        assertEquals(BotChatTarget("root-1", "tip-3"), bot.chatTarget())
        assertEquals("Weekly digest", bot.title)
        assertEquals(41, bot.messageCount)
        assertTrue(bot.pinned)
        assertEquals(1_700_000_950_000L, bot.activityTimestamp)
    }

    @Test
    fun `the chain maps verbatim, in the order received`() {
        assertEquals(listOf("root-1", "mid-2", "tip-3"), conversation(compressionRow()).lineageSessionIds)
    }

    @Test
    fun `a plain row keeps one identity`() {
        val bot = conversation(row("""{"id": "plain-1", "title": "Standup"}"""))
        assertEquals("plain-1", bot.storedSessionId)
        assertEquals("plain-1", bot.resolvedSessionId)
        assertEquals("research:plain-1", bot.key())
        assertNull(bot.lineageSessionIds)
    }

    @Test
    fun `a row without any lineage key decodes without throwing`() {
        val response = json.decodeFromString<SessionListResponse>(
            """{"data": [{"id": "legacy-9", "title": "Legacy row"}]}""",
        )
        val listed: SessionItem? = response.data?.single()
        assertTrue("the list envelope still decodes its row", listed != null)
        val item = listed!!
        assertNull(item.lineageRootId)
        assertNull(item.lineageIds)
        assertNull(item.continuationKind)
        val bot = conversation(item)
        assertEquals("legacy-9", bot.storedSessionId)
        assertEquals("legacy-9", bot.resolvedSessionId)
    }

    @Test
    fun `a chain alone never moves the identity off the row id`() {
        val bot = conversation(
            row("""{"id": "tip-7", "_lineage_ids": ["root-6", "tip-7"], "continuation_kind": "compression"}"""),
        )
        assertEquals("tip-7", bot.storedSessionId)
        assertEquals("tip-7", bot.resolvedSessionId)
        assertEquals(listOf("root-6", "tip-7"), bot.lineageSessionIds)
    }

    @Test
    fun `a blank lineage root is treated as absent`() {
        val bot = conversation(row("""{"id": "tip-8", "_lineage_root_id": "", "title": "Draft"}"""))
        assertEquals("tip-8", bot.storedSessionId)
        assertEquals("tip-8", bot.resolvedSessionId)
        assertEquals("research:tip-8", bot.key())
    }

    @Test
    fun `an unstated timestamp stays null instead of epoch zero`() {
        assertNull(conversation(row("""{"id": "no-time", "title": "Quiet"}""")).activityTimestamp)
        // Any of the aliases the model reads counts as stated.
        assertEquals(
            1_700_000_000_000L,
            conversation(row("""{"id": "time-1", "last_activity_at": 1700000000}""")).activityTimestamp,
        )
    }

    @Test
    fun `a title falls back to the row preview, never to an invented label`() {
        assertEquals(
            "First user message",
            conversation(row("""{"id": "p-1", "title": "  ", "preview": "First user message"}""")).title,
        )
        assertEquals(
            "",
            conversation(row("""{"id": "p-2", "title": null}""")).title,
        )
    }
}
