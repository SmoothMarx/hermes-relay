package com.hermesandroid.relay.data

import java.util.Locale

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
}
