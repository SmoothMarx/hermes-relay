package com.hermesandroid.relay.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.BotModeRoster
import com.hermesandroid.relay.data.BotModeState
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.BotSessionSummary
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.ui.components.LocalSphereSkin
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.ui.components.botModeActivityKey
import com.hermesandroid.relay.ui.screens.BotModeContent
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screen 1's status light, rendered: a lit row, a row with nothing attributable (recency only),
 * a row owned by another connection, which is never lit, a stale row, which keeps the existing
 * offline marker and is never lit either (T2.2), the honesty disclosure (T2.4) — shown when a
 * completed pass could not attribute every live row, and withheld when it could — and the state a
 * hiding policy leaves (T2.5): the other profiles' rows, their chips and the caveat all absent.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-432dpi")
class BotModeProfileListLightScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun litProfileRows() {
        capture(
            fileName = "bot-mode-profile-list-lit.png",
            activityStates = mapOf(
                botModeActivityKey("default", "default-bot-chat")!! to SessionActivityState.NeedsInput,
                botModeActivityKey("builder", "builder-bot-chat")!! to SessionActivityState.Working,
            ),
        )
    }

    @Test
    fun unlitProfileRows() {
        capture(fileName = "bot-mode-profile-list-unlit.png", activityStates = emptyMap())
    }

    /**
     * T2.2 — the active connection's row whose roster read did not come back: it keeps the offline
     * marker and its recency, and it stays unlit even though a state keyed to that row's own profile
     * and id is in the snapshot (which is what lights it in [litProfileRows]). The Builder row is the
     * control beside it.
     */
    @Test
    fun staleProfileRow() {
        capture(
            fileName = "bot-mode-profile-list-stale.png",
            activityStates = mapOf(
                botModeActivityKey("default", "default-bot-chat")!! to SessionActivityState.NeedsInput,
                botModeActivityKey("builder", "builder-bot-chat")!! to SessionActivityState.Working,
            ),
            staleProfiles = setOf("default"),
        )
    }

    /**
     * T2.4 — the shown half: a completed pass that could not attribute every live row. The Builder
     * row is lit from the snapshot while Lucy stays on recency, which is exactly the reading the
     * line qualifies.
     */
    @Test
    fun ambiguousDisclosure() {
        capture(
            fileName = "bot-mode-profile-list-disclosure.png",
            activityStates = mapOf(
                botModeActivityKey("builder", "builder-bot-chat")!! to SessionActivityState.Working,
            ),
            activityComplete = true,
            activityAmbiguous = true,
        )
    }

    /** The withheld half of the same pass-shaped state: a completed pass that attributed every row. */
    @Test
    fun settledProfileRowsWithoutDisclosure() {
        capture(
            fileName = "bot-mode-profile-list-settled.png",
            activityStates = mapOf(
                botModeActivityKey("builder", "builder-bot-chat")!! to SessionActivityState.Working,
            ),
            activityComplete = true,
            activityAmbiguous = false,
        )
    }

    /**
     * T2.5 — the absent state: the connection is pinned to `builder`, so the other profiles' rows
     * are gone rather than shown greyed out, the chips that would light them are gone with them,
     * and the caveat is gone too (the surface must not describe conversations it will not render).
     * The pinned profile is the control: still listed, still lit.
     */
    @Test
    fun supervisedAbsentProfileRows() {
        capture(
            fileName = "bot-mode-profile-list-supervised-absent.png",
            activityStates = mapOf(
                botModeActivityKey("default", "default-bot-chat")!! to SessionActivityState.NeedsInput,
                botModeActivityKey("builder", "builder-bot-chat")!! to SessionActivityState.Working,
            ),
            activityComplete = true,
            activityAmbiguous = true,
            lockedProfileName = "builder",
        )
    }

    private fun capture(
        fileName: String,
        activityStates: Map<String, SessionActivityState>,
        staleProfiles: Set<String> = emptySet(),
        activityComplete: Boolean = false,
        activityAmbiguous: Boolean = false,
        lockedProfileName: String? = null,
    ) {
        val output = File("build/ui-evidence/$fileName")
        output.parentFile?.mkdirs()
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalSphereSkin provides SphereRegistry.Adaptive) {
                    BotModeContent(
                        state = fixtureState(staleProfiles),
                        connections = listOf(
                            fixtureConnection("hermes", "Hermes"),
                            fixtureConnection("lab", "Lab server"),
                        ),
                        activeConnection = fixtureConnection("hermes", "Hermes"),
                        onBack = {},
                        onRefresh = {},
                        onSelectGateway = {},
                        onOpenBot = {},
                        onOpenGroup = {},
                        onNewBot = {},
                        nowMs = NOW,
                        activityStates = activityStates,
                        activityComplete = activityComplete,
                        activityAmbiguous = activityAmbiguous,
                        lockedProfileName = lockedProfileName,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(output.absolutePath)
    }

    private fun fixtureState(staleProfiles: Set<String> = emptySet()) = BotModeState(
        roster = BotModeRoster(
            bots = listOf(
                bot("hermes", "Hermes", "default", "Lucy", "Approve: run the migration script?", NOW - 120_000L, "default" in staleProfiles),
                bot("hermes", "Hermes", "builder", "Builder", "Build complete. 3 tests added.", NOW - 43 * 60_000L, "builder" in staleProfiles),
                bot("lab", "Lab server", "researcher", "Researcher", "Here are the latest findings.", NOW - 18 * 60_000L, "researcher" in staleProfiles),
            ),
            botModeProtocolSupported = true,
        ),
    )

    private fun bot(
        connectionId: String,
        connectionLabel: String,
        name: String,
        title: String,
        preview: String,
        activeAt: Long,
        stale: Boolean = false,
    ) = BotRosterEntry(
        profile = Profile(name = name, model = "gpt-5.6", description = title),
        displayName = title,
        route = com.hermesandroid.relay.data.BotGatewayRoute(
            key = com.hermesandroid.relay.data.BotGatewayRouteKey(connectionId, name),
            connectionLabel = connectionLabel,
        ),
        stale = stale,
        canonicalSession = BotSessionSummary(
            id = "$name-bot-chat",
            preview = preview,
            lastActiveAtMs = activeAt,
        ),
    )

    private fun fixtureConnection(id: String, label: String) = Connection(
        id = id,
        label = label,
        apiServerUrl = "",
        relayUrl = "",
        dashboardUrl = "https://example.invalid",
        tokenStoreKey = "test-$id",
    )

    private companion object {
        const val NOW = 1_777_000_000_000L
    }
}
