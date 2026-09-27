package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.SessionActivityState
import java.util.Locale

/**
 * Bot Mode's status policy: the one pure derivation from the activity projection's composite
 * states to what a profile row, a conversation tab and a conversation chip may state.
 *
 * The vocabulary is exactly what the platform produces (ADR 48, owner D2). Nothing is invented,
 * no state is dressed up, and `null` means "nothing can be stated here" — the surface then shows
 * recency rather than a light.
 */

/**
 * The states a profile row may be lit by (B4).
 *
 * `BackgroundWork` cannot describe a profile: the projection is single-slot on the attached
 * conversation and `listProcesses()` answers only for a session this client holds. `Checking` and
 * `Unavailable` are transport facts, not activity. A row whose only states fall outside this
 * vocabulary shows recency only.
 */
private val PROFILE_LIGHT_STATES: Set<SessionActivityState> = setOf(
    SessionActivityState.NeedsInput,
    SessionActivityState.Starting,
    SessionActivityState.Working,
)

/**
 * The profile half of the projection's composite key: the wire name, trimmed and lowercased, with
 * the server default projected as the literal `default`.
 *
 * This is the normalization the activity projection applies when it publishes
 * `backgroundSessionActivityStates` (the server-default sentinel becomes `default`, and every
 * owner profile arrives trimmed and lowercased), so a lookup built here can never silently miss.
 */
internal fun botModeActivityProfileKey(profileName: String?): String =
    AgentDisplay.profileRequestName(profileName)?.lowercase(Locale.ROOT) ?: "default"

/**
 * The projection's composite key for one conversation: `profile:storedSessionId`.
 *
 * Null when the session id cannot name a conversation — an unattributable or absent owner. A bare
 * session id is never a key here: session ids are unique only inside their owning profile.
 */
internal fun botModeActivityKey(profileName: String?, storedSessionId: String?): String? {
    val sessionId = storedSessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "${botModeActivityProfileKey(profileName)}:$sessionId"
}

/**
 * The state a profile row may show for the conversations it owns, or `null` when nothing may be
 * stated (the row then shows its recency).
 *
 * [keys] are the conversations that row owns — the id half of the projection's key, i.e. their
 * stored session ids. An unattributable conversation arrives as `null` (or as a duplicate owner the
 * projection never published) and can never light the row. The winner is the highest-priority
 * lightable state in the drawer's own rank order: `NeedsInput` > `Starting` > `Working`.
 */
internal fun profileLight(
    states: Map<String, SessionActivityState>,
    profileKey: String?,
    keys: Iterable<String?>,
): SessionActivityState? = keys.asSequence()
    .mapNotNull { botModeActivityKey(profileKey, it) }
    .mapNotNull { states[it] }
    .filter { it in PROFILE_LIGHT_STATES }
    .minByOrNull { it.botModeStatusRank() }

/**
 * The state one conversation may state, from its composite [key] (`botModeActivityKey`, or the key
 * the conversation model publishes), or `null` when the projection says nothing about it.
 *
 * The full reachable vocabulary is passed through unchanged — `Checking` and `Unavailable`
 * included, so a state this surface does not produce today stays representable instead of being
 * frozen out. Routing the tab, the chip and the row through this one function is what keeps the
 * three render sites from disagreeing.
 *
 * [key] is the composite form only (`botModeActivityKey`): a key without a profile half is a bare
 * session id, and a bare session id never matches.
 */
internal fun conversationChip(
    states: Map<String, SessionActivityState>,
    key: String?,
): SessionActivityState? {
    // The profile half must be present: a key without one is a bare session id.
    val composite = key?.takeIf { it.indexOf(':') > 0 } ?: return null
    return states[composite]
}

/**
 * The drawer's status rank (the ordering the session drawer's own status ranking encodes): lower
 * wins, so `NeedsInput` outranks `Starting`, which outranks `Working`.
 */
private fun SessionActivityState.botModeStatusRank(): Int = when (this) {
    SessionActivityState.NeedsInput -> SessionDrawerStatus.NeedsInput.ordinal
    SessionActivityState.Starting -> SessionDrawerStatus.Starting.ordinal
    SessionActivityState.Working -> SessionDrawerStatus.Working.ordinal
    SessionActivityState.BackgroundWork -> SessionDrawerStatus.BackgroundWork.ordinal
    SessionActivityState.Checking -> SessionDrawerStatus.Checking.ordinal
    SessionActivityState.Unavailable -> SessionDrawerStatus.Unavailable.ordinal
}
