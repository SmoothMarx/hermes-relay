# PLAN — Bot Mode: profile list → conversation sub-menu → top conversation tabs (phased implementation plan)

Campaign `wf_botmode_a1c4`, planning pass **P1**. Repo `/home/smoothmarx/Projects/Hermes Relay` @ `dev`
(`6258c751`, tree clean at recon). **Read-only in the repo** for this document; the plan is written here only.

**Shape decision (binding):** owner approved **option A — tabs on top**, per `out/mockup_messenger_v2.html`
(screen 1 = profile list with status indicators; screen 2 = that profile's conversations; screen 3 = one
conversation with a horizontal tab strip on top). The mockup's own recommendation text is at
`out/mockup_messenger_v2.html:141-152`; the side rail (option B) is **not** in this plan.

**Anchor convention.** Kotlin anchors are package-relative to
`app/src/main/kotlin/com/hermesandroid/relay/`; `docs/`, `scripts/`, `app/src/…res` are repo-root relative.
Anchors here come from `out/RECON_FACTS.md`, `out/design_D1_ux_architecture.md`,
`out/design_D1b_messenger_status.md`, `out/judge_D2_hostile_review.md`,
`out/judge_D2b_combined_review.md`, plus anchors **this pass re-opened directly** (listed in §0).
Anything neither artifact nor this pass verified is marked `UNVERIFIED`.

---

## 0. READ SET AND WHAT THIS PASS VERIFIED ITSELF

Re-opened directly for this plan (so the builder can trust these without re-deriving):

| Anchor | What it proves |
|---|---|
| `ui/screens/BotModeScreen.kt:88-93` | roster item key is `"bot:route:<len>:<connectionId>:<profileName>"` (owner pair already the Compose identity) |
| `ui/screens/BotModeScreen.kt:140-166` | row tap today runs `ensureCanonicalBotChat(route).map { it.resolvedSessionId }` → `onOpenBotChat` — the **lineage tip** is what the route carries |
| `ui/screens/BotModeScreen.kt:210-227` | `BotModeContent` is `internal`, driven by tests; existing params have defaults |
| `ui/screens/BotModeScreen.kt:455-495,539-604` | list/empty/loading branches; `BotConversationRow` at `:540`; recency `:566-570`; `"$connectionLabel · @${bot.handle}"` `:572-580`; existing `bot_mode_offline` stale marker `:581-588`; preview `:591-602` |
| `ui/screens/BotChatScreen.kt:82-93,140-200` | screen signature; `ChatMediaViewerHost(route.key, sessionId)` `:93`; `remember(route.key, sessionId)` ×3 `:145-149`; providers installed `:162-181`; `setProfileSelectionHandler { selected?.name == route.profileName }` `:173-175`; `openProfileSession(sessionId = sessionId)` `:183-188`; **no** `setProfileSessionLister`/`refreshSessions` anywhere in the file |
| `ui/screens/BotChatScreen.kt:200-260,278-292,339-411` | TopAppBar block and message body block — the insertion points for the tab strip; `ChatMessageQueue` at `:278` |
| `ui/RelayApp.kt:497-520` | `Screen.BotGroup` / `Screen.BotChat` sealed entries with `ARG_*` + `route(x)` (the shape to copy) |
| `ui/RelayApp.kt:2620-2705` | `BotGroup` composable registration; `BotChat` registration, route-scoped lease + `botDashboardClient` `:2662-2670`, `DisposableEffect` disposes both `:2671-2676`, blank `sessionId` ⇒ "open failed" `:2677-2689`, VM key `"bot-chat:${connectionId}:${profileName}:$sessionId"` `:2691-2693` |
| `viewmodel/connection/ProfileController.kt:808-829` | `listProfileScopedSessions` resolves `activeConnectionId.value` `:819` + `activeDashboardUrlProvider()` `:820` and hard-codes `archived = "include"` `:826` |
| `viewmodel/connection/BotModeController.kt:44-54,118-158,270-292` | lease factory signature (incl. `retain`); roster refresh on a non-retained lease fixed to profile `"default"` `:126-128`; `BotModeGatewaySnapshot(stale,error)` `:131-155`; install_id collapse key `:275-279` |
| `viewmodel/connection/UpstreamTransportController.kt:505-546,556-569` | route clients keyed `(connectionId, profile)`; `fixedSessionProfile = profile` `:540`; teardown only when `(retired || replaced) && activeRequests == 0 && retained == 0` `:564-568` (so judge N6 is right: an ordinary release leaves the client alive) |
| `viewmodel/ChatViewModel.kt:194,345-372,964-966,2381,4043-4058,4450-4457,5508-5518,5597,6112-6160,7922-7948` | `SESSION_DIRECTORY_PAGE_SIZE = 50`; `resolveGatewayActiveSessions` (shipped 4-branch attribution resolver); `_pendingAttachments` unkeyed; `updateCurrentProfileActivityDirectory`; lister setters; `sessions`/`currentSessionId`; `automaticGatewayWorkDeferred()` barrier; lister consumer; `switchSession`; gateway detach + checkpoint + NeedsInput key |
| `ui/components/SessionDrawerPolicy.kt:62-63,74-109,126-152` | `sessionRowKey` = `profileKey.lowercase():sessionId`; `sessionDrawerStatus`; `scopedSessionActivityStates(..., allowBareSessionIds)`; status rank `NeedsInput 0 … Idle 6` |
| `data/BotModeData.kt:38-62,105-122` | `BotRosterEntry` carries exactly `lastSession`/`workerSession`/`canonicalSession`; `latestActivityAtMs`/`latestPreview` derivations; `BotChatTarget(storedSessionId, resolvedSessionId)` `:110-115`; `BotModeState` has no conversation list |
| `data/SessionActivityRegistry.kt:25-40,84-105` | `SessionActivityScope = (connectionId, normalized profile)`; phase priority; `presentationState()` → `null` for Revalidating/Unavailable/Idle |
| `network/upstream/DashboardApiClient.kt:1116-1155` | profile-scoped `GET /api/sessions?order=recent&min_messages=1&profile=`, ≤100/page, `archived=exclude|only|include`; doc says the returned ids are the same stored-session ids `session.resume` reads |
| `docs/upstream-surface-matrix.md:50,54,291-300` | `archived` semantics + PATCH; `session.active_list` contract incl. the admissible attribution set and fail-closed rule; `is_active` prohibition; `process.list` ⇒ Background work; upstream's own advice |
| `docs/decisions.md:268-278,2795-2832,2878-2932,3958-4014,4136-4161` | ADR 12 (no separate lane/segment; bottom nav fixed at 4); ADR 46 (`archived` upstream-owned, drawer is a stateless renderer, optimistic-update-then-server-truth discipline); ADR 48 (hamburger exclusively the drawer; live turn detaches; SSE switching disabled); ADR 67 (one drawer entry + full-screen messenger list; `(connectionId, profile)` pair for stable item identity; never switch the global connection); ADR 69 (passive observation sends no `session.resume/activate/prompt.submit/interrupt`) |
| `docs/dev-loop.md:55-68` | UI/behaviour is a **human gate** (`:62`); `area:android (logic)` is CI-gateable |
| `docs/localization.md:19-60` | 7 `values*` dirs in `app/src/main/res`; flavor catalogs under `app/src/sideload/res/…`; `source_sha256` per source set; ≥200 % font + TalkBack review |
| `docs/localization-status.json:locales.<lc>.source_sha256.{main,sideload}` | per-source-set canonical hashes the gate compares against |
| `scripts/android-prepush.py:23-55,60-69,114-125` | `FOCUSED_TESTS` = 31 entries, **none** matching `BotMode*`/`BotChat*`/`SessionDrawer*`; `REPOSITORY_CHECKS` list; test task `:app:testSideloadDebugUnitTest` |
| `docs/audits/design-qa.md:58-77` | the existing "Android Bot Mode messenger workspace" evidence section format to extend |
| `app/src/test/kotlin/.../screenshots/BotModeScreenshotTest.kt:27-59` | Robolectric screenshot convention `@Config(qualifiers = "w390dp-h844dp-432dpi")`, `captureRoboImage`, writes `build/ui-evidence/…` |

**Two things this pass found that the designs did not say, and the plan acts on:**

1. `app/src/sideload/res/values/strings.xml` is a **tiny flavor overlay** (4 `<string>` entries, 1107 bytes) with
   its own 6 locale catalogs. Adding main-only strings therefore does **not** require flavor-catalog entries —
   but the gate must be run to prove it (§6), and the ≥200 % font/TalkBack review covers both flavors (§5).
2. Owner decision **D3 kills design D1b §3 "Option A" (one `session.active_list` per saved gateway per
   refresh)**. The plan therefore scopes live status to the **active connection only** and keeps the existing
   stale/offline treatment for every other saved gateway; judge finding B5's alternative branch ("re-scope to
   the active connection only and keep Option C's stale treatment for the rest — cheap, honest") is the branch
   taken.

---

## 1. SCOPE AND NON-GOALS

### 1.1 What ships (v1 of this feature)

| # | Deliverable | Where | Owner's words it answers |
|---|---|---|---|
| S-1 | Bot Mode's primary list stays one row per **profile owner** `(connectionId, profile)`, now with a status indicator, recency line and the existing stale marker | `ui/screens/BotModeScreen.kt` | "the list of profile" |
| S-2 | Tapping a profile row body opens a **conversation sub-menu** (new route, back-navigable, inside Bot Mode) listing that profile's **open (= non-archived)** conversations | new `ui/screens/BotConversationsScreen.kt` + `Screen.BotConversations` | "upon clicking it, it opens a sub-menu displaying all active conversations with that profile" |
| S-3 | Inside a conversation, a **horizontal tab strip on top** lists that profile's conversations and swaps between them **in place**; the open tab is highlighted and carries a state dot with a text/`contentDescription` label | `ui/screens/BotChatScreen.kt` | "a side scrollable list of tabs … allows the user to switch between them" (placement per owner D4: top) |
| S-4 | A status indicator that **reacts**: per-conversation chip; per-profile light only where attribution is real; **no "done" state anywhere** | policy functions + 3 render sites | "Pending, done, etc." — the honest subset |
| S-5 | Archive **is** close: `archived=exclude` is the open list, `include archived` is an explicit view, and the row action archives against the route's own profile/connection | sub-menu | "conversations I have not deemed closed or archived" |
| S-6 | The pre-existing unkeyed-attachment defect is fixed **before** switching becomes one tap | `viewmodel/ChatViewModel.kt` | (safety: nothing is delivered to the wrong conversation) |

### 1.2 What this plan does NOT do (say so in the PR)

| Non-goal | Why (anchor) |
|---|---|
| **Fleet-wide status lights** — no `session.active_list` per saved gateway per refresh | Owner **D3**; the lease is `default`-fixed (`BotModeController.kt:126-128`) and the scope unit is `(connection, profile)` (`SessionActivityRegistry.kt:25-40`), so "one extra RPC" could not light other profiles anyway (judge B5) |
| **Side rail / vertical tab list** | Owner **D4**; no `NavigationRail`/`Draggable`/`WindowSizeClass` in the app (RECON §6) |
| **A second navigation lane, a 4th bottom-nav segment, a new drawer entry** | ADR 12 (`docs/decisions.md:272`; `docs/spec.md:589-601`); ADR 48 keeps the hamburger exclusively the drawer (`:2928`) |
| **Any new server contract** — no `profile` filter on `session.active_list`, no new route, no plugin change | The conformance script rejects a `profile` filter (`scripts/check-gateway-scenario-conformance.py:356-359`); upstream work goes upstream (`AGENTS.md` Non-negotiables) |
| **Any version bump** | `RELEASE.md:239-245`; `CHANGELOG.md:7` `[Unreleased]` only |
| Per-**message** categories/tags/folders | No message-level field on any model; `archived` is the only durable flag and Android keeps no second registry (`docs/decisions.md:2810-2811`) |
| Cross-profile "all conversations" browsing in Bot Mode; a persistent "last-open conversation per bot" | The sub-menu is per owner; ADR 48/67 keep restore semantics server-owned. (design D9(a)) |
| Creating/deleting conversations from the sub-menu; prune | `POST /api/sessions/prune` is an owner-level cleanup action with a required preview (`DashboardApiClient.kt:1471-1500`); not a row action |
| Group rooms (unchanged, read-only) | `ui/screens/BotModeScreen.kt:768` route untouched |
| Provisional `proactive:` Threads appearing in the bot list | A REST directory cannot contain them; one explicit sentence added to the surface's copy/TODO (judge N8) — they remain drawer-only |
| **The mockup's `＋ New conversation` row (screen 2)** | **DEFERRED** — `Screen.BotChat` requires a non-blank `sessionId` and renders "open failed" otherwise (`ui/RelayApp.kt:2677-2689`), so a fresh-draft entry is a route-contract change, not a row. Owner-visible effect: screen 2 ships without that row; the first follow-up phase adds draft entry via `ConversationBindingController.startFreshDraft` (`viewmodel/ConversationBindingController.kt:114`) |
| **The mockup's per-profile conversation count on screen 1** ("2 conversations") | **DEFERRED** — the roster carries no conversation count (`data/BotModeData.kt:38-48,117-122`) and obtaining one costs a directory per profile per gateway, i.e. exactly the cost owner D3 rejects. Owner-visible effect: screen 1 shows recency only; screen 2 shows **"N open"** once its own directory has loaded |
| The mockup's "N open" in screen 1's header | **DEFERRED**, same reason as above; screen 1's header shows the number of visible profiles instead |

---

## 2. OWNER DECISIONS (BINDING)

| ID | Decision (verbatim meaning) | Consequences for the work |
|---|---|---|
| **D1** | **"Close" = archive.** The open list is `archived=exclude`; archive is reversible. | The sub-menu's default read is `archived = "exclude"` (today's caller passes `"include"`, `ProfileController.kt:826` — wrong for a list branded "not closed"). An explicit `include archived` view re-issues the same read with `"include"` and marks rows. The archive action writes through the **route's** profile/connection (`DashboardApiClient.kt:1407-1431`). Opening an archived conversation does **not** auto-unarchive. No second local flag registry (ADR 46). |
| **D2** | **The status is an indicator that *reacts*; there is no "done" state and none may be faked.** | The vocabulary is exactly what the platform produces: `NeedsInput` (client-derived pending input, incl. upstream `waiting`), `Starting`, `Working`, `BackgroundWork` (conversation-level only). `"done"` renders as **absence of a light + a recency line**. `Idle`/`Unavailable`/`Revalidating` must never be dressed up as a state. Copy must never say "complete"/"finished". |
| **D3** | **Status appears only for conversations that actually have one — no fleet polling, no per-gateway `session.active_list` lease per refresh.** | Live status exists **only** for the **active connection**, sourced from the activity already produced for it (existing projection + directory attribution). Every other saved gateway's rows show **recency + the existing stale/offline marker** (`BotModeController.kt:140-155`; `BotModeScreen.kt:581-588`). No new activity RPC is added anywhere. Where attribution is unresolved the row shows **no light** and the list shows one bounded, honest disclosure line. |
| **D4** | **Tabs on top (horizontal strip), not a side rail. No new navigation lane (ADR 12).** | The strip is a `PrimaryScrollableTabRow` **inside** the conversation destination, modelled on `ui/screens/DashboardManagementScreen.kt:1618-1627`. The side rail is out; the tablet/landscape rail remains a future presentation change on the same data contract (owner pair + selected session id). |

---

## 3. REQUIREMENT COVERAGE TABLE

Every ID appears literally below. `DEFERRED` means "not in this plan", with the reason and the owner-visible effect.

| ID | Requirement (short) | Satisfied by | Notes / status |
|---|---|---|---|
| **B1** | stored-vs-lineage-tip session id | **T0.1** (spike, decides the rule) + **T1.1** (model carries both ids) + **T3.1/T5.2** (selection & open rules) | Resolved as a *rule*: rows carry `(storedSessionId, resolvedSessionId)`; open uses the resolved tip when known (matches today's canonical path `BotModeScreen.kt:154`); the tab's `selected` matches the **bound id first**, then the stored id, and shows **no highlight** on ambiguity. The remaining empirical unknown (does the REST directory ever return a tip?) is settled by T0.1, **not** by inference. |
| **B2** | the profile-session lister arms the readiness barrier | **T0.2** (spike asserts barrier clears) + **T5.0** (wiring + barrier assertion + logging guard) | `automaticGatewayWorkDeferred()` returns `false` fast only when `streamingEndpoint != "gateway" \|\| profileSessionLister == null` (`ChatViewModel.kt:5508-5518`) and gates the activity poll at `:2497-2502`. A lister that publishes success for the wrong owner keeps the barrier closed ⇒ badges silently never appear. |
| **B3** | the activity registry/projection lives on `ChatViewModel`; Bot Mode has no activity path | **T1.4** (bridge: one publisher, installed for the main ChatViewModel in the binder) + **T5.0** (bot route feeds its own scope) | Bot Mode reads a connection-scoped snapshot published by the active connection's `ChatViewModel` (emitted next to `publishSessionActivityProjection()`, `ChatViewModel.kt:562-576`). Rows whose `connectionId != activeConnectionId` are **never** lit (D3). |
| **B4** | "Background work" cannot be a profile-level light | **T1.2** (profile vocabulary = {NeedsInput, Starting, Working}) | Reason: `listProcesses()` returns early unless *this client* holds a live/stored session id (`GatewayChatClient.kt:2488-2494`) and the projection is single-slot on `chatHandler.currentSessionId` (`ChatViewModel.kt:2669-2691`). `BackgroundWork` stays on the **conversation chip and tab**, with a content-description label. |
| **B5** | status scope unit is `(connection, profile)`; today's roster lease is fixed to `default` | **T2.2** + **T1.4** | No per-gateway poll is added (D3). The active connection's snapshot is keyed by `(connection, profile)` and Bot Mode matches on the composite key with `allowBareSessionIds = false` — never the lease's `default` identity. |
| **B6** | screen-local state pinned to the entry conversation; stale/age marker off the active connection | **T5.1** (route arg = entry id; selection reads `ChatViewModel.currentSessionId`; every `remember(route.key, sessionId)`/host re-keyed) + **T2.2** (non-active gateways reuse the existing stale marker; **no** new age field) | The `DisposableEffect(… route.key)` at `BotChatScreen.kt:162` must **not** re-run on a switch; the composer key, media-viewer host and queue-editing key must follow the **bound** id. |
| **D1** | Close = archive | T3.4 (default `exclude` + Include-archived view) + T3.5 (archive row action) | See §2. |
| **D2** | Reacting indicator, no faked "done" | T1.2 (policy) + T2.3 (row) + T2.4 (disclosure) + T5.4 (chip/tab badges) | See §2. |
| **D3** | Status only where it exists; no fleet polling | T1.4 + T2.2 + §1.2 non-goal | See §2. |
| **D4** | Tabs on top, no new lane | T5.2 + §1.2 non-goal | See §2. |
| **C1** | ADR 12 — no second lane | T5.2 (strip is inside the conversation destination) + T3.1 (sub-menu is inside the Bot Mode stack) | Bottom nav untouched; hamburger untouched (`docs/decisions.md:272,2928`) |
| **C2** | ADR 48 — no fake activity indicator; colour never communicates activity or health alone | T1.2 + T2.3 + T5.2 | Every state renders an **icon/dot plus a text label or `contentDescription`**; selection is carried by border/weight + label, never colour alone. (Quote as carried in `out/OWNER_DECISIONS.md:26-27`.) |
| **C3** | ADR 67 — one drawer entry, full-screen messenger list; identity `(connectionId, profile)` | T1.1 + T3.1 + T3.2 | ADR 67 itself names the surfaces: "Both the active Bot strip and conversation list use that pair for stable Compose item identity" (`docs/decisions.md:3988-3990`) |
| **C4** | Supervised Mode extended day one; affordances **absent**, not disabled | T2.5 (rows + light absent) + T3.7 (sub-menu route gated) + T6.3 (assertion) | Gate on the existing predicate: `Connection.isProfileSelectionAllowed(name)` (`runtime/HermesRuntimeBinder.kt:193`) and the surface's own `selected?.name == route.profileName` pattern (`ui/screens/BotChatScreen.kt:173-175`). Deep links/restored routes pass the same gate (`docs/decisions.md:3874-3877,3897`). |
| **C5** | Every new string in canonical + 6 locale catalogs (+ flavor catalogs) with refreshed `source_sha256` | T2.6, T3.8, T5.7, T6.4 | 7 catalogs per source set (`app/src/main/res/values*`, `app/src/sideload/res/values*`); `source_sha256.main` per locale in `docs/localization-status.json`; gate `scripts/check-android-locales.py` |
| **C6** | Rendered behaviour is a human/device gate; no version bumps (`CHANGELOG.md` `[Unreleased]` only) | T6.5 (evidence) + T6.6 | `docs/dev-loop.md:62`; `RELEASE.md:239-245`; `scripts/check-version-tracks.py` |
| **C7** | Never hot-swap a live turn; passive open is read-only | T3.5 + T5.3 + T5.6 | Gateway: `switchSession` detaches into `backgroundTurns` (`ChatViewModel.kt:7922-7948`). Non-gateway: switching unavailable while streaming, with a one-line reason (`:7956-7970`). Opening the sub-menu sends **no** `session.resume/activate/prompt.submit/interrupt` (`docs/decisions.md:4150-4158`; `docs/spec.md:624`). |
| **C8** | the unkeyed `_pendingAttachments` defect must be fixed before tabs make switching one tap | **T4.1**, which lands **before P5 (the strip) is presented for review** | `_pendingAttachments` is `ChatViewModel.kt:964-966`, read by the composer (`BotChatScreen.kt:151,284`) and send (`:8679-8682,8921-8923`), and referenced nowhere inside `switchSession` (`:6112-6160`). |

**Non-ID deferrals** (mirrored from §1.2, all with reason + owner-visible effect):

| Deferred item | Reason | Owner-visible effect |
|---|---|---|
| `＋ New conversation` row (mockup screen 2) | `Screen.BotChat` requires a non-blank session id (`ui/RelayApp.kt:2677-2689`); a draft entry is a route-contract change | Screen 2 ships without the row; follow-up phase adds draft entry via `ConversationBindingController.startFreshDraft` (`:114`) |
| Per-profile conversation count on screen 1 (and its "N open" header) | Roster carries no count (`data/BotModeData.kt:38-48,117-122`); a count costs a directory per profile per gateway (cost rejected by D3) | Screen 1 shows recency + status only; screen 2 shows "N open" after its directory loads |
| Provisional `proactive:` Threads in the bot list | A REST session directory cannot contain client-synthesized rows (`SessionDrawer.kt:144,151`) | The bot list shows real server conversations only; the Thread stays a drawer row (stated on the surface + TODO entry, T6.7) |
| Per-conversation scroll position across a tab switch | Not implementable from today's single `listState` (`BotChatScreen.kt:144`; RECON §5) | Switching returns the transcript to its latest message; disclosed in the PR and TODO (T6.7) |

---

## 4. PHASES

Task sizing is "one cheap builder turn per task". Every task names **files (anchored)**, **change intent**,
**dependency**, **verification**, **risk**, **rollback**. Phases P2–P6 are separate PRs to `dev`.

### P0 — PROVE-FIRST (spikes; temporary wires only, removed at phase end)

| Task | Files (anchored) | Change intent | Verify | Risk | Rollback |
|---|---|---|---|---|---|
| **T0.1** — id-namespace probe | temp wire in `ui/screens/BotChatScreen.kt:162-194`; temp call of `DashboardApiClient.listSessions` (`:1131-1205`) via the route's `botDashboardClient` (`ui/RelayApp.kt:2668-2670`) | On a live host with a profile that has **≥1 compressed** conversation (stored ≠ resolved): log, per directory row, `id` **and** whatever the gateway path exposes (`BotChatTarget.storedSessionId`/`resolvedSessionId`, `data/BotModeData.kt:110-115`; reader `GatewayChatClient.kt:2005-2008`). Answer: does `GET /api/sessions` return **stored** ids for every row, or can it return a lineage **tip**? | Log-derived evidence, no UI. Record the answer in the PR body and `docs/audits/design-qa.md` | Nothing ships; if the sample has no compressed conversation the probe is inconclusive — repeat with `/new` used once in a conversation to force compression (ADR 67 compression path) | delete the temp wire |
| **T0.2** — directory + **barrier** probe | temp `setProfileSessionLister`/`refreshSessions` inside the existing `DisposableEffect` (`ui/screens/BotChatScreen.kt:162-194`), against the route's dashboard client; observe `ChatViewModel.kt:5508-5518` | Prove **both**: (a) the rows are the **route's** profile on a **non-active** gateway (the naive wiring would serve the active connection, `ProfileController.kt:819-820`); (b) the readiness barrier **clears** — `lastSessionRefreshSuccessOwner == (activeProfileContextKey to currentSessionProfileName())` and `automaticGatewayWorkDeferred()` stops returning `true`, and an activity poll actually runs (`:2497-2502`) | Log-derived evidence for (a) **and** (b). (b) is the acceptance criterion — **not** the row count (judge B2) | If the barrier never clears, badges will look like "no status anywhere": P2/P5 reduced per §10 branch conditions | delete the temp wire |
| **T0.3** — attribution probe | same temp wire as T0.2 + observation of `resolveGatewayActiveSessions` (`ChatViewModel.kt:345-372`) | With a host running **≥2 profiles** and **≥1 conversation that is not the attached one**: report the attributed / unresolved split per live row, and confirm/deny that `BackgroundWork` can appear for a **non-attached** conversation (expected: **no** — `ChatViewModel.kt:2669-2691`) | Log-derived evidence: attributed count, unresolved count, whether `ambiguous` fires | If almost nothing resolves, the whole per-row badge plan is decoration ⇒ §10 branch condition (fallback: chip only for the attached conversation + the honest disclosure) | delete the temp wire |

**P0 exit criteria:** three written answers, in the PR body and a dated `docs/audits/design-qa.md` section.
No UI, no released code. **Cannot be CI-verified** (needs a live host with concurrent profiles).

### P1 — DATA AND POLICY (pure Kotlin; no UI, fully unit-testable)

| Task | Files (anchored) | Change intent | Dep | Verify | Risk | Rollback |
|---|---|---|---|---|---|---|
| **T1.1** — conversation model | new `data/BotConversation.kt`; reuse `BotChatTarget` (`data/BotModeData.kt:110-115`) | `BotConversation(connectionId, profileName, storedSessionId, resolvedSessionId, title, activityTimestamp, pinned, archived, messageCount)` + `key()` returning the composite `profile:storedSessionId` form used by the app (`SessionDrawerPolicy.kt:62-63`). No persistence, no new store | — | new pure-JVM `BotConversationKeyTest` | Low | revert the add |
| **T1.2** — status policy (pure) | new `ui/components/BotModeStatusPolicy.kt`; input vocabulary `data/SessionActivityState.kt:4-11`; reuse `sessionDrawerStatus` rank (`SessionDrawerPolicy.kt:130-138`) | `profileLight(states, profileKey, keys)` → `NeedsInput > Starting > Working`, **`BackgroundWork` excluded** (B4), else `null` (D2/B5); `conversationChip(states, key)` → full reachable vocabulary **and keeps `Checking`/`Unavailable` representable** (judge N4 — do not freeze to today's 4); key normalization must equal the projection's (`ChatViewModel.kt:569-573`, incl. the `SERVER_DEFAULT_PROFILE_KEY → "default"` mapping); **no bare-id acceptance anywhere** (`allowBareSessionIds = false`, `SessionDrawerPolicy.kt:93-107`) | T1.1 | `BotModeStatusPolicyTest`: needs-input wins over working; background work never lights a profile row; missing/duplicate owner ⇒ `null`; bare id never matches; the wire/default normalization case both ways | Medium — a wrong normalization silently darkens every light | revert |
| **T1.3** — route-scoped directory reader | new function on `ConnectionViewModel` (beside `botDashboardClient`, `viewmodel/ConnectionViewModel.kt:2457`), using that route's client; params mirror `DashboardApiClient.listSessions` (`:1131-1155`) | Read a **route's own** profile directory: `archived` param default `"exclude"`, `include` for the explicit view, `excludeSources` from the connection's hidden set, one read budget, ≤200-row window via bounded pages (`SessionListPaging.kt:4`). **Fail closed**: any `profileName != route.profileName` returns `null` **and logs** (never an unscoped list, never an empty profile — D1 §5(2)) | — | `BotConversationDirectoryTest`: wrong profile ⇒ `null` + log; `archived` values; window/paging arithmetic | Low–Medium | revert |
| **T1.4** — activity bridge (B3) | emit beside `publishSessionActivityProjection()` (`viewmodel/ChatViewModel.kt:562-576`); new `StateFlow` on `viewmodel/ConnectionViewModel.kt`; install in `runtime/HermesRuntimeBinder.kt:200-224` for the **main** ChatViewModel only; `ambiguous` from `ResolvedGatewayActiveSessions` (`ChatViewModel.kt:340-342`) | Snapshot = `(connectionId, Map<"profile:storedSessionId", SessionActivityState>, ambiguous: Boolean, complete: Boolean)`. Additive: the existing `_backgroundSessionActivityStates` consumer (`ui/screens/ChatScreen.kt:963-988`) is untouched | T1.1, T1.2 | `BotModeActivityBridgeTest`: keys are composite; `ambiguous` propagates; snapshot clears on connection change; a fake publisher installed for a bot route does **not** overwrite the main one | Medium — touches shared VM surface | revert |
| **T1.5** — copy-reservation check (small) | `app/src/main/res/values/strings.xml` (read) | Confirm the new surface's words ("Conversations", "Open", "Archived", "Pending you", "Working", "None running") do not collide on-screen with the existing "Active now" strip's label (`ui/screens/BotModeScreen.kt:405-453`) (judge N10). Outcome: reuse existing words where they exist; rename **only** the new surface's copy, never the already-approved existing copy | — | written note in the PR | Low | — |

### P2 — MESSENGER PROFILE LIST (screen 1) + status light — first phase a human can touch

| Task | Files (anchored) | Change intent | Dep | Verify | Risk | Rollback |
|---|---|---|---|---|---|---|
| **T2.1** — profile-row light + recency | `ui/screens/BotModeScreen.kt:539-604` (row), `:558-571` (title row), new `BotStatusChip` composable in the same file or `ui/components/` | Insert the light into the title row; when `profileLight(...) == null` the row shows **only** recency (`bot.latestActivityAtMs.toBotModeTime(nowMs)`, `:566-570`). Chip = icon/dot **plus** `contentDescription` label (C2). No light for rows whose `connectionId != activeConnectionId` (D3) | T1.2, T1.4 | `BotModeScreenTest` additions + `BotModeProfileListLightScreenshotTest` (Robolectric, `@Config(qualifiers = "w390dp-h844dp-432dpi")`, `BotModeScreenshotTest.kt:29-37` convention) | Low–Medium | revert |
| **T2.2** — stale/recency honesty off the active connection | `ui/screens/BotModeScreen.kt:572-589` | Keep the existing `R.string.bot_mode_offline` marker for `bot.stale` rows, and **never** render a light for a non-active gateway; no new age/`activityObservedAtMillis` field is introduced (D3 means there is nothing to timestamp) | T2.1 | unit/screenshot case: a stale row renders with marker and no light | Low | revert |
| **T2.3** — status derivation wiring | `ui/screens/BotModeScreen.kt:210-241` (params + visible rows) | Read the activity snapshot from `ConnectionViewModel` and derive each row's light with T1.2, using the row's owner as the profile key. New `BotModeContent` params **must carry defaults** (screen is `internal` and driven directly by tests, `:210-227`; `BotModeScreenshotTest.kt:41-55`) | T2.1 | `BotModeScreenTest` | Low | revert |
| **T2.4** — bounded honesty disclosure (D2) | `ui/screens/BotModeScreen.kt` (list header area) | One dismissible line, shown **only** when the snapshot is complete and `ambiguous == true`: state that some running conversations on this host cannot be attributed to a profile and are therefore not shown with a status (never "0 running"; never a list) — this is also where the user is told that "no light" can mean *quiet* **or** *running but unattributable* (judge N3) | T2.3 | screenshot of the shown/hidden states; unit test for the trigger | Low | revert |
| **T2.5** — supervised gating (C4) | `ui/screens/BotModeScreen.kt` (rows + chip) | Gate the profile rows and the light on the existing predicate (`Connection.isProfileSelectionAllowed`, `runtime/HermesRuntimeBinder.kt:193`); under a hiding policy they are **absent**, not disabled | T2.1 | unit test + screenshot of the absent state | Medium — a leak here is a policy regression | revert |
| **T2.6** — strings + catalogs (C5) | `app/src/main/res/values/strings.xml` + `values-de/es/ja/ru/b+pt+BR/b+zh+Hans/strings.xml`; `docs/localization-status.json` | Add every new user-visible string (chip labels, content descriptions, disclosure line) to canonical **and** the 6 locale catalogs; refresh `source_sha256.main` for each non-canonical locale using the gate's own computation | T2.1–T2.4 | `python3 scripts/check-android-locales.py` exits 0 | Medium (gate hard-fails on a miss) | revert |

### P3 — CONVERSATION SUB-MENU (screen 2)

| Task | Files (anchored) | Change intent | Dep | Verify | Risk | Rollback |
|---|---|---|---|---|---|---|
| **T3.1** — new route + tap-target split | `ui/RelayApp.kt:497-520` (new `Screen.BotConversations` copied in shape from `Screen.BotGroup`), registration beside `:2640-2645`, navigation from `ui/screens/BotModeScreen.kt:148-161,472-479` | Row **body** → sub-menu for that owner; a trailing affordance keeps today's one-tap canonical chat (`ensureCanonicalBotChat`, `:153-156`) so existing muscle memory and ADR 67's canonical-chat contract survive. Sub-menu opens with **no** session RPC | T2.1 | navigation test; device check of both tap targets | Low–Medium | revert |
| **T3.2** — sub-menu screen | new `ui/screens/BotConversationsScreen.kt`; route client acquired/disposed the way `ui/RelayApp.kt:2662-2676` does | Header: profile display name + gateway label + **"N open"** from the loaded directory. Body: `LazyColumn` keyed by the composite owner-triple; rows show title + chip (T1.2) + "N messages · <relative time>" (mockup screen 2, `out/mockup_messenger_v2.html:76-88`). Loading presentation on entry (never an empty flash — pattern `viewmodel/ChatViewModel.kt:5548-5553`); distinct empty/error/unavailable states; paging at `totalItemsCount - 5` (drawer idiom `ui/components/SessionDrawer.kt:358-380`); "showing the 200 most recent" note when the window is full | T1.1, T1.3, T3.1 | `BotConversationsScreenTest` + screenshot; device evidence for load/empty/error | Medium — the route-scoped client's lifetime is owned by the route (`RelayApp.kt:2671-2676`), so the screen must own its own acquire/release and never assume the BotChat route's client | revert |
| **T3.3** — Open / Include-archived view (D1) | `ui/screens/BotConversationsScreen.kt`; read params `DashboardApiClient.kt:1152-1155` | A two-item selector (mockup screen 2 tabs, `:77`): "Open" = `archived="exclude"` (default), "Archived" = `"include"` with those rows marked. Archived rows are never in the default view | T3.2 | unit test on the param + screenshot of both | Low | revert |
| **T3.4** — open a conversation | `ui/screens/BotConversationsScreen.kt` → navigate to `Screen.BotChat` | Open with the row's **resolved tip when known, else the stored id** (T0.1 decides whether a tip exists on this surface), matching the canonical path's semantics (`ui/screens/BotModeScreen.kt:153-156`). Opening is **passive**: no `session.resume/activate/prompt.submit/interrupt` (C7) | T3.1, T0.1 | unit test on the chosen id; device check that the transcript for that row loads and Back returns to the list **without leaving Bot Mode** | Medium (id namespace) | revert |
| **T3.5** — archive-as-close row action (D1, C7) | `ui/screens/BotConversationsScreen.kt`; write path `network/upstream/DashboardApiClient.kt:1407-1431`, `viewmodel/connection/ProfileController.kt:887-900` | Archive against the **route's** profile/connection; optimistic row update, rollback on a failed write, refresh from server truth after success (ADR 46 discipline, `docs/decisions.md:2820-2823`). Opening an archived row never auto-unarchives | T3.3 | unit test for the call target + rollback; device check that the row leaves the default view and is recoverable via "Archived" | Medium | revert |
| **T3.6** — supervised gating for the new route (C4) | `ui/screens/BotConversationsScreen.kt` + its `composable` block | The sub-menu is absent/blocked under a hiding policy, using the same predicate as T2.5; a deep link or restored route passes the same gate | T2.5, T3.2 | unit test + a signed-off assertion task in T6.3 | Medium | revert |
| **T3.7** — strings + catalogs (C5) | as T2.6 | Titles, chip labels, "N open", "N messages", states, archive action, filter labels, plural-form strings (every locale must provide `other`, `docs/localization.md:33-36`) | T3.2–T3.6 | locale gate | Medium | revert |

### P4 — PER-CONVERSATION ATTACHMENT FIX (C8) — must land before the strip is reviewed

| Task | Files (anchored) | Change intent | Dep | Verify | Risk | Rollback |
|---|---|---|---|---|---|---|
| **T4.1** — key `_pendingAttachments` | `viewmodel/ChatViewModel.kt:964-966` (state), composer read `ui/screens/BotChatScreen.kt:151,284`, send path `:8679-8682,8921-8923`; precedent for per-session keying `ChatViewModel.kt:8697-8715` | Keep attachments per `(contextKey, sessionId)` and expose the **bound** conversation's list through the existing public surface, so a file staged in A can never ride into B. **Do not** clear on switch (silent loss is forbidden) | — | new `ChatViewModelPendingAttachmentsTest` (isolation across a switch; the main chat's list is unchanged for the bound session) + manual main-chat regression | High blast radius (shared with the main chat) — keep the change additive; the main chat's tests must stay green | revert the single commit (the strip has not shipped) |

### P5 — TOP TAB STRIP + IN-PLACE SWITCHING (screen 3)

| Task | Files (anchored) | Change intent | Dep | Verify | Risk | Rollback |
|---|---|---|---|---|---|---|
| **T5.0** — wire the bot route's own directory (B2) | `ui/screens/BotChatScreen.kt:162-194` | Install `setProfileSessionLister` against the **route's** client and call `refreshSessions()` once on entry, with the T1.3 fail-closed guard. The `DisposableEffect` stays keyed on `route.key` | T1.3, T0.2 | barrier assertion per T0.2 (owner success + poll runs) as an automated check where possible, device evidence otherwise; **logging, not an empty list**, on a profile mismatch | High — this seam can suppress activity polling (B2) | remove the two calls |
| **T5.1** — bound-id source of truth (B6) | `ui/screens/BotChatScreen.kt:93,140-149,162,183-188`; bound id `ChatViewModel.kt:4456-4457` | The route arg becomes the **entry** conversation only. Selection reads `chatViewModel.currentSessionId`. Re-key to the bound id: `ChatMediaViewerHost` `:93`, the three `remember(route.key, sessionId)` `:145-149`, and the queue-editing key. The `DisposableEffect` `:162` must **not** re-run on a switch | T4.1 | unit test that a switch does not change the route key/ViewModel key (`ui/RelayApp.kt:2691-2693`); device check that composer/media/queue follow the visible conversation | High — this is where "obvious naive implementation" silently breaks | revert |
| **T5.2** — the strip | `ui/screens/BotChatScreen.kt` between the TopAppBar block (`:200-260`) and the body (`:339-411`), modelled on `ui/screens/DashboardManagementScreen.kt:1618-1627` (tab row → `HorizontalDivider` → weighted body) | `PrimaryScrollableTabRow(selectedTabIndex = …)`; items = the profile's directory rows in the app's ordering (`SessionDrawerPolicy.kt:130-159`, NeedsInput first); each tab = title + state dot **with a text/`contentDescription` label** (C2, D2); long titles ellipsised; the strip renders the already-loaded window (no independent paging); `selected` matches the bound id first then the stored id, and shows **no** highlight when ambiguous (B1) | T5.0, T5.1 | `BotChatTabStripTest` (Robolectric): scroll with ≥5 conversations; `selected` tracks the bound conversation; no selection on ambiguity; screenshot at 200 % font (T6.5) | Medium–High | revert |
| **T5.3** — tab tap switches **in place** (D4, C7) | tap → `chatViewModel.switchSession(id)` (`ChatViewModel.kt:6112-6208`) | Never a route push (a push would mint one `ChatViewModel` + one `ChatHandler` per conversation on a shared refcounted client, `ui/RelayApp.kt:2691-2693`). Gateway detaches (`:7922-7948`); non-gateway cancels (`:7956-7970`) so it is unavailable per streaming conversation with a one-line reason | T5.2 | device check: a gateway turn **detaches** and its tab keeps a running badge; unit test that no new ViewModel key is minted | High (live-turn handling) | revert |
| **T5.4** — badges on the tabs (D2) | `ChatViewModel.kt:7932-7948` (`backgroundTurnCheckpoints`, `backgroundNeedsInputKeys`), queue `:6133` + `ChatMessageQueue` (`BotChatScreen.kt:278-292`) | Running badge for a detached/running conversation; NeedsInput badge when its checkpoint holds a pending ask; queue-count badge. Pending-ask **restore is conditional** — `restorePendingAsk` runs only under `hasPendingWork && handle != null && (running \|\| autoContinue != null)` (`ChatViewModel.kt:8127-8148`), so copy must not promise restoration (judge N2); the badge stays until the conversation is reopened and re-observed | T5.3 | unit test for badge derivation; device check with an approval pending in a backgrounded conversation | Medium | revert |
| **T5.5** — per-conversation chip in the message view | `ui/screens/BotChatScreen.kt` top bar block (`:200-260`) | One chip using the **same** function as the row/tab (`conversationChip`, T1.2) so the three render sites can never disagree; the chip is the only place a single conversation's status is stated | T1.2, T5.1 | screenshot + unit test | Low | revert |
| **T5.6** — SSE/defensive branch | `ui/screens/BotChatScreen.kt` strip | On a non-gateway transport, per-conversation switching is unavailable **while that conversation is streaming**, with a one-line non-modal reason; never silently cancel. Note: bot routes set `streamingEndpoint = "gateway"` (`ui/screens/BotChatScreen.kt:165`), so this branch may be unreachable (`UNVERIFIED`) — implement it defensively, do not add tests that assert unreachable state | T5.3 | code-level check only | Low | revert |
| **T5.7** — strings + catalogs (C5) | as T2.6 | Tab content descriptions, chip labels, badge labels, the SSE reason line | T5.2–T5.6 | locale gate | Medium | revert |

### P6 — TESTS, GATES, LOCALIZATION AND EVIDENCE CLOSURE

| Task | Files (anchored) | Change intent | Verify |
|---|---|---|---|
| **T6.1** — register the suites | `scripts/android-prepush.py:23-55` (`FOCUSED_TESTS`) | Add the new/affected suites (T6.1 list in §5). Note today **no** `BotMode*`/`BotChat*`/`SessionDrawer*` suite is registered, so these screens' tests never run in the focused lane | the focused lane actually runs them (`:app:testSideloadDebugUnitTest`, `scripts/android-prepush.py:122`) |
| **T6.2** — defaults preserved | `ui/screens/BotModeScreen.kt:210-227` | Every new `BotModeContent` parameter has a default, and the existing screenshot fixture (`BotModeScreenshotTest.kt:41-55`) still compiles unchanged | compile + that test |
| **T6.3** — supervised-mode assertion | new test beside the sub-menu suite | A falsifiable assertion that the sub-menu and the profile light are absent under a hiding policy (naming the predicate: `Connection.isProfileSelectionAllowed`, `runtime/HermesRuntimeBinder.kt:193`) | the test |
| **T6.4** — localization closure | catalogs + `docs/localization-status.json` | `python3 scripts/check-android-locales.py` exits 0 for both source sets | the gate |
| **T6.5** — rendered evidence + design QA | `app/build/ui-evidence/*.png`; `docs/audits/design-qa.md` (extend the existing "Android Bot Mode messenger workspace" section, `:58-77`) | Capture: profile list (lit / unlit / stale / ambiguous-disclosure), sub-menu (default / archived / empty / error / 200 % font / TalkBack labels), conversation (strip selected / detached-running badge / NeedsInput badge / SSE reason). Compare against `out/mockup_messenger_v2.html` screen 1/2/3 and state the two deliberate deviations (no `＋ New conversation` row; no per-profile count) | human gate (C6) |
| **T6.6** — release hygiene | `CHANGELOG.md:7` `[Unreleased]` | One entry under Changed/Fixed; **no** version files touched | `python3 scripts/check-version-tracks.py`, `python3 scripts/check-android-release-notes.py` |
| **T6.7** — record the deferrals | `docs/project/TODO.md` (the repo's single home for "what's next", `AGENTS.md`) | The four non-ID deferrals from §3, plus the upstream ask (profile metadata/filter or an aggregate activity route — `docs/upstream-surface-matrix.md:297-300`), plus "message-level categories are an upstream field" | review |
| **T6.8** — docs, if the surface is user-visible | `user-docs/features/*.md` (`docs/features/` does not exist — RECON §8) | A short "Bot Mode: profiles, conversations, tabs" page | review |

---

## 5. TEST PLAN

Conventions: pure-JVM policy tests for logic; Robolectric Compose tests for screens with
`@Config(qualifiers = "w390dp-h844dp-432dpi")` and `captureRoboImage` (precedent
`app/src/test/kotlin/com/hermesandroid/relay/screenshots/BotModeScreenshotTest.kt:27-59`); rendered evidence into
`build/ui-evidence/` plus a `docs/audits/design-qa.md` section (precedent `:58-77`).

| Test (new unless marked) | Type | What it proves | Phase |
|---|---|---|---|
| `com.hermesandroid.relay.data.BotConversationKeyTest` | pure JVM | composite key form (never a title, handle or bare id; `docs/decisions.md:3988-3993`); owner triple distinct across connections | P1 |
| `com.hermesandroid.relay.ui.components.BotModeStatusPolicyTest` | pure JVM | priority `NeedsInput > Starting > Working`; **BackgroundWork never lights a profile row**; absent/unattributable ⇒ `null` (no fake state); `Checking`/`Unavailable` remain representable; the wire-default profile normalization matches the projection | P1 |
| `com.hermesandroid.relay.viewmodel.connection.BotConversationDirectoryTest` | pure JVM | the route-scoped reader uses its own route's profile; wrong profile ⇒ `null` **and** a log line; `archived` param; window/paging arithmetic | P1 |
| `com.hermesandroid.relay.viewmodel.BotModeActivityBridgeTest` | pure JVM | snapshot keys are composite; `ambiguous` propagates; clearing on connection change; the main chat's projection is unchanged | P1 |
| `com.hermesandroid.relay.ui.screens.BotModeScreenTest` *(exists — extend)* | Robolectric | tap targets (body vs trailing affordance); light rendered/absent/stale; disclosure shown only when complete + ambiguous; supervised absence | P2/P3 |
| `com.hermesandroid.relay.screenshots.BotModeProfileListLightScreenshotTest` | Robolectric + image | lit / unlit / stale / disclosure states at the standard qualifier | P2 |
| `com.hermesandroid.relay.ui.screens.BotConversationsScreenTest` | Robolectric | load / empty / error / unavailable; default excludes archived; "Archived" shows and marks them; paging at `totalItemsCount - 5`; row identity is the owner triple | P3 |
| `com.hermesandroid.relay.screenshots.BotConversationsScreenScreenshotTest` | Robolectric + image | screen 2 against the approved mockup (minus the two deferred items) | P3 |
| `com.hermesandroid.relay.viewmodel.ChatViewModelPendingAttachmentsTest` | pure JVM | a staged attachment in A never appears in B; nothing is silently dropped on switch; main-chat behavior unchanged | P4 |
| `com.hermesandroid.relay.ui.screens.BotChatTabStripTest` | Robolectric | strip scrolls with ≥5 conversations; `selected` tracks `currentSessionId`; **no** selection under id ambiguity; a switch mints no new route/ViewModel key; badges derive from the checkpoint/queue registries | P5 |
| `com.hermesandroid.relay.ui.screens.BotChatScreenBindingTest` *(exists — extend)* | Robolectric | the installed lister is the route's own; the guard logs on a profile mismatch; the media/composer/queue keys follow the bound id | P5 |
| Supervised-mode gate test (T6.3) | pure JVM or Robolectric | the sub-menu + light are absent under a hiding policy | P6 |

**`scripts/android-prepush.py: FOCUSED_TESTS` registrations T6.1 adds** (exact names, package-qualified):
`com.hermesandroid.relay.ui.screens.BotModeScreenTest`,
`com.hermesandroid.relay.screenshots.BotModeScreenshotTest`,
`com.hermesandroid.relay.screenshots.BotModeProfileListLightScreenshotTest`,
`com.hermesandroid.relay.ui.screens.BotConversationsScreenTest`,
`com.hermesandroid.relay.screenshots.BotConversationsScreenScreenshotTest`,
`com.hermesandroid.relay.ui.screens.BotChatTabStripTest`,
`com.hermesandroid.relay.ui.screens.BotChatScreenBindingTest`,
`com.hermesandroid.relay.viewmodel.connection.BotModeControllerTest` *(exists)*,
`com.hermesandroid.relay.viewmodel.connection.BotConversationDirectoryTest`,
`com.hermesandroid.relay.viewmodel.BotModeActivityBridgeTest`,
`com.hermesandroid.relay.viewmodel.ChatViewModelPendingAttachmentsTest`,
`com.hermesandroid.relay.data.BotConversationKeyTest`,
`com.hermesandroid.relay.ui.components.BotModeStatusPolicyTest`.

*(Registering all of them is deliberate: today none of the bot suites is in the list (verified, `:23-55`), and
no gate pins a screen's nav wiring — only `scripts/check-android-tailscale-settings.py` does that — so an
unwired new screen would otherwise pass every mechanical check.)*

---

## 6. GATES AND COMPLIANCE CHECKLIST

| Obligation | Command / check that proves it | Source |
|---|---|---|
| Localization, canonical + 6 locale catalogs, refreshed hashes | `python3 scripts/check-android-locales.py` (in `REPOSITORY_CHECKS`, `scripts/android-prepush.py:62`) | `docs/localization.md:19-60` |
| Flavor catalogs unaffected (main-only strings) | same gate, run for the sideload source set — the flavor canonical is a 4-string overlay (`app/src/sideload/res/values/strings.xml`), so the gate should pass untouched; if it fails, the message names the missing entries | `docs/localization.md:27-28` |
| ≥200 % font + TalkBack labels, both flavors | human pass per T6.5 | `docs/localization.md:51-55` |
| No Java-21 `SequencedCollection` calls | `python3 scripts/check-android-collection-apis.py` (`:64`) | gate docstring |
| Every OkHttp client through `HermesClients.build(...)` | `python3 scripts/check-android-hermes-transports.py` (`:65`) | gate docstring |
| Tailscale route policy unchanged | `python3 scripts/check-android-tailscale-settings.py` (`:66`) | gate |
| Capability/first-party surface | `python3 scripts/check-android-capabilities.py` (`:61`) | gate |
| No version bumps; release notes untouched | `python3 scripts/check-version-tracks.py` (`:68`), `python3 scripts/check-android-release-notes.py` (`:67`); `CHANGELOG.md:7` `[Unreleased]` only | `RELEASE.md:239-245` |
| ADR 12 (no second lane) | review checklist: bottom nav still 4 tabs; hamburger still only the drawer; the strip lives inside the conversation destination | `docs/decisions.md:272,2928`; `docs/spec.md:589-601` |
| ADR 46 (`archived` upstream-owned, no second registry) | code review: the only flag touched is `archived`, written through PATCH; optimistic + rollback + server-truth refresh | `docs/decisions.md:2810-2823` |
| ADR 48 (no fake indicator; no colour-only communication) | review checklist: every state has an icon/text label or `contentDescription` | `out/OWNER_DECISIONS.md:26-27`; `docs/decisions.md:2906-2912` |
| ADR 67 (identity pair; never switch the global connection; no foreign database) | review checklist: every read uses the route's own dashboard client; no `activeConnectionId` resolution in the bot surface | `docs/decisions.md:3970-4001` |
| ADR 69 (passive open is read-only) | code review + a test/log assertion that opening the sub-menu sends no `session.resume/activate/prompt.submit/interrupt` | `docs/decisions.md:4150-4158`; `docs/spec.md:624` |
| Supervised Mode day-one, fail closed | T6.3 assertion | `docs/decisions.md:3874-3877,3897` |
| Focused lane + lint | `./scripts/dev.sh prepush` (or `scripts/android-lane.ps1` while editing); cloud `Android On-Demand` on an exact pushed SHA | `AGENTS.md` Android bullet; `scripts/android-prepush.py:114-125` |
| Public-repo writing hygiene (no names/infrastructure/AI narration) | PR body + CHANGELOG review | `AGENTS.md` |

---

## 7. VERIFICATION MATRIX — what CI proves vs what only a device proves

`docs/dev-loop.md:62`: "Android UI/behavior … **human gate** — a fix is never 'done' from CI alone."

| Claim | CI / mechanical lane | Human / device gate |
|---|---|---|
| Composite keying; status aggregation (incl. B4 exclusion); route-scoped reader guard; attachment isolation | ✅ pure-JVM tests | — |
| The strip scrolls, selection tracks the bound conversation, tab tap switches **in place**, badges derive correctly | ✅ Robolectric assertions on state | ⚠ **device**: the visual strip, the actual transcript swap, and that a gateway turn keeps running while detached |
| Localization completeness + hashes; ADR/grep gates; no version bumps | ✅ gates | — |
| A **light** looks like an indicator that reacts (not a "done" tick) | ❌ | ⚠ device, dark + light theme, both flavors |
| Colour never communicates activity alone | ❌ (only that a label exists) | ⚠ device **with TalkBack on** |
| Layout at 200 % font; long conversation titles in the strip | ❌ | ⚠ device |
| Live attribution behaviour (does a sibling conversation get a badge? does `ambiguous` fire?) | ❌ | ⚠ **live host with ≥2 profiles and a concurrent conversation** (T0.3) |
| Real attachment regression in the main chat | ✅ unit tests | ⚠ manual: stage a file in the main chat, switch, send |
| Sub-menu load/empty/error/archived/stale states | partial (Robolectric) | ⚠ device with a slow/unreachable gateway |
| CI cannot sign off rendered behaviour | — | cite `docs/dev-loop.md:62` in every PR body |

---

## 8. SPIKES / MUST-PROVE-FIRST

| # | Must be proven | Smallest experiment | Decides |
|---|---|---|---|
| **S1** (=T0.1) | Which id a conversation *is*: does `GET /api/sessions` ever return a lineage **tip**, or always the stored row? (`DashboardApiClient.kt:1125` says stored; the bot route carries the tip, `ui/screens/BotModeScreen.kt:154`) | one profile with a compressed conversation; log directory `id`s vs `BotChatTarget.storedSessionId/resolvedSessionId` | the tab's selection rule and the sub-menu's open id (B1) |
| **S2** (=T0.2) | That a route-scoped lister (a) reads the **route's** profile on a non-active gateway and (b) **clears the directory barrier** so activity polling runs (`ChatViewModel.kt:5508-5518`, `:2497-2502`) | temporary wiring in `ui/screens/BotChatScreen.kt:162-194`; assert `lastSessionRefreshSuccessOwner` and that a poll fires | whether badges can exist at all (B2) |
| **S3** (=T0.3) | How much of `session.active_list` the client can actually attribute for conversations it is **not** attached to (expected: `resolveGatewayActiveSessions` attributes siblings only via the directory) and whether `BackgroundWork` can ever describe a non-attached conversation | same temp wire; log attributed/unresolved per row over a few refresh cycles | the profile-row light's usefulness and the exact copy of the disclosure (B3, B4) |

---

## 9. RISK REGISTER

| # | Risk | Likelihood | Blast radius | Mitigation | If it fails |
|---|---|---|---|---|---|
| R1 | Attribution is much thinner than assumed ⇒ profile lights stay dark | Medium–High | The feature's headline value | S3 first; lights are **derived, never guessed**; the disclosure line states the limitation on the surface | Ship S-1 with recency + disclosure only (a defined reduction, not a silent one) |
| R2 | Id-namespace mismatch (stored vs tip) ⇒ the strip loses its selection on compressed conversations | Medium | The one behaviour B3 is defined by | S1 first; the model carries both ids; ambiguity ⇒ **no** highlight, never a wrong one | Fall back to tip-equality only, with the stored id as the badge key |
| R3 | The lister arms the barrier and suppresses activity polling ⇒ "no status anywhere" | Medium | All status surfaces | T0.2's acceptance criterion is the barrier, not the row count; the fail-closed guard **logs** instead of rendering an empty profile; T6.1 puts the assertions in the focused lane | Remove the wiring; fall back to a strip without live badges (chip from the attached conversation only) |
| R4 | Keying `_pendingAttachments` regresses the main chat | Medium | Every send in Chat | Additive change, keyed by the existing `(contextKey, sessionId)` precedent; existing main-chat tests must stay green; the fix lands in its own commit before the strip is reviewed | Revert the commit; the strip is held back (its precondition) |
| R5 | Route-scoped client/lease lifetime: the sub-menu assumes the BotChat route's client, which is shut down on dispose (`ui/RelayApp.kt:2671-2676`) | Medium | The sub-menu's data | T3.2 acquires and releases its own lease/client the same way the route does | Sub-menu falls back to the main connection's client — **not acceptable** (ADR 67); hold the phase instead |
| R6 | Localization gate hard-fail / missed strings (incl. plural forms) | Medium | Merge blocked | Strings are budgeted inside each screen's task; `check-android-locales.py` runs in the focused lane | Fix the catalogs and the `source_sha256` entries; the gate names what is stale |
| R7 | 200-row window truncation hides an older conversation | Medium | List completeness | Window cap 200 with bounded ≤100 pages (`SessionListPaging.kt:4`); visible "showing the 200 most recent" note | Accept + disclose; a search affordance is follow-up work |
| R8 | Supervised-Mode leak (profile rows or light visible under a hiding policy) | Low–Medium | Policy regression | T2.5/T3.6 gate on the existing predicate; T6.3 is a falsifiable assertion | Fix forward before merge — policy issues block |
| R9 | Scope creep into the drawer's semantics (a "second session list") | Medium | ADR 46/48/67 coherence | The sub-menu is a bot-scoped view of exactly the same REST directory; the drawer and the hamburger are untouched; the overlap is stated in the PR | Escalate to the owner: it is a product decision, not a code fix |
| R10 | Cross-gateway double listing (same profile name on two gateways) | Medium | Wrong rows shown | Identity is the owner pair throughout (roster key `ui/screens/BotModeScreen.kt:89-93`; ADR 67 `:3988-3993`); install_id collapse picks one route (first wins by active/stale/order, `BotModeController.kt:282-286`) — say so in the PR (judge N9) | Add a chooser as follow-up; never merge rows across connections |

---

## 10. SEQUENCING AND GATE-BETWEEN-PHASES

```
P0 (S1,S2,S3 answers)  ──gate: all three answered; branch conditions chosen──▶
P1 (model + policy + reader + bridge)  ──gate: pure-JVM tests green, locale gate still green──▶
P2 (profile list + light + disclosure) ──gate: focused lane green + device pass on the list──▶
P3 (sub-menu)                          ──gate: focused lane green + device load/empty/error──▶
P4 (attachment keying)                 ──gate: main-chat regression clean──▶
P5 (strip + switching)                 ──gate: device proof that a detached turn keeps its badge──▶
P6 (tests, gates, evidence, docs)
```

- **Branch conditions from P0** (each is a pre-decided reduction, not a re-planning session):
  - If S2's barrier never clears → P5 drops live badges; the chip reflects only the attached conversation.
  - If S3 finds sibling attribution impossible → P2's light is replaced by recency + the disclosure; P1.2's
    `profileLight` stays (unused) behind the same call site.
  - If S1 finds the directory can return tips → the selection rule uses tip equality first and the stored id as
    the badge key; otherwise stored-first.
- **What must be green before the next phase starts:** the focused lane (including the newly registered suites)
  and the seven `REPOSITORY_CHECKS` (`scripts/android-prepush.py:60-69`) at the phase's tip SHA. Rendered
  behaviour is signed off by a human per phase (`docs/dev-loop.md:62`) — a phase is not "done" from CI alone.
- **First phase a human can actually touch on a phone: P2.** P2 changes no navigation (the row tap keeps
  opening the canonical conversation until P3 lands), so it is independently installable and reviewable: the
  profile list with a reacting status indicator and honest recency.
- P3 and P5 are the two phases that must be reviewed by the owner on a device before merge.

---

## 11. DEFINITION OF DONE (mapped to the owner's words)

| Owner's words | Done means | Proof |
|---|---|---|
| "bot mode to have the list of profile" | Bot Mode's primary list is one row per `(connectionId, profile)` owner, with an identity-bearing key and a status indicator that reacts | `BotConversationKeyTest`; `BotModeScreenTest`; device pass on the profile list |
| "upon clicking it, it opens a sub-menu displaying all active conversations with that profile" | Tapping the row body opens a back-navigable in-Bot-Mode list of that profile's **open (= non-archived)** conversations, each with title, last activity and (where attributable) a state; Back returns to the list | `BotConversationsScreenTest` + screenshot; device load/empty/error; `archived="exclude"` default proven |
| "a side scrollable list of tabs … allows the user to switch between them" (placement: **top**, owner D4) | A horizontal `PrimaryScrollableTabRow` on top of the conversation lists that profile's conversations; the open conversation is selected; a tap swaps the transcript **in place** with no new route/ViewModel | `BotChatTabStripTest` + device proof of in-place switching and a detached turn keeping its badge |
| "Pending, done, etc." | Pending (NeedsInput) and running (Starting/Working) render truthfully; a detached running conversation keeps a running badge; **"done" is never faked** — it renders as recency ("last activity X ago") | `BotModeStatusPolicyTest` (no fabricated state); screenshot evidence; the disclosure line states that no light can mean *quiet* or *unattributable* |
| "conversations I have not deemed closed or archived" | `archived` is the only close field; the default view excludes archived rows; the row action archives against the route's own profile/connection and is reversible through the "Archived" view | `BotConversationsScreenTest`; device archive → re-list → restore |
| (safety, unstated by the owner) | A file staged in one conversation never follows the user into another | `ChatViewModelPendingAttachmentsTest` + manual main-chat regression |
| (compliance, unstated by the owner) | Every new string in canonical + 6 locale catalogs with refreshed `source_sha256`; all ADR/grep gates green; no version bumps; rendered behaviour signed off on a device by a human; the four deferrals recorded in `docs/project/TODO.md` | §6 checklist + §7 matrix |

