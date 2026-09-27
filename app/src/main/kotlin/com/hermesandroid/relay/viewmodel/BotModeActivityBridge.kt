package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.SessionActivityOwner
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.ui.components.botModeActivityKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One connection's live-activity snapshot, as Bot Mode's profile list reads it (T1.4 / B3).
 *
 * [states] is keyed with the composite form the app's own activity projection publishes and
 * `BotModeStatusPolicy` looks up (`profile:storedSessionId`, the server-default profile projected
 * as `default`), so a row matches a key without a second normalization.
 *
 * [ambiguous] is true when the pass that produced [states] could not attribute every live row the
 * host reported. An unattributable row carries **no** key at all, so it can only ever be described
 * by the honest disclosure — never by an invented state (ADR 48, owner D2).
 *
 * [complete] is true when that pass ran to completion, i.e. when the *absence* of a state is
 * authoritative for this connection. A transient failure, or a host without the active-list RPC,
 * leaves it false: nothing may be stated and the ambiguity disclosure is withheld, because the
 * picture it would qualify is not settled (T2.4).
 */
internal data class BotModeActivitySnapshot(
    val connectionId: String,
    val states: Map<String, SessionActivityState>,
    val ambiguous: Boolean,
    val complete: Boolean,
)

private const val TAG = "BotModeActivityBridge"

/**
 * Builds the snapshot Bot Mode's profile list reads from one pass over the activity registry, or
 * `null` when no connection is active.
 *
 * The snapshot is **connection-scoped** (owner D3): rows of any other connection are dropped here,
 * so a Bot Mode row can never be lit by another connection's chat, and an unscoped read — the shape
 * that would let it happen — is not expressible. `null` is the honest answer while no connection is
 * active; it is never an empty snapshot, which would claim "nothing is running on this connection".
 */
internal fun botModeActivitySnapshot(
    connectionId: String?,
    states: Map<SessionActivityOwner, SessionActivityState>,
    ambiguous: Boolean,
    complete: Boolean,
): BotModeActivitySnapshot? {
    val connection = connectionId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val rows = states
        .filterKeys { it.connectionId == connection }
        .mapNotNull { (owner, state) ->
            botModeActivityKey(owner.profile, owner.storedSessionId)?.let { key -> key to state }
        }
        .toMap()
    return BotModeActivitySnapshot(
        connectionId = connection,
        states = rows,
        ambiguous = ambiguous,
        complete = complete,
    )
}

/**
 * The single publisher of Bot Mode's activity snapshot (T1.4 / B3).
 *
 * The activity registry lives on `ChatViewModel`, which exists once per chat surface: the main
 * chat, and one per open Bot conversation route. A Bot Mode profile row states the status of the
 * *active connection*, so exactly one publisher is allowed — the main ChatViewModel, installed by
 * the runtime binder. The first claim wins; a later claim, and any publication from a source other
 * than the claimed one, is refused with a log line.
 *
 * The failure this prevents is silent: a Bot route's own ViewModel publishing its scope over the
 * main one would light profile rows with another chat's activity, and nothing on screen would say
 * so.
 */
internal class BotModeActivityBridge(
    private val warn: (String) -> Unit = { message -> android.util.Log.w(TAG, message) },
) {
    private val _snapshot = MutableStateFlow<BotModeActivitySnapshot?>(null)

    /**
     * The latest snapshot from the claimed publisher, or `null` while no connection is active or
     * before that publisher has stated one.
     */
    val snapshot: StateFlow<BotModeActivitySnapshot?> = _snapshot.asStateFlow()

    private var publisher: Any? = null

    /**
     * Claims this bridge for [source]. Idempotent for the same source; `false` for any other, in
     * which case the caller must install nothing — a refused holder has no bridge to publish
     * through.
     */
    fun claim(source: Any): Boolean {
        val current = publisher
        if (current == null) {
            publisher = source
            return true
        }
        if (current === source) return true
        warn(
            "Refused a second Bot Mode activity publisher: this bridge is claimed by " +
                "${current.javaClass.simpleName}; the surface's status reads one connection only",
        )
        return false
    }

    /**
     * Publishes [next] for [source]. A publication from any source other than the claimed publisher
     * is dropped and logged: the snapshot on screen stays the one the owning connection stated.
     * The first publication also claims the bridge, so a publisher installed without an explicit
     * [claim] still cannot be replaced.
     */
    fun publish(source: Any, next: BotModeActivitySnapshot?) {
        val current = publisher
        if (current == null) {
            publisher = source
        } else if (current !== source) {
            warn(
                "Refused a Bot Mode activity snapshot from a second publisher: this bridge is " +
                    "claimed by ${current::class.java.simpleName}",
            )
            return
        }
        _snapshot.value = next
    }
}
