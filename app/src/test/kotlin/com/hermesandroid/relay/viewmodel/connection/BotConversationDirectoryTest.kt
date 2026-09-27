package com.hermesandroid.relay.viewmodel.connection

import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The route-scoped conversation directory: which profile a read may serve, which `archived` view it
 * asks for, and how much of the store one read assembles.
 *
 * The reader's failure modes are silent on screen — an unscoped read shows another profile's
 * conversations, an empty read looks like "this profile has none" — so each refusal is asserted both
 * as a `null` result *and* as a log line, with the Dashboard recording no request at all. The window
 * assertions read the requests the reader actually issued, because a truncated directory and a
 * paged one look identical from the returned rows alone.
 */
class BotConversationDirectoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DashboardApiClient
    private val recordedPaths = ConcurrentLinkedQueue<String>()

    /** How many rows the fake profile's store holds; the dispatcher serves pages out of it. */
    @Volatile
    private var storeRows: Int = 2

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    recordedPaths += path
                    if (!path.startsWith("/api/sessions?")) {
                        // Enrichment and any other host surface is optional metadata.
                        return MockResponse().setResponseCode(404)
                    }
                    val limit = param(path, "limit")?.toIntOrNull() ?: 0
                    val offset = param(path, "offset")?.toIntOrNull() ?: 0
                    val pageSize = minOf(limit, (storeRows - offset).coerceAtLeast(0))
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(pageBody(pageSize, offset))
                }
            }
            start()
        }
        client = DashboardApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            okHttpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun foreignProfileIsRefusedWithNullAndALogAndNoRead() = runBlocking {
        val lines = mutableListOf<String>()
        val directory = directory(excludeSources = listOf("cron", "webhook"), warn = { lines += it })

        val result = directory.read(client, profileName = "someone-else")

        assertNull("a foreign profile must never produce a directory", result)
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().contains("someone-else"))
        assertTrue(lines.single(), lines.single().contains(ROUTE_PROFILE))
        assertTrue("the refusal read the dashboard: ${recordedPaths.toList()}", recordedPaths.isEmpty())
    }

    @Test
    fun unscopedOrBlankProfileIsRefusedRatherThanRead() = runBlocking {
        val lines = mutableListOf<String>()
        val directory = directory(excludeSources = listOf("cron"), warn = { lines += it })

        // A blank name is the launch-profile read on the host, i.e. the unscoped list this surface
        // must never render — but it is not *this route's* profile, so it is refused like any other
        // foreign profile.
        assertNull(directory.read(client, profileName = null))
        assertNull(directory.read(client, profileName = "   "))

        assertEquals(2, lines.size)
        assertTrue(lines.joinToString("|"), lines.all { it.contains("<unscoped>") })
        assertTrue("an unscoped request reached the dashboard: ${recordedPaths.toList()}", recordedPaths.isEmpty())
    }

    @Test
    fun routeOwnProfileIsReadFromTheRoutesOwnClientWithTheOpenViewByDefault() = runBlocking {
        val lines = mutableListOf<String>()
        val directory = directory(excludeSources = listOf("cron", "webhook"), warn = { lines += it })

        val rows = checkNotNull(directory.read(client, profileName = ROUTE_PROFILE)).getOrThrow()

        assertEquals(listOf("session-0", "session-1"), rows.map { it.id })
        assertTrue("a served read logged a refusal: $lines", lines.isEmpty())
        val path = drainPaths().single()
        assertEquals(ROUTE_PROFILE, param(path, "profile"))
        // Owner D1: the open list is the default view, so archived rows are excluded unless the
        // caller names the archived view.
        assertEquals(BOT_CONVERSATION_ARCHIVED_EXCLUDE, param(path, "archived"))
        assertEquals("cron,webhook", decoded(param(path, "exclude_sources")))
        assertEquals("recent", param(path, "order"))
    }

    @Test
    fun archivedModeReachesTheWireAndUnknownModesFallClosedToTheOpenView() = runBlocking {
        val directory = directory()

        val include = checkNotNull(
            directory.read(client, profileName = ROUTE_PROFILE, archived = "include"),
        ).getOrThrow()
        assertEquals(2, include.size)
        assertEquals(BOT_CONVERSATION_ARCHIVED_INCLUDE, param(drainPaths().single(), "archived"))

        assertEquals(BOT_CONVERSATION_ARCHIVED_ONLY, param(archivePath("only"), "archived"))
        assertEquals(BOT_CONVERSATION_ARCHIVED_EXCLUDE, param(archivePath("everything"), "archived"))
        assertEquals(BOT_CONVERSATION_ARCHIVED_EXCLUDE, param(archivePath("  "), "archived"))

        // The normalized form itself, so an unknown or blank value can never widen the read.
        assertEquals(BOT_CONVERSATION_ARCHIVED_EXCLUDE, botConversationArchivedMode(null))
        assertEquals(BOT_CONVERSATION_ARCHIVED_EXCLUDE, botConversationArchivedMode("EXCLUDE"))
        assertEquals(BOT_CONVERSATION_ARCHIVED_INCLUDE, botConversationArchivedMode(" Include "))
        assertEquals(BOT_CONVERSATION_ARCHIVED_ONLY, botConversationArchivedMode("ONLY"))
    }

    @Test
    fun windowIsBoundedToTwoHundredRowsOverBoundedPages() = runBlocking {
        assertEquals(1, botConversationWindowLimit(0))
        assertEquals(1, botConversationWindowLimit(-5))
        assertEquals(37, botConversationWindowLimit(37))
        assertEquals(
            BOT_CONVERSATION_DIRECTORY_WINDOW_LIMIT,
            botConversationWindowLimit(BOT_CONVERSATION_DIRECTORY_WINDOW_LIMIT),
        )
        assertEquals(BOT_CONVERSATION_DIRECTORY_WINDOW_LIMIT, botConversationWindowLimit(5_000))

        storeRows = 500
        val directory = directory()

        val bounded = checkNotNull(
            directory.read(client, profileName = ROUTE_PROFILE, limit = 5_000),
        ).getOrThrow()
        assertEquals(BOT_CONVERSATION_DIRECTORY_WINDOW_LIMIT, bounded.size)
        assertEquals(bounded.size, bounded.map { it.id }.distinct().size)
        val windowRequests = drainPaths()
        assertEquals(2, windowRequests.size)
        assertEquals("100", param(windowRequests[0], "limit"))
        assertEquals("0", param(windowRequests[0], "offset"))
        assertEquals("100", param(windowRequests[1], "limit"))
        assertEquals("100", param(windowRequests[1], "offset"))

        // A window that is not a whole number of pages still ends in a bounded remainder page.
        val remainder = checkNotNull(
            directory.read(client, profileName = ROUTE_PROFILE, limit = 120),
        ).getOrThrow()
        assertEquals(120, remainder.size)
        val remainderRequests = drainPaths()
        assertEquals(2, remainderRequests.size)
        assertEquals("20", param(remainderRequests[1], "limit"))
        assertEquals("100", param(remainderRequests[1], "offset"))

        // A window below one page is one bounded page, not a zero-row read.
        val single = checkNotNull(
            directory.read(client, profileName = ROUTE_PROFILE, limit = 0),
        ).getOrThrow()
        assertEquals(1, single.size)
        assertEquals("1", param(drainPaths().single(), "limit"))
    }

    @Test
    fun offsetIsPassedThroughPerPageAndNeverNegative() = runBlocking {
        storeRows = 500
        val directory = directory()

        val paged = checkNotNull(
            directory.read(client, profileName = ROUTE_PROFILE, limit = 120, offset = 40),
        ).getOrThrow()
        assertEquals(120, paged.size)
        val requests = drainPaths()
        assertEquals("40", param(requests[0], "offset"))
        assertEquals("140", param(requests[1], "offset"))

        checkNotNull(directory.read(client, profileName = ROUTE_PROFILE, limit = 1, offset = -7)).getOrThrow()
        assertEquals("0", param(drainPaths().single(), "offset"))
    }

    @Test
    fun paddedSpellingOfTheRoutesOwnProfileIsServedUnderTheRoutesOwnName() = runBlocking {
        val lines = mutableListOf<String>()
        val directory = directory(warn = { lines += it })

        val rows = checkNotNull(
            directory.read(client, profileName = "  $ROUTE_PROFILE  "),
        ).getOrThrow()

        assertEquals(2, rows.size)
        assertTrue("padding must not refuse the route's own profile: $lines", lines.isEmpty())
        // The request carries the route's own name, never the caller's spelling.
        assertEquals(ROUTE_PROFILE, param(drainPaths().single(), "profile"))
    }

    /** Reads one `archived` spelling and returns the path the dashboard recorded for it. */
    private suspend fun archivePath(archived: String): String {
        val directory = directory()
        checkNotNull(directory.read(client, profileName = ROUTE_PROFILE, archived = archived)).getOrThrow()
        return drainPaths().single()
    }

    private fun directory(
        excludeSources: Collection<String> = emptyList(),
        warn: (String) -> Unit = {},
    ): BotConversationDirectory = BotConversationDirectory(
        route = BotGatewayRoute(
            key = BotGatewayRouteKey(connectionId = CONNECTION_ID, profileName = ROUTE_PROFILE),
            connectionLabel = "Route gateway",
        ),
        excludeSources = excludeSources,
        warn = warn,
    )

    /** Drains the paths recorded for the reads issued so far. */
    private fun drainPaths(): List<String> {
        val paths = mutableListOf<String>()
        while (true) {
            val path = recordedPaths.poll() ?: return paths
            paths += path
        }
    }

    /** A page of the fake store, `count` rows starting at [offset]. */
    private fun pageBody(count: Int, offset: Int): String = buildJsonObject {
        put("sessions", buildJsonArray {
            repeat(count) { index ->
                add(buildJsonObject {
                    put("id", "session-${offset + index}")
                    put("title", "Conversation ${offset + index}")
                    put("profile", ROUTE_PROFILE)
                    put("message_count", 3)
                })
            }
        })
    }.toString()

    /** One query parameter of a recorded request path, still percent-encoded. */
    private fun param(path: String, name: String): String? =
        path.substringAfter('?', "")
            .split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.firstOrNull() == name }
            ?.getOrNull(1)

    private fun decoded(value: String?): String? =
        value?.let { URLDecoder.decode(it, "UTF-8") }

    private companion object {
        const val CONNECTION_ID = "connection-route"
        const val ROUTE_PROFILE = "routebot"
    }
}
