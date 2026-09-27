package com.hermesandroid.relay.viewmodel.connection

import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.SESSION_LIST_WINDOW_LIMIT
import com.hermesandroid.relay.network.upstream.models.SessionItem
import java.util.Locale

/**
 * Bot Mode's route-scoped conversation directory (T1.3).
 *
 * Every other session read in the app resolves the *active* connection. A Bot Mode conversation
 * route is a different connection as often as not (ADR 67), so its directory is read from **that
 * route's own** profile through **that route's own** Dashboard client, and it is refused outright
 * for anything else. Both ways of getting this wrong are silent on screen — an unscoped read hands
 * back another profile's conversations, and a read that quietly returns nothing renders as "this
 * profile has none" — so a profile mismatch reads nothing and returns `null` with a log line, and
 * the caller states that it could not read this route instead of showing a wrong or empty list.
 */

/** Upstream `archived` filter values (`DashboardApiClient.listSessions`). */
internal const val BOT_CONVERSATION_ARCHIVED_EXCLUDE = "exclude"
internal const val BOT_CONVERSATION_ARCHIVED_ONLY = "only"
internal const val BOT_CONVERSATION_ARCHIVED_INCLUDE = "include"

/**
 * The drawer's bounded window. The directory is read in pages of at most 100 rows
 * (`SESSION_LIST_PAGE_LIMIT`) inside one read budget, and is never assembled beyond
 * [SESSION_LIST_WINDOW_LIMIT] rows, so a long-lived profile cannot make this surface unbounded.
 */
internal const val BOT_CONVERSATION_DIRECTORY_WINDOW_LIMIT = SESSION_LIST_WINDOW_LIMIT

private const val TAG = "BotConversationDir"

/**
 * The `archived` mode a read actually sends.
 *
 * `exclude` is the default Open view (owner D1: "close" = archive, and the open list excludes
 * archived rows); `only` and `include` are named by the Archived view. Anything else — blank, an
 * unknown spelling, a value a newer host invents — falls back to `exclude`: guessing narrow hides
 * rows until the view is named again, while guessing wide shows conversations the user closed.
 */
internal fun botConversationArchivedMode(requested: String?): String =
    when (requested?.trim()?.lowercase(Locale.ROOT)) {
        BOT_CONVERSATION_ARCHIVED_ONLY -> BOT_CONVERSATION_ARCHIVED_ONLY
        BOT_CONVERSATION_ARCHIVED_INCLUDE -> BOT_CONVERSATION_ARCHIVED_INCLUDE
        else -> BOT_CONVERSATION_ARCHIVED_EXCLUDE
    }

/**
 * The bounded row window one read may assemble: at least one row, never more than
 * [BOT_CONVERSATION_DIRECTORY_WINDOW_LIMIT]. The pages themselves are bounded by the client
 * (`sessionListPages`), so this clamp is the whole of the window arithmetic a caller controls.
 */
internal fun botConversationWindowLimit(limit: Int): Int =
    limit.coerceIn(1, BOT_CONVERSATION_DIRECTORY_WINDOW_LIMIT)

/** The name a request must carry to be served: trimmed, and nothing else normalized. */
private fun normalizedProfileName(profileName: String?): String = profileName?.trim().orEmpty()

/**
 * Reads one Bot route's own profile directory.
 *
 * @param route the Bot route the surface was opened on — identity is `(connectionId, profile)`
 *   (ADR 67); the reader never consults the active connection.
 * @param excludeSources the connection's hidden-source set (the drawer's source filter), so this
 *   surface lists what the drawer would list.
 * @param warn the log sink for a refusal; injected so the refusal is assertable without a device.
 */
internal class BotConversationDirectory(
    private val route: BotGatewayRoute,
    private val excludeSources: Collection<String> = emptyList(),
    private val warn: (String) -> Unit = { message -> android.util.Log.w(TAG, message) },
) {
    /** The route's own profile name, trimmed — the only profile this reader will serve. */
    val routeProfileName: String = normalizedProfileName(route.profileName)

    /**
     * Reads [route]'s own profile through [client] — the route's own Dashboard client, whose
     * lifetime belongs to the caller exactly as the Bot Chat route's `DisposableEffect` owns it.
     *
     * One read budget and the 100-row pages come from the client (`listSessions`), which is where
     * the bounded window is enforced end to end.
     *
     * Returns `null` when the request names any profile other than the route's own: nothing is read
     * and the refusal is logged. `null` never means "this profile has no conversations" — an empty
     * result means that.
     */
    suspend fun read(
        client: DashboardApiClient,
        profileName: String? = route.profileName,
        limit: Int = BOT_CONVERSATION_DIRECTORY_WINDOW_LIMIT,
        offset: Int = 0,
        archived: String = BOT_CONVERSATION_ARCHIVED_EXCLUDE,
    ): Result<List<SessionItem>>? {
        val requested = normalizedProfileName(profileName)
        if (requested != routeProfileName) {
            warn(
                "Refused a conversation directory read: asked for profile " +
                    "'${requested.ifEmpty { "<unscoped>" }}' on route '$routeProfileName' " +
                    "(connection ${route.connectionId}); a Bot route reads only its own profile",
            )
            return null
        }
        return client.listSessions(
            // The route's own name, never the caller's spelling: the request can only ever name
            // the profile this route owns.
            profile = routeProfileName,
            limit = botConversationWindowLimit(limit),
            offset = offset.coerceAtLeast(0),
            archived = botConversationArchivedMode(archived),
            excludeSources = excludeSources,
        )
    }
}
