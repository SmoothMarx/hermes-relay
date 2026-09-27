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
    }
}
