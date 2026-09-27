package com.hermesandroid.relay.data

import com.hermesandroid.relay.network.upstream.models.SessionItem
import java.util.Locale

/** Upstream session timestamps are epoch seconds; this model's convention is epoch millis. */
private const val SECONDS_TO_MILLIS = 1000.0

/**
 * One conversation inside one Bot owner.
 *
 * The owner is the immutable `(connectionId, profileName)` pair: profile names and session
 * ids are only unique inside their owning connection, so [connectionId] travels beside the
 * composite [key] and the pair is the identity.
 *
 * The two session ids carry [BotChatTarget]'s contract instead of a second pair of meanings:
 * [storedSessionId] is the durable registry-row identity — the session half of the badge and
 * selection key — while [resolvedSessionId] is the compression-lineage tip that is *opened*,
 * defaulting to the stored id exactly as [BotChatTarget] defaults it.
 *
 * Presentation only: nothing here is persisted and no local registry is introduced; `archived`
 * remains the single upstream-owned close field.
 */
data class BotConversation(
    val connectionId: String,
    val profileName: String,
    val storedSessionId: String,
    val resolvedSessionId: String = storedSessionId,
    val title: String,
    /** Epoch millis, the convention of [BotRosterEntry.latestActivityAtMs]. */
    val activityTimestamp: Long? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Null when the source cannot state a count (a roster-sourced row) — never a fabricated `0`. */
    val messageCount: Int? = null,
    /**
     * The compression chain upstream reports for this row (`_lineage_ids`, root first), carried
     * exactly as received. Null when the row carries no chain — an uncompressed conversation, or a
     * row that did not come from the directory at all. Presentation only: a chain is never a
     * lookup key, and nothing here is inferred.
     */
    val lineageSessionIds: List<String>? = null,
) {
    /**
     * The app's composite conversation key: the form the activity projection publishes for
     * `backgroundSessionActivityStates`, with [profileKey] in the profile half. Titles, handles
     * and bare session ids are never identities.
     */
    fun key(): String = "${profileKey()}:$storedSessionId"

    /**
     * The normalized profile half of [key]: trimmed, lowercased, and `default` for the server
     * default profile — the same normalization the activity projection applies, so a lookup by
     * this key can never silently miss.
     */
    fun profileKey(): String =
        AgentDisplay.profileRequestName(profileName)?.lowercase(Locale.ROOT) ?: "default"

    /** The open pair, in the type the chat route already consumes. */
    fun chatTarget(): BotChatTarget = BotChatTarget(
        storedSessionId = storedSessionId,
        resolvedSessionId = resolvedSessionId,
    )

    companion object {
        /**
         * One conversation from one directory row (`GET /api/sessions`).
         *
         * The two ids follow the measured namespace of that surface: the row's `id` is the
         * compression-lineage **tip** and is what gets opened, while the durable registry id lives
         * on the row as `_lineage_root_id` and is the badge/selection half of [key]. An
         * uncompressed row carries no lineage, so one identity fills both fields — the behaviour
         * this surface had before the lineage fields were decoded.
         *
         * A blank `_lineage_root_id` is treated as absent: an empty [storedSessionId] would key
         * every badge lookup to nothing and silently darken the row, which is the failure this
         * mapping exists to prevent.
         *
         * [title] follows the app's own precedence for a directory row (server title, else the
         * first-message preview, else empty); [activityTimestamp] stays null when the row states no
         * timestamp rather than defaulting to epoch zero.
         */
        fun fromDirectoryRow(
            connectionId: String,
            profileName: String,
            row: SessionItem,
        ): BotConversation = BotConversation(
            connectionId = connectionId,
            profileName = profileName,
            storedSessionId = row.lineageRootId?.takeIf { it.isNotBlank() } ?: row.id,
            resolvedSessionId = row.id,
            title = row.title?.takeIf { it.isNotBlank() }
                ?: row.preview?.takeIf { it.isNotBlank() }.orEmpty(),
            activityTimestamp = row.resolvedLastActivity?.let { (it * SECONDS_TO_MILLIS).toLong() },
            pinned = row.pinned,
            archived = row.archived,
            messageCount = row.messageCount,
            lineageSessionIds = row.lineageIds,
        )

        /**
         * One conversation from one roster session summary — `canonical_session` of
         * `profiles.list(include_sessions: true)` (`data/BotModeData.kt:27-49`).
         *
         * This is the source that can see a row the REST directory cannot: every profile store's
         * canonical "Bot Chat" row is `hidden = 1` while the directory read filters `hidden = 0`
         * and exposes no blanket `include_hidden`, so a directory-only list never contains the
         * conversation the Bot Mode route opens. The summary is already held and costs no request.
         *
         * The two ids use the gateway's own namespace, the mirror image of a directory row's: the
         * durable registry row is `id` and the compression-lineage **tip** is `resolved_id`
         * (defaulting to `id` when the host states no tip), so [storedSessionId] — the badge and
         * selection key — is `id`, and [resolvedSessionId] — the id that is opened — is
         * `resolvedId`.
         *
         * Everything this row says comes from the summary's own statement about *this*
         * conversation; nothing is taken from the profile-level maxima, which mix in other
         * sessions ([BotRosterEntry.latestActivityAtMs] is `max(canonical, lastSession)`,
         * `data/BotModeData.kt:50-62`), and nothing is invented:
         *
         * - [activityTimestamp] is the canonical summary's own last activity and stays `null` when
         *   it states none — never epoch zero, the rule the directory mapping follows as well.
         * - [title] follows the directory mapping's precedence (stated title, else the
         *   first-message preview, else empty); a roster row is never renamed locally.
         * - [messageCount] stays `null`: this path cannot tell whether the summary's count spans
         *   the compression chain, and a number the surface cannot vouch for is exactly what the
         *   count must not become. The directory row supplies the count whenever it lists the
         *   conversation, and the union prefers it.
         * - `pinned`, `archived` and the lineage keep their defaults: a summary states no pin flag,
         *   no archive flag and no chain, so the row claims no emphasis, no archive state and no
         *   chain. It is listed in the Open view (owner D1's default read) because no source states
         *   otherwise, and the directory row — which does carry those fields — wins the union
         *   whenever it lists the same conversation.
         */
        fun fromRosterSummary(
            connectionId: String,
            profileName: String,
            summary: BotSessionSummary,
        ): BotConversation = BotConversation(
            connectionId = connectionId,
            profileName = profileName,
            storedSessionId = summary.id,
            resolvedSessionId = summary.resolvedId,
            title = summary.title.takeIf(String::isNotBlank)
                ?: summary.preview.takeIf(String::isNotBlank).orEmpty(),
            activityTimestamp = summary.lastActiveAtMs.takeIf { it > 0L },
        )
    }
}

/**
 * The conversation set one Bot route lists: the route's own roster canonical summary **∪** the
 * directory rows it read, de-duplicated by id (T1.7).
 *
 * Why the union exists (measured, read-only): the profile's canonical "Bot Chat" row is
 * `hidden = 1`, the session-list handler behind `DashboardApiClient.listSessions` filters
 * `hidden = 0` and exposes no blanket `include_hidden`, so a directory-only list shows the
 * profile's other sessions and **not** the conversation the surface was opened from. The roster
 * summary is already held (`profiles.list(include_sessions: true)`), so including it adds no
 * request and no server-side change.
 *
 * **Ownership.** Every entry is tagged with [BotGatewayRoute.connectionId] /
 * [BotGatewayRoute.profileName], and a roster entry is admitted only when its own route names that
 * connection and that profile — the fleet roster merges connections that carry the same profile
 * name (`BotModeController.aggregateForTest`), so matching on the profile name alone would leak
 * another connection's conversation (ADR 67: identity is the `(connectionId, profile)` pair). An
 * entry with no route has no owner to attribute and is skipped rather than guessed; a profile name
 * is compared as the route states it (trimmed) and never case-folded, because two profiles on one
 * host may differ only in case. A row that names no id at all is skipped too: it can be neither
 * opened nor keyed.
 *
 * **De-duplication is by id**, and "an id" is every id upstream uses for that one conversation —
 * the durable registry id, the compression-lineage tip, and the chain a directory row carries —
 * not one spelling of it. The two sources number the same conversation differently (the roster
 * calls the registry row `id` and the tip `resolved_id`; a directory row calls the tip `id` and the
 * registry row `_lineage_root_id`), and a compression landing between the two reads moves the tip,
 * so matching on a single id would list the entry conversation twice or drop the row the route
 * opened. The **directory row wins** the merge: it is the server's own row, it carries the count,
 * the archive flag and the chain, and its `id` is what opens the conversation.
 *
 * **Ordering** is the drawer's default row ordering (`SessionDrawerOrdering.Updated` through
 * `filterAndSortSessionRows`, `ui/components/SessionDrawerPolicy.kt:139-151`): pinned first, then
 * most recent activity, then title. An unstated timestamp sorts as the oldest; it is never
 * promoted to "now".
 */
internal fun botConversationUnion(
    route: BotGatewayRoute,
    roster: BotModeRoster?,
    directoryRows: List<SessionItem>,
): List<BotConversation> {
    val listed = directoryRows
        .map { row -> BotConversation.fromDirectoryRow(route.connectionId, route.profileName, row) }
        .filter { it.storedSessionId.isNotBlank() }
    val canonical = roster?.bots
        ?.firstOrNull { entry ->
            val entryRoute = entry.route
            entryRoute != null &&
                entryRoute.connectionId == route.connectionId &&
                entryRoute.profileName.trim() == route.profileName.trim()
        }
        ?.canonicalSession
        ?.takeIf { it.id.isNotBlank() }
        ?.let { summary ->
            BotConversation.fromRosterSummary(route.connectionId, route.profileName, summary)
        }
    val merged = listed.toMutableList()
    if (canonical != null && merged.none { it.sharesIdentityWith(canonical) }) {
        merged += canonical
    }
    return merged.sortedWith(botConversationOrder)
}

/**
 * Whether two entries name the same conversation: the same owner — the `(connectionId, profile)`
 * pair, not a profile name alone — and at least one shared id among [identityIds]. Never true for
 * two different owners, and never based on a title or a preview, which two conversations may share.
 */
internal fun BotConversation.sharesIdentityWith(other: BotConversation): Boolean =
    connectionId == other.connectionId &&
        profileKey() == other.profileKey() &&
        identityIds().any(other.identityIds()::contains)

/** Every id upstream uses for this conversation: the registry id, the open tip, and the chain. */
private fun BotConversation.identityIds(): Set<String> =
    (lineageSessionIds.orEmpty() + listOf(storedSessionId, resolvedSessionId))
        .filter(String::isNotBlank)
        .toSet()

/** The drawer's default row ordering, over conversations. */
private val botConversationOrder: Comparator<BotConversation> =
    compareByDescending<BotConversation> { it.pinned }
        .thenByDescending { it.activityTimestamp ?: 0L }
        .thenBy { it.title.lowercase(Locale.ROOT) }
