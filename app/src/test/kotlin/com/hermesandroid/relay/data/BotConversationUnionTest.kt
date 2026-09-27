package com.hermesandroid.relay.data

import com.hermesandroid.relay.network.upstream.models.SessionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The union contract (T1.7): the conversation set the sub-menu and the strip list is the route's
 * own roster canonical summary **∪** the directory rows it read, de-duplicated by id.
 *
 * The reason the union exists is invisible from the repo (it is a live-host measurement): the
 * profile's canonical "Bot Chat" row is `hidden = 1` while the directory read filters `hidden = 0`,
 * so a directory-only list omits the very conversation the route was opened from. The contract this
 * class pins is the part that *is* derivable: the two sources number one conversation differently
 * (the roster: registry row `id` + tip `resolved_id`; a directory row: tip `id` + registry row
 * `_lineage_root_id`), so the entry must appear **exactly once** however the two reads spell it,
 * the directory row must win, a roster-only row must claim nothing the roster does not state, and
 * nothing outside the route's own `(connectionId, profile)` owner may enter the list.
 */
class BotConversationUnionTest {

    private val route = BotGatewayRoute(
        key = BotGatewayRouteKey(connectionId = "conn-a", profileName = "research"),
        connectionLabel = "Gateway A",
    )

    private fun rosterEntry(
        connectionId: String = "conn-a",
        profileName: String = "research",
        canonical: BotSessionSummary? = summary(),
    ): BotRosterEntry = BotRosterEntry(
        profile = Profile(name = profileName, model = "test-model"),
        displayName = profileName,
        route = BotGatewayRoute(
            key = BotGatewayRouteKey(connectionId = connectionId, profileName = profileName),
            connectionLabel = "Gateway A",
        ),
        canonicalSession = canonical,
    )

    private fun summary(
        id: String = "root-1",
        resolvedId: String = "tip-3",
        title: String = "Bot Chat",
        preview: String = "",
        lastActiveAtMs: Long = 1_700_000_000_000L,
        messageCount: Int = 0,
    ) = BotSessionSummary(
        id = id,
        resolvedId = resolvedId,
        title = title,
        preview = preview,
        lastActiveAtMs = lastActiveAtMs,
        messageCount = messageCount,
    )

    private fun directoryRow(
        id: String = "tip-3",
        lineageRootId: String? = "root-1",
        lineageIds: List<String>? = listOf("root-1", "mid-2", "tip-3"),
        title: String = "Weekly digest",
        messageCount: Int? = 41,
        lastActiveSeconds: Double = 1_700_000_950.0,
        archived: Boolean = false,
    ) = SessionItem(
        id = id,
        title = title,
        messageCount = messageCount,
        archived = archived,
        lastActive = lastActiveSeconds,
        lineageRootId = lineageRootId,
        lineageIds = lineageIds,
    )

    private fun union(
        roster: BotModeRoster?,
        rows: List<SessionItem>,
    ): List<BotConversation> = botConversationUnion(route, roster, rows)

    @Test
    fun `the canonical row is listed when the directory cannot see it`() {
        val listed = union(BotModeRoster(bots = listOf(rosterEntry())), emptyList())
        assertEquals(1, listed.size)
        val row = listed.single()
        // The gateway's namespace: the registry row is `id`, the tip is `resolved_id`.
        assertEquals("conn-a", row.connectionId)
        assertEquals("research", row.profileName)
        assertEquals("root-1", row.storedSessionId)
        assertEquals("tip-3", row.resolvedSessionId)
        assertEquals("research:root-1", row.key())
        assertEquals(BotChatTarget("root-1", "tip-3"), row.chatTarget())
        assertEquals("Bot Chat", row.title)
    }

    @Test
    fun `the canonical row and its directory row collapse to one item`() {
        val listed = union(
            BotModeRoster(bots = listOf(rosterEntry())),
            listOf(directoryRow()),
        )
        assertEquals(1, listed.size)
        // The directory row wins the merge: it is the server's own row and the one that opens.
        val row = listed.single()
        assertEquals("Weekly digest", row.title)
        assertEquals(41, row.messageCount)
        assertEquals(listOf("root-1", "mid-2", "tip-3"), row.lineageSessionIds)
        assertEquals("root-1", row.storedSessionId)
        assertEquals("tip-3", row.resolvedSessionId)
    }

    @Test
    fun `dedup holds when the directory row carries no lineage at all`() {
        val listed = union(
            BotModeRoster(bots = listOf(rosterEntry(canonical = summary(id = "plain-1", resolvedId = "plain-1")))),
            listOf(directoryRow(id = "plain-1", lineageRootId = null, lineageIds = null)),
        )
        assertEquals(1, listed.size)
        assertEquals("plain-1", listed.single().storedSessionId)
    }

    @Test
    fun `a tip that moved between the two reads still collapses to one item`() {
        // The roster read happened first and named an older tip; the conversation was compressed
        // again before the directory read, so only the chain still identifies it.
        val listed = union(
            BotModeRoster(bots = listOf(rosterEntry(canonical = summary(id = "root-1", resolvedId = "mid-2")))),
            listOf(directoryRow(id = "tip-3", lineageIds = listOf("root-1", "mid-2", "tip-3"))),
        )
        assertEquals(1, listed.size)
        assertEquals("tip-3", listed.single().resolvedSessionId)
    }

    @Test
    fun `a roster-only row fabricates no message count`() {
        // `BotSessionSummary.messageCount` defaults to 0 when the host states nothing, so a `0`
        // cannot be told from an absent count and this row renders none.
        val listed = union(
            BotModeRoster(bots = listOf(rosterEntry(canonical = summary(messageCount = 0)))),
            emptyList(),
        )
        assertNull(listed.single().messageCount)
    }

    @Test
    fun `a roster-only row states only what the roster states`() {
        val listed = union(
            BotModeRoster(
                bots = listOf(
                    rosterEntry(
                        canonical = summary(
                            title = "  ",
                            preview = "First user message",
                            lastActiveAtMs = 0L,
                        ),
                    ),
                ),
            ),
            emptyList(),
        )
        val row = listed.single()
        assertEquals("First user message", row.title)
        assertNull("an unstated timestamp is not epoch zero", row.activityTimestamp)
        assertNull(row.lineageSessionIds)
        assertEquals(false, row.pinned)
        assertEquals(false, row.archived)
    }

    @Test
    fun `the union never introduces another connection's row`() {
        // The fleet roster merges connections that carry the same profile name, so only the entry
        // whose own route names this connection may be used (ADR 67).
        val listed = union(
            BotModeRoster(
                bots = listOf(
                    rosterEntry(connectionId = "conn-b", canonical = summary(id = "other-root")),
                    rosterEntry(connectionId = "conn-a", canonical = summary()),
                ),
            ),
            emptyList(),
        )
        assertEquals(1, listed.size)
        assertEquals("conn-a", listed.single().connectionId)
        assertEquals("root-1", listed.single().storedSessionId)
    }

    @Test
    fun `another profile in the same roster is never introduced`() {
        val listed = union(
            BotModeRoster(
                bots = listOf(
                    rosterEntry(profileName = "vintage", canonical = summary(id = "vintage-root")),
                ),
            ),
            emptyList(),
        )
        assertTrue("another profile's canonical row is not this route's conversation", listed.isEmpty())
    }

    @Test
    fun `a roster entry with no owner is skipped, not guessed`() {
        val orphan = BotRosterEntry(
            profile = Profile(name = "research", model = "test-model"),
            displayName = "research",
            canonicalSession = summary(),
        )
        assertTrue(union(BotModeRoster(bots = listOf(orphan)), emptyList()).isEmpty())
    }

    @Test
    fun `a profile that states no canonical summary adds nothing`() {
        val listed = union(
            BotModeRoster(bots = listOf(rosterEntry(canonical = null))),
            listOf(directoryRow()),
        )
        assertEquals(1, listed.size)
        assertEquals("tip-3", listed.single().resolvedSessionId)
    }

    @Test
    fun `a null roster leaves the directory read alone`() {
        val listed = union(null, listOf(directoryRow()))
        assertEquals(1, listed.size)
        assertEquals("Weekly digest", listed.single().title)
    }

    @Test
    fun `a row that names no id is not listed`() {
        val listed = union(
            BotModeRoster(bots = listOf(rosterEntry(canonical = summary(id = "", resolvedId = "")))),
            listOf(directoryRow(id = "", lineageRootId = "", lineageIds = null)),
        )
        assertTrue("a row without an id can be neither opened nor keyed", listed.isEmpty())
    }

    @Test
    fun `two conversations sharing a title are never merged`() {
        val listed = union(
            BotModeRoster(bots = listOf(rosterEntry(canonical = summary(id = "root-1", resolvedId = "root-1")))),
            listOf(
                directoryRow(id = "tip-3", lineageRootId = null, lineageIds = null, title = "Bot Chat"),
            ),
        )
        assertEquals(2, listed.size)
    }

    @Test
    fun `ordering is the drawer's default row ordering`() {
        val pinned = directoryRow(id = "pinned-1", lineageRootId = null, lineageIds = null, title = "Pinned")
            .copy(pinned = true)
        val recent = directoryRow(
            id = "recent-1",
            lineageRootId = null,
            lineageIds = null,
            title = "Recent",
            lastActiveSeconds = 1_700_000_900.0,
        )
        val older = directoryRow(
            id = "older-1",
            lineageRootId = null,
            lineageIds = null,
            title = "Older",
            lastActiveSeconds = 1_700_000_100.0,
        )
        val unstated = directoryRow(
            id = "quiet-1",
            lineageRootId = null,
            lineageIds = null,
            title = "Quiet",
        ).copy(lastActive = null)
        val listed = union(
            BotModeRoster(bots = listOf(rosterEntry(canonical = null))),
            listOf(older, unstated, recent, pinned),
        )
        assertEquals(
            listOf("pinned-1", "recent-1", "older-1", "quiet-1"),
            listed.map(BotConversation::storedSessionId),
        )
    }

    @Test
    fun `a roster row carries its own conversation's recency, not the profile's`() {
        // `BotRosterEntry.latestActivityAtMs` is max(canonical, lastSession), so a row built from it
        // would inherit a sibling conversation's recency: the summary's own last activity is used.
        val entry = rosterEntry(canonical = summary(lastActiveAtMs = 1_700_000_000_000L))
            .copy(lastSession = BotSessionSummary(id = "sibling-1", lastActiveAtMs = 1_800_000_000_000L))
        val listed = union(BotModeRoster(bots = listOf(entry)), emptyList())
        assertEquals(1_700_000_000_000L, listed.single().activityTimestamp)
        assertEquals(1_800_000_000_000L, entry.latestActivityAtMs)
    }
}
