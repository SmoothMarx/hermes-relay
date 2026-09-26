# Always connect via Tailscale (Android) — implementation plan

**Status:** implemented and verified (see the Verification ladder section)
**Date:** 2026-09-26
**Branch:** `feat/android-tailscale-always-on` (base `e5989426`, the fork's `main`)
**Owner-visible outcome:** a new row in **Settings → "Always connect via Tailscale"** (directly under the
Gateways row) opening a subpage that owns the single switch, the status/blocked state, the remediation actions
and the "this app only" checklist. The connection's Routes tab shows a read-only status row linking to it.

## Goal
When the mode is ON for a connection, every Hermes-host byte the app sends goes over a Tailscale route and
the app refuses to connect when it cannot — it never silently falls back to a LAN or public route.

## Scope
- Android app (`app/`), engineering docs (`docs/`), user docs (`user-docs/`).
- Client-side only: no change to the pair/QR wire format (`plugin/relay/qr_sign.py` payload untouched).
- "Only this app": the app binds ONLY its own sockets. Routing ONLY this app through Tailscale is the
  Tailscale app's own per-app split-tunnelling (include mode) setting — a user action the Relay app guides
  and then verifies for its own traffic. That boundary is a platform limit, not an implementation choice.

## Approach (four slices, file-disjoint, one writer per file)
| Slice | Content | Where it is built |
|---|---|---|
| A | tailnet primitives + the single enforcement owner | `app/src/main/kotlin/com/hermesandroid/relay/network/shared/` |
| B | every transport binds; every silent-downgrade path is gated | existing connection/viewmodel/client files |
| C | Settings entry, subpage, status row, all 7 locale catalogs | `ui/`, `res/values*/strings.xml` |
| D | static gate, ADR 75, doc truth-fixes, test registration | `scripts/`, `docs/` |

## Frozen contracts
`FROZEN_API.md` (package/class/method freeze) and the design's C1–C9 contracts. Key invariants:
per-connection opt-in flag defaulting to false; enforcement owned by one class (`TailnetEnforcer`); every
Hermes-host transport built through `HermesClients`; tailnet-ness inferred from addresses only
(CGNAT 100.64.0.0/10, `fd7a:115c:a1e0::/48`, `*.ts.net`); forbidden APIs: `bindProcessToNetwork`,
`setUnderlyingNetworks`, any in-app `VpnService`; no new Gradle dependency.

## Verification ladder
1. JDK-free local gates: `python3 scripts/check-android-locales.py`, `check-android-collection-apis.py`,
   `check-android-capabilities.py`, `check-version-tracks.py`, the new `check-android-hermes-transports.py`
   (with its negative control) and `python3 -m unittest scripts.tests.*`.
2. Cloud compile + unit tests (no JDK exists on the build host):
   `gh workflow run 'Required checks' -R <fork> --ref main -f base_sha=<base> -f head_sha=<sha> -f android_preset=auto`
   which runs the full Android CI (lint + build + test + release-smoke). Every red is triaged against the same
   command at the base commit (`e5989426`), where the push lane is already red for an unrelated, pre-existing
   release-notes drift.
3. Installable artifact: `.github/workflows/candidate-sideload.yml` (workflow_dispatch) — green at base.
4. Three independent reviewer passes (spec-compliance, code-quality, compile-risk) from model families that
   did not write the design or the code.

## Honest limitations
- No JDK/Android SDK/Gradle on the implementation host, so Kotlin correctness is proven by cloud CI, not locally.
- Device-level behaviour (VPN callback delivery, split-tunnel bind errors, MagicDNS resolution) is unproven here
  and is flagged as UNPROVEN in the code's own KDoc.
- The pre-existing red in the push lane is left untouched (out of scope; proven pre-existing at the base commit).


---

## Slice A — foundation

Worktree: `<repo>` (branch `feat/android-tailscale-always-on`, base `e5989426`). Six tasks, strictly sequential
(A1 → A2/A3 → A4 → A5 → A6; A2 and A3 only need A1). Every task creates exactly one production file and its
unit test, and nothing else. Package for every file: `com.hermesandroid.relay.net.tailnet`.

## Slice A contracts consumed

- **File ownership (binding, FILE_OWNERSHIP.md row A):** only these twelve NEW files, under
  `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/` and `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/`.
  design_MERGED.md C2-C6 name `network/shared/`; the ownership table overrides the path (see open question 1).
  No existing file is edited by this slice. `scripts/android-prepush.py` (test registration) belongs to slice D.
- **MERGE C2 `TailnetAddresses`** — implemented verbatim: `MAGIC_DNS_SUFFIX`, `isTailnetHost(String?)`,
  `isTailnetAddress(InetAddress)`, `isTailnetUrl(String?)`; never does DNS. Added pure helper
  `ipLiteralOrNull(String?): InetAddress?` (hand-parsed literals, `InetAddress.getByAddress`, no resolution) —
  D1 C2's rule "call `InetAddress.getByName` only for `:` strings" is NOT used because on Android a non-literal
  containing `:` falls through to a DNS query.
- **MERGE C3 `TailnetRoutePolicy`** over the repo's real route type `com.hermesandroid.relay.data.EndpointCandidate`
  (`app/src/main/kotlin/com/hermesandroid/relay/data/Endpoint.kt:38-55`, T1:13): fields `role: String`,
  `experimental: Boolean`, `dashboard?.url`, `api?.url` (computed, `Endpoint.kt:71-73`), `relay?.url`, `proxy?.url`,
  `broker?.url`. `isEligible` / `filter` (order preserved; the route-lock entry point slice B calls from
  `ConnectionManager.resolveBestEndpointSafe`) / `hasEligibleRoute`. MERGE is silent on "no URLs at all" →
  D1 C3 rule 3 fills it: ineligible.
- **MERGE C4 `TailnetNetworkSource`** — `TailnetNetworkStatus.Absent` / `Present(network: android.net.Network,
  linkAddresses: List<InetAddress>)`; interface `status`, `start()`, `stop()`, `bindSocket(Socket)`, `lookup(String)`;
  production `AndroidTailnetNetworkSource(context)` with `registerNetworkCallback(TRANSPORT_VPN, −NOT_VPN)`,
  address-based selection, never identifying the VPN owner. D1 C5 detail kept where MERGE is silent:
  `TailnetUnavailableException : UnknownHostException`, stale-handle check before `bindSocket`, registration
  failure = stay `Absent`, no `NET_CAPABILITY_INTERNET` requirement.
- **D1 C4 `TailnetNetworkClassifier`** (brief item 2) reduced by MERGE refutation A1: address inference only, no
  interface name (`tunN` on Android), no Strong/Weak strength; DNS `100.100.100.100` is only a tie-break.
- **MERGE C5 `TailnetEnforcer`** — `private constructor(source)` (plus a private scope parameter),
  `initialize(context)`, `get()`, `setPolicy(activeConnectionId, enabled)`, `isEnforcing()`, `checkUrl(url)`,
  `socketFactory()`, `dns(fallback = Dns.SYSTEM)`, `addressGuard()`; block reasons exactly
  `PolicyNoEligibleRoute, TailnetNetworkAbsent, HostNotOnTailnet, HostUnreachable`. D1 C6 detail where MERGE is
  silent: `enforcing` / `generation` / `networkStatus` flows, `register(client)` + idle-pool eviction on every
  generation bump, dynamic per-call decisions, connected `createSocket` overloads.
- **MERGE C6 + D1 C7 + D1 §1.4** — `fun OkHttpClient.Builder.enforceTailnetPolicy(enforcer = TailnetEnforcer.get(),
  dnsFallback = Dns.SYSTEM): OkHttpClient.Builder` as a top-level extension in `HermesClients.kt` (brief: same file),
  idempotent via the guard marker; `HermesClients.build(builder, enforcer, dnsFallback)`.
- **RESOLVED_FACTS 2:** `socketFactory(SocketFactory)`, `dns(Dns)`, `addNetworkInterceptor(Interceptor)`,
  `Dns.lookup(hostname: String): List<InetAddress>` throwing `UnknownHostException` — OkHttp 5.5.0.
- **Test conventions actually used by the repo** (`app/build.gradle.kts:434-444`, e.g.
  `app/src/test/kotlin/com/hermesandroid/relay/network/shared/ReconnectBackoffTest.kt`): JUnit 4
  (`org.junit.Test`, `org.junit.Assert.*`, `assertThrows`), backtick test names, MockK (`io.mockk.mockk`),
  `okhttp3.mockwebserver.MockWebServer`/`MockResponse().setBody`, kotlinx-coroutines; plain JVM tests (no
  Robolectric; `unitTests.isReturnDefaultValues = true`, `app/build.gradle.kts:237-238`). Only stable coroutine
  APIs (`Dispatchers.Unconfined`), no `kotlinx.coroutines.test` experimental dispatchers.
- **Constraints honoured:** no new dependency (`gradle/libs.versions.toml` and `app/build.gradle.kts` untouched —
  checked by every task's Verify 7), Kotlin only, explicit imports, no reflection, no experimental APIs, no
  `VpnService`, no process-wide network binding (no `bindProcessToNetwork`), no QR/pairing wire change.

**Public surface this slice exports (slices B/C/D code against exactly this; all in package
`com.hermesandroid.relay.net.tailnet`):**

```kotlin
object TailnetAddresses {
    const val MAGIC_DNS_SUFFIX = ".ts.net"
    fun isTailnetHost(host: String?): Boolean
    fun isTailnetAddress(address: java.net.InetAddress): Boolean
    fun isTailnetUrl(url: String?): Boolean
    fun ipLiteralOrNull(host: String?): java.net.InetAddress?
}
object TailnetRoutePolicy {
    fun isEligible(candidate: EndpointCandidate): Boolean
    fun filter(candidates: List<EndpointCandidate>): List<EndpointCandidate>
    fun hasEligibleRoute(candidates: List<EndpointCandidate>): Boolean
}
data class NetworkSnapshot(val handle: Long, val isVpn: Boolean, val linkAddresses: List<InetAddress>, val dnsServers: List<InetAddress>, val firstSeenElapsedMs: Long)
object TailnetNetworkClassifier { fun isTailnet(snapshot: NetworkSnapshot): Boolean; fun pick(snapshots: List<NetworkSnapshot>): NetworkSnapshot? }
sealed interface TailnetNetworkStatus { data object Absent; data class Present(val network: android.net.Network, val linkAddresses: List<InetAddress>) }
enum class TailnetBlockReason { PolicyNoEligibleRoute, TailnetNetworkAbsent, HostNotOnTailnet, HostUnreachable }
class TailnetUnavailableException(val reason: TailnetBlockReason, message: String) : java.net.UnknownHostException(message)
interface TailnetNetworkSource { val status: StateFlow<TailnetNetworkStatus>; fun start(); fun stop(); fun bindSocket(socket: java.net.Socket); fun lookup(host: String): List<InetAddress> }
class AndroidTailnetNetworkSource(context: Context, elapsed: () -> Long = SystemClock::elapsedRealtime) : TailnetNetworkSource
class TailnetEnforcer private constructor(...) {
    companion object { fun initialize(context: Context); fun get(): TailnetEnforcer }
    val enforcing: StateFlow<Boolean>
    val generation: StateFlow<Long>
    val networkStatus: StateFlow<TailnetNetworkStatus>
    val activeConnectionId: String?
    fun setPolicy(activeConnectionId: String?, enabled: Boolean)
    fun isEnforcing(): Boolean
    fun checkUrl(url: String?): TailnetBlockReason?   // null = allowed
    fun socketFactory(): javax.net.SocketFactory
    fun dns(fallback: okhttp3.Dns = okhttp3.Dns.SYSTEM): okhttp3.Dns
    fun addressGuard(): okhttp3.Interceptor
    fun register(client: okhttp3.OkHttpClient): okhttp3.OkHttpClient
}
fun okhttp3.OkHttpClient.Builder.enforceTailnetPolicy(enforcer: TailnetEnforcer = TailnetEnforcer.get(), dnsFallback: okhttp3.Dns = okhttp3.Dns.SYSTEM): okhttp3.OkHttpClient.Builder
object HermesClients { fun build(builder: OkHttpClient.Builder = OkHttpClient.Builder(), enforcer: TailnetEnforcer = TailnetEnforcer.get(), dnsFallback: Dns = Dns.SYSTEM): OkHttpClient }
```

Import lines for callers: `import com.hermesandroid.relay.net.tailnet.TailnetEnforcer`,
`import com.hermesandroid.relay.net.tailnet.TailnetRoutePolicy`, `import com.hermesandroid.relay.net.tailnet.TailnetBlockReason`,
`import com.hermesandroid.relay.net.tailnet.TailnetAddresses`, `import com.hermesandroid.relay.net.tailnet.HermesClients`,
`import com.hermesandroid.relay.net.tailnet.enforceTailnetPolicy`, `import com.hermesandroid.relay.net.tailnet.TailnetNetworkStatus`,
`import com.hermesandroid.relay.net.tailnet.TailnetUnavailableException`.

Behaviour guarantees for downstream slices:
- Policy OFF (default, and in any process where `setPolicy` is never called): every decoration is a pass-through —
  plain `Socket()`, `dnsFallback`, guard only calls `proceed`. Zero behaviour change for existing users.
- Policy ON: each new socket is bound to the tailnet network or refused (`TailnetNetworkAbsent`) before any I/O;
  names resolve only on the tailnet and only to tailnet addresses (`HostNotOnTailnet` otherwise, the fallback DNS is
  never consulted); every request's connected peer must be a tailnet address (covers IP literals, proxies and stale
  pooled connections). Decided per socket / lookup / request, so a client built while OFF enforces the moment the
  policy turns ON — clients never need rebuilding.
- Before `TailnetEnforcer.initialize(context)` runs, `get()` already returns the process enforcer; its source is
  permanently absent, so enforcing before initialization fails closed instead of leaking.

### Tasks

#### Task A1: Add the pure tailnet address predicates (`TailnetAddresses`)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt`, `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt`
- **Depends on:** none
- **Estimate:** M (153 + 118 lines, new files)
- **Create / modify:** two NEW files. The file content is EXACTLY the fenced block content followed by one trailing newline. Do not reformat, reorder imports, or add/remove lines.

  `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Pure tailnet address predicates for "Always connect via Tailscale" (ADR 75).
 *
 * A tailnet address is inferred ONLY from the address itself:
 *  - a MagicDNS name ending in [MAGIC_DNS_SUFFIX] with a non-empty label before it,
 *  - an IPv4 literal in the CGNAT range 100.64.0.0/10,
 *  - an IPv6 literal in the Tailscale range fd7a:115c:a1e0::/48.
 *
 * Nothing in this object performs a DNS lookup. IP literals are parsed by hand and turned
 * into [InetAddress] objects with [InetAddress.getByAddress], which never resolves names.
 */
object TailnetAddresses {
    const val MAGIC_DNS_SUFFIX = ".ts.net"

    private val TAILNET_IPV6_PREFIX = intArrayOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)

    /** True iff [host] is a MagicDNS name or a tailnet IP literal. Never resolves [host]. */
    fun isTailnetHost(host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (normalized.endsWith(MAGIC_DNS_SUFFIX)) {
            val label = normalized.removeSuffix(MAGIC_DNS_SUFFIX)
            return label.isNotEmpty() && !label.startsWith(".") && !label.endsWith(".")
        }
        val literal = ipLiteralOrNull(normalized) ?: return false
        return isTailnetAddress(literal)
    }

    /** True iff [address] is in 100.64.0.0/10 (IPv4) or fd7a:115c:a1e0::/48 (IPv6). */
    fun isTailnetAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isTailnetIpv4(address.address)
        is Inet6Address -> isTailnetIpv6(address.address)
        else -> false
    }

    /**
     * True iff [url] parses and its host [isTailnetHost]. `ws://` and `wss://` are mapped to
     * `http://` and `https://` before parsing, the same mapping `Endpoint.kt` uses.
     * Any parse failure, a missing host, null or blank input returns false.
     */
    fun isTailnetUrl(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val httpUrl = when {
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            else -> trimmed
        }
        val host = runCatching { URI(httpUrl).host }.getOrNull() ?: return false
        return isTailnetHost(host)
    }

    /**
     * Returns the [InetAddress] for an IPv4 or IPv6 literal (brackets, one trailing dot and an
     * IPv6 zone suffix are tolerated), or null when [host] is not an IP literal.
     * Never performs a DNS lookup.
     */
    fun ipLiteralOrNull(host: String?): InetAddress? {
        val normalized = normalizeHost(host) ?: return null
        val bytes = parseIpv4(normalized)
            ?: if (normalized.contains(':')) parseIpv6(normalized) else null
        return bytes?.let { InetAddress.getByAddress(it) }
    }

    private fun normalizeHost(host: String?): String? {
        var value = host?.trim() ?: return null
        if (value.startsWith("[") && value.endsWith("]") && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        if (value.endsWith(".")) value = value.dropLast(1)
        value = value.lowercase()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun isTailnetIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }

    private fun isTailnetIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        return TAILNET_IPV6_PREFIX.indices.all { index ->
            bytes[index].toInt() and 0xff == TAILNET_IPV6_PREFIX[index]
        }
    }

    internal fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (index in 0 until 4) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            out[index] = value.toByte()
        }
        return out
    }

    internal fun parseIpv6(text: String): ByteArray? {
        val literal = text.substringBefore('%')
        if (literal.isEmpty()) return null
        val doubleColon = literal.indexOf("::")
        if (doubleColon >= 0 && literal.indexOf("::", doubleColon + 1) >= 0) return null
        val groups: List<Int> = if (doubleColon < 0) {
            val all = parseIpv6Groups(literal, allowTrailingIpv4 = true) ?: return null
            if (all.size != 8) return null
            all
        } else {
            val head = parseIpv6Groups(literal.substring(0, doubleColon), allowTrailingIpv4 = false)
                ?: return null
            val tail = parseIpv6Groups(literal.substring(doubleColon + 2), allowTrailingIpv4 = true)
                ?: return null
            val missing = 8 - head.size - tail.size
            if (missing < 1) return null
            head + List(missing) { 0 } + tail
        }
        val out = ByteArray(16)
        groups.forEachIndexed { index, group ->
            out[2 * index] = (group shr 8).toByte()
            out[2 * index + 1] = (group and 0xff).toByte()
        }
        return out
    }

    private fun parseIpv6Groups(text: String, allowTrailingIpv4: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val pieces = text.split(':')
        val groups = ArrayList<Int>(8)
        for (index in pieces.indices) {
            val piece = pieces[index]
            if (allowTrailingIpv4 && index == pieces.lastIndex && piece.contains('.')) {
                val v4 = parseIpv4(piece) ?: return null
                groups.add(((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff))
                groups.add(((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff))
            } else {
                if (piece.isEmpty() || piece.length > 4) return null
                if (!piece.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                groups.add(piece.toInt(16))
            }
        }
        return groups
    }
}
```

  `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetAddressesTest {

    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    @Test
    fun `magic dns names are tailnet hosts`() {
        assertTrue(TailnetAddresses.isTailnetHost("host.tail0000.ts.net"))
        assertTrue(TailnetAddresses.isTailnetHost("HOST.TAIL0000.TS.NET."))
        assertTrue(TailnetAddresses.isTailnetHost("  host.example.ts.net  "))
    }

    @Test
    fun `suffix alone and lookalike names are not tailnet hosts`() {
        assertFalse(TailnetAddresses.isTailnetHost("ts.net"))
        assertFalse(TailnetAddresses.isTailnetHost(".ts.net"))
        assertFalse(TailnetAddresses.isTailnetHost("hostts.net"))
        assertFalse(TailnetAddresses.isTailnetHost("host.ts.net.example.com"))
        assertFalse(TailnetAddresses.isTailnetHost("hermes.example.com"))
        assertFalse(TailnetAddresses.isTailnetHost(""))
        assertFalse(TailnetAddresses.isTailnetHost("   "))
        assertFalse(TailnetAddresses.isTailnetHost(null))
    }

    @Test
    fun `cgnat range boundaries`() {
        assertTrue(TailnetAddresses.isTailnetHost("100.64.0.0"))
        assertTrue(TailnetAddresses.isTailnetHost("100.64.0.1"))
        assertTrue(TailnetAddresses.isTailnetHost("100.127.255.255"))
        assertFalse(TailnetAddresses.isTailnetHost("100.63.255.255"))
        assertFalse(TailnetAddresses.isTailnetHost("100.128.0.0"))
        assertFalse(TailnetAddresses.isTailnetHost("10.0.0.20"))
        assertFalse(TailnetAddresses.isTailnetHost("203.0.113.7"))
    }

    @Test
    fun `malformed ipv4 literals are rejected`() {
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0.1.5"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0.256"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.-1.1"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0.1a"))
        assertFalse(TailnetAddresses.isTailnetHost("100.64.0.0001"))
    }

    @Test
    fun `tailscale ipv6 range`() {
        assertTrue(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0::1"))
        assertTrue(TailnetAddresses.isTailnetHost("[fd7a:115c:a1e0::1]"))
        assertTrue(TailnetAddresses.isTailnetHost("FD7A:115C:A1E0:AB12:4843:CD96:6258:B240"))
        assertTrue(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0::1%tun0"))
        assertTrue(TailnetAddresses.isTailnetHost("::ffff:100.64.0.1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e1::1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c::1"))
        assertFalse(TailnetAddresses.isTailnetHost("::1"))
    }

    @Test
    fun `malformed ipv6 literals are rejected`() {
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0:::1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0::1::2"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0:1:2:3:4:5:6"))
        assertFalse(TailnetAddresses.isTailnetHost("gd7a:115c:a1e0::1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0:12345::1"))
        assertFalse(TailnetAddresses.isTailnetHost("fd7a:115c:a1e0:-1::1"))
    }

    @Test
    fun `tailnet address objects`() {
        assertTrue(TailnetAddresses.isTailnetAddress(v4(100, 64, 0, 1)))
        assertTrue(TailnetAddresses.isTailnetAddress(v4(100, 127, 255, 255)))
        assertFalse(TailnetAddresses.isTailnetAddress(v4(100, 128, 0, 0)))
        assertFalse(TailnetAddresses.isTailnetAddress(v4(10, 0, 0, 20)))
        val tailnetV6 = TailnetAddresses.ipLiteralOrNull("fd7a:115c:a1e0::5")
        assertTrue(tailnetV6 is Inet6Address)
        assertTrue(TailnetAddresses.isTailnetAddress(tailnetV6!!))
        val otherV6 = TailnetAddresses.ipLiteralOrNull("fd7a:115c:a1e1::5")
        assertFalse(TailnetAddresses.isTailnetAddress(otherV6!!))
    }

    @Test
    fun `tailnet urls`() {
        assertTrue(TailnetAddresses.isTailnetUrl("wss://host.tail0000.ts.net:10443/relay"))
        assertTrue(TailnetAddresses.isTailnetUrl("WSS://HOST.TAIL0000.TS.NET/"))
        assertTrue(TailnetAddresses.isTailnetUrl("ws://100.100.1.2:8767"))
        assertTrue(TailnetAddresses.isTailnetUrl("http://[fd7a:115c:a1e0::1]:9119/"))
        assertTrue(TailnetAddresses.isTailnetUrl("https://host.example.ts.net"))
        assertFalse(TailnetAddresses.isTailnetUrl("https://hermes.example.com"))
        assertFalse(TailnetAddresses.isTailnetUrl("http://10.0.0.20:8642"))
        assertFalse(TailnetAddresses.isTailnetUrl("https://host.ts.net.example.com/"))
        assertFalse(TailnetAddresses.isTailnetUrl("not a url"))
        assertFalse(TailnetAddresses.isTailnetUrl("100.64.0.1:8767"))
        assertFalse(TailnetAddresses.isTailnetUrl(""))
        assertFalse(TailnetAddresses.isTailnetUrl(null))
    }

    @Test
    fun `ip literal parsing never resolves names`() {
        val started = System.nanoTime()
        assertNull(TailnetAddresses.ipLiteralOrNull("nonexistent.invalid"))
        assertNull(TailnetAddresses.ipLiteralOrNull("host.tail0000.ts.net"))
        assertFalse(TailnetAddresses.isTailnetHost("nonexistent.invalid"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("predicates must not do DNS (took ${elapsedMs}ms)", elapsedMs < 1_000)
        assertTrue(TailnetAddresses.ipLiteralOrNull("100.64.0.1") is Inet4Address)
        assertTrue(TailnetAddresses.ipLiteralOrNull("[fd7a:115c:a1e0::1]") is Inet6Address)
    }
}
```
- **Steps:**
  1. `cd <repo>` and confirm the branch: `git rev-parse --abbrev-ref HEAD` → `feat/android-tailscale-always-on`.
  2. Symbol check (must print nothing: the package is new): `grep -rn 'package com.hermesandroid.relay.net.tailnet' app/src`.
  3. Create `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt` with the first block verbatim (the write tool creates missing directories).
  4. Create `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt` with the second block verbatim.
  5. Run the Verify commands 1-8 below and keep their real output for the report.
  6. `git add app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt`
  7. `git commit -m "feat(android): add tailnet address predicates"`
- **Verify:** (run each command from the worktree; expected output after the arrow)
  1. `cd <repo> && test -f app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt && test -f app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt && echo present` → `present`
  2. `cd <repo> && sha256sum app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt` → `9f7498785ad2cff2dbdb6919ec28a54ce834decca67cfd4f801b7f1e2aeb56ca  app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt` and `a61474580215487b76f9c5db38c35d09c76eb144f6f2847c49d71b186706d267  app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt` (if a hash differs, re-copy the block exactly; a difference caused ONLY by the final newline is acceptable and must be reported under Deviations)
  3. `cd <repo> && python3 -c "import pathlib,sys; [print(p, (t:=pathlib.Path(p).read_text()).count('{')-t.count('}'), t.count('(')-t.count(')')) for p in sys.argv[1:]]" app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt` → both lines end with `0 0`
  4. `cd <repo> && grep -c '^package com.hermesandroid.relay.net.tailnet$' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt` → `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt:1` and `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt:1`
  5. `cd <repo> && python3 scripts/check-android-collection-apis.py` → `Android collection API compatibility check passed (Kotlin sources)` (exit 0)
  6. `cd <repo> && grep -rnE 'bindProcessToNetwork|setProcessDefaultNetwork|VpnService|setUnderlyingNetworks' app/src/main; echo rc=$?` → no match lines, then `rc=1`
  7. `cd <repo> && git diff --stat e5989426 -- gradle/libs.versions.toml app/build.gradle.kts` → empty output (no dependency change)
  8. `cd <repo> && git status --short` → exactly `?? app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt` and `?? app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt` before the commit (the first task shows the directory form `?? app/src/main/kotlin/com/hermesandroid/relay/net/` / `?? app/src/test/kotlin/com/hermesandroid/relay/net/` instead — also acceptable); after the commit: `git show --stat --format=%s HEAD` lists exactly these 2 files and the subject below.
  9. NOT verifiable on this host (no JDK): compilation and the unit tests in `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddressesTest.kt`. They run in cloud CI once slice D registers the test class in `FOCUSED_TESTS`; report them under "what you could NOT verify".
- **Commit:** `feat(android): add tailnet address predicates`

#### Task A2: Add the route-lock predicate (`TailnetRoutePolicy`) and `TailnetRoutePolicyTest`
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt`, `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt`
- **Depends on:** Task A1
- **Estimate:** S (44 + 162 lines, new files)
- **Create / modify:** two NEW files. The file content is EXACTLY the fenced block content followed by one trailing newline. Do not reformat, reorder imports, or add/remove lines.

  `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import com.hermesandroid.relay.data.EndpointCandidate

/**
 * Route-lock predicate for "Always connect via Tailscale" (ADR 75). Pure, no I/O.
 *
 * A candidate is eligible iff ALL hold:
 *  1. its role is not `public` (case-insensitive): a public route is never used because
 *     Tailscale is on, and pairing emits Funnel `.ts.net` routes as `public`;
 *  2. it is not `experimental` (Hermes Reach rides a public broker);
 *  3. it has at least one non-blank surface URL, and EVERY non-blank URL among
 *     dashboard, api, relay, proxy and broker is a tailnet URL. A candidate that mixes a
 *     tailnet surface with a non-tailnet surface is ineligible as a whole.
 *
 * The role string is only compared, never rewritten, and no role is required: a `lan` route
 * pointing at 100.x is eligible, a `tailscale` route pointing at a LAN address is not.
 * The resolver is not changed; [filter] keeps the ADR 24 order of what remains.
 */
object TailnetRoutePolicy {

    fun isEligible(candidate: EndpointCandidate): Boolean {
        if (candidate.role.equals("public", ignoreCase = true)) return false
        if (candidate.experimental) return false
        val urls = surfaceUrls(candidate)
        return urls.isNotEmpty() && urls.all { TailnetAddresses.isTailnetUrl(it) }
    }

    /** The eligible subset of [candidates], order preserved. This is the route-lock entry point. */
    fun filter(candidates: List<EndpointCandidate>): List<EndpointCandidate> =
        candidates.filter { isEligible(it) }

    fun hasEligibleRoute(candidates: List<EndpointCandidate>): Boolean =
        candidates.any { isEligible(it) }

    internal fun surfaceUrls(candidate: EndpointCandidate): List<String> =
        listOfNotNull(
            candidate.dashboard?.url,
            candidate.api?.url,
            candidate.relay?.url,
            candidate.proxy?.url,
            candidate.broker?.url,
        ).map { it.trim() }.filter { it.isNotEmpty() }
}
```

  `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.BrokerEndpoint
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.ProxyEndpoint
import com.hermesandroid.relay.data.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetRoutePolicyTest {

    private fun candidate(
        role: String,
        priority: Int = 0,
        api: ApiEndpoint? = null,
        relay: RelayEndpoint? = null,
        dashboard: DashboardEndpoint? = null,
        proxy: ProxyEndpoint? = null,
        broker: BrokerEndpoint? = null,
        experimental: Boolean = false,
    ): EndpointCandidate = EndpointCandidate(
        role = role,
        priority = priority,
        api = api,
        relay = relay,
        dashboard = dashboard,
        proxy = proxy,
        broker = broker,
        experimental = experimental,
    )

    private val tailnetDashboard = DashboardEndpoint("https://host.tail0000.ts.net")
    private val tailnetRelay = RelayEndpoint("wss://host.tail0000.ts.net:10443/relay")
    private val lanRelay = RelayEndpoint("ws://10.0.0.20:8767")

    @Test
    fun `tailscale route with only tailnet urls is eligible`() {
        assertTrue(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", dashboard = tailnetDashboard, relay = tailnetRelay),
            ),
        )
    }

    @Test
    fun `lan role pointing at a cgnat address is eligible`() {
        assertTrue(
            TailnetRoutePolicy.isEligible(
                candidate(
                    "lan",
                    api = ApiEndpoint(host = "100.64.0.7", port = 8642),
                    relay = RelayEndpoint("ws://100.64.0.7:8767"),
                ),
            ),
        )
    }

    @Test
    fun `bracketed tailscale ipv6 api host is eligible`() {
        assertTrue(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", api = ApiEndpoint(host = "[fd7a:115c:a1e0::7]", port = 8642)),
            ),
        )
    }

    @Test
    fun `tailscale role pointing at a lan address is not eligible`() {
        assertFalse(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", api = ApiEndpoint(host = "10.0.0.20", port = 8642)),
            ),
        )
    }

    @Test
    fun `public funnel route is never eligible`() {
        assertFalse(TailnetRoutePolicy.isEligible(candidate("public", dashboard = tailnetDashboard)))
        assertFalse(TailnetRoutePolicy.isEligible(candidate("PUBLIC", dashboard = tailnetDashboard)))
    }

    @Test
    fun `mixed tailnet and lan surfaces are not eligible`() {
        assertFalse(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", dashboard = tailnetDashboard, relay = lanRelay),
            ),
        )
    }

    @Test
    fun `experimental route is not eligible`() {
        assertFalse(
            TailnetRoutePolicy.isEligible(
                candidate("tailscale", dashboard = tailnetDashboard, experimental = true),
            ),
        )
    }

    @Test
    fun `broker on a public host is not eligible`() {
        val broker = BrokerEndpoint(
            url = "wss://broker.example.com/v1/connect",
            hostId = "AAAAAAAAAAAAAAAAAAAAAA",
            credentialKind = "route",
            token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )
        assertFalse(
            TailnetRoutePolicy.isEligible(
                candidate(
                    "outbound_broker",
                    proxy = ProxyEndpoint(url = "https://host.tail0000.ts.net:8443"),
                    broker = broker,
                ),
            ),
        )
    }

    @Test
    fun `route without any url is not eligible`() {
        assertFalse(TailnetRoutePolicy.isEligible(candidate("tailscale")))
    }

    @Test
    fun `blank urls are ignored`() {
        assertTrue(
            TailnetRoutePolicy.isEligible(
                candidate(
                    "tailscale",
                    dashboard = DashboardEndpoint("   "),
                    relay = tailnetRelay,
                ),
            ),
        )
    }

    @Test
    fun `filter keeps only eligible routes in their original order`() {
        val first = candidate("tailscale", priority = 0, dashboard = tailnetDashboard)
        val lan = candidate("lan", priority = 0, relay = lanRelay)
        val second = candidate("lan", priority = 1, relay = RelayEndpoint("ws://100.64.0.9:8767"))
        val public = candidate("public", priority = 2, dashboard = DashboardEndpoint("https://hermes.example.com"))
        val third = candidate("tailscale", priority = 0, relay = tailnetRelay)
        assertEquals(
            listOf(first, second, third),
            TailnetRoutePolicy.filter(listOf(first, lan, second, public, third)),
        )
    }

    @Test
    fun `has eligible route`() {
        val lan = candidate("lan", relay = lanRelay)
        val tailnet = candidate("tailscale", dashboard = tailnetDashboard)
        assertTrue(TailnetRoutePolicy.hasEligibleRoute(listOf(lan, tailnet)))
        assertFalse(TailnetRoutePolicy.hasEligibleRoute(listOf(lan)))
        assertFalse(TailnetRoutePolicy.hasEligibleRoute(emptyList()))
    }
}
```
- **Steps:**
  1. `cd <repo>` and confirm the branch: `git rev-parse --abbrev-ref HEAD` → `feat/android-tailscale-always-on`.
  2. Symbol check — the route type and fields this task uses must exist exactly: `grep -n 'data class EndpointCandidate\|val experimental\|val broker: BrokerEndpoint\|val proxy: ProxyEndpoint\|val dashboard: DashboardEndpoint\|val relay: RelayEndpoint\|val api: ApiEndpoint\|val url: String' app/src/main/kotlin/com/hermesandroid/relay/data/Endpoint.kt` → hits for every name (EndpointCandidate at line 39; `ApiEndpoint.url` is the computed property at lines 71-73). If any is missing, STOP and report (do not redesign).
  3. Create `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt` with the first block verbatim (the write tool creates missing directories).
  4. Create `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt` with the second block verbatim.
  5. Run the Verify commands 1-8 below and keep their real output for the report.
  6. `git add app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt`
  7. `git commit -m "feat(android): add tailnet route eligibility policy"`
- **Verify:** (run each command from the worktree; expected output after the arrow)
  1. `cd <repo> && test -f app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt && test -f app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt && echo present` → `present`
  2. `cd <repo> && sha256sum app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt` → `cc6cd5af91786b1e0089db6afe1cf0a8864d817400104761efb77d2f2a93727b  app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt` and `10afb5fd504bb12fb21214759d31da502440d19edddafb365ad97ffef4a239d2  app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt` (if a hash differs, re-copy the block exactly; a difference caused ONLY by the final newline is acceptable and must be reported under Deviations)
  3. `cd <repo> && python3 -c "import pathlib,sys; [print(p, (t:=pathlib.Path(p).read_text()).count('{')-t.count('}'), t.count('(')-t.count(')')) for p in sys.argv[1:]]" app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt` → both lines end with `0 0`
  4. `cd <repo> && grep -c '^package com.hermesandroid.relay.net.tailnet$' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt` → `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt:1` and `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt:1`
  5. `cd <repo> && python3 scripts/check-android-collection-apis.py` → `Android collection API compatibility check passed (Kotlin sources)` (exit 0)
  6. `cd <repo> && grep -rnE 'bindProcessToNetwork|setProcessDefaultNetwork|VpnService|setUnderlyingNetworks' app/src/main; echo rc=$?` → no match lines, then `rc=1`
  7. `cd <repo> && git diff --stat e5989426 -- gradle/libs.versions.toml app/build.gradle.kts` → empty output (no dependency change)
  8. `cd <repo> && git status --short` → exactly `?? app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt` and `?? app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt` before the commit (the first task shows the directory form `?? app/src/main/kotlin/com/hermesandroid/relay/net/` / `?? app/src/test/kotlin/com/hermesandroid/relay/net/` instead — also acceptable); after the commit: `git show --stat --format=%s HEAD` lists exactly these 2 files and the subject below.
  9. NOT verifiable on this host (no JDK): compilation and the unit tests in `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicyTest.kt`. They run in cloud CI once slice D registers the test class in `FOCUSED_TESTS`; report them under "what you could NOT verify".
- **Commit:** `feat(android): add tailnet route eligibility policy`

#### Task A3: Add the address-only VPN classifier (`TailnetNetworkClassifier`)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt`, `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt`
- **Depends on:** Task A1
- **Estimate:** S (69 + 87 lines, new files)
- **Create / modify:** two NEW files. The file content is EXACTLY the fenced block content followed by one trailing newline. Do not reformat, reorder imports, or add/remove lines.

  `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Plain-data view of one Android network, built by [AndroidTailnetNetworkSource] from the
 * network's `NetworkCapabilities` and `LinkProperties`.
 *
 * The interface name is deliberately NOT part of this shape: on Android the VPN interface is
 * a generic `tunN`, so a name match cannot identify the tailnet. Identification uses addresses.
 */
data class NetworkSnapshot(
    /** `Network.getNetworkHandle()`. */
    val handle: Long,
    /** `NetworkCapabilities.hasTransport(TRANSPORT_VPN)`. */
    val isVpn: Boolean,
    /** `LinkProperties.getLinkAddresses()` mapped to their addresses. */
    val linkAddresses: List<InetAddress>,
    /** `LinkProperties.getDnsServers()`. */
    val dnsServers: List<InetAddress>,
    /** Elapsed-realtime millis when the network was first seen by the source. */
    val firstSeenElapsedMs: Long,
)

/**
 * Pure classification of which network, if any, carries the tailnet (ADR 75).
 *
 * A network is the tailnet iff it is a VPN-transport network AND at least one of its link
 * addresses is in a tailnet range (100.64.0.0/10 or fd7a:115c:a1e0::/48). This infers a
 * "tailnet-style VPN"; it cannot prove the VPN app is Tailscale (the platform hides the VPN
 * owner from third-party apps).
 */
object TailnetNetworkClassifier {

    fun isTailnet(snapshot: NetworkSnapshot): Boolean =
        snapshot.isVpn && snapshot.linkAddresses.any { TailnetAddresses.isTailnetAddress(it) }

    /**
     * Among the tailnet networks in [snapshots], picks one; null when none qualifies.
     * Tie-break order (only between networks that already qualify):
     *  1. has a link address in fd7a:115c:a1e0::/48,
     *  2. lists the MagicDNS resolver 100.100.100.100 as a DNS server,
     *  3. most recently first seen,
     *  4. highest handle.
     */
    fun pick(snapshots: List<NetworkSnapshot>): NetworkSnapshot? =
        snapshots.filter { isTailnet(it) }.maxWithOrNull(pickOrder)

    private val pickOrder: Comparator<NetworkSnapshot> = Comparator { left, right ->
        compareValuesBy(
            left,
            right,
            { snapshot -> hasTailnetIpv6(snapshot) },
            { snapshot -> hasMagicDnsResolver(snapshot) },
            { snapshot -> snapshot.firstSeenElapsedMs },
            { snapshot -> snapshot.handle },
        )
    }

    private fun hasTailnetIpv6(snapshot: NetworkSnapshot): Boolean =
        snapshot.linkAddresses.any { it is Inet6Address && TailnetAddresses.isTailnetAddress(it) }

    private fun hasMagicDnsResolver(snapshot: NetworkSnapshot): Boolean =
        snapshot.dnsServers.any { address ->
            address is Inet4Address && address.address.all { it.toInt() and 0xff == 100 }
        }
}
```

  `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetNetworkClassifierTest {

    private fun ip(literal: String): InetAddress = TailnetAddresses.ipLiteralOrNull(literal)!!

    private fun snapshot(
        handle: Long,
        isVpn: Boolean = true,
        linkAddresses: List<String> = emptyList(),
        dnsServers: List<String> = emptyList(),
        firstSeenElapsedMs: Long = 0L,
    ): NetworkSnapshot = NetworkSnapshot(
        handle = handle,
        isVpn = isVpn,
        linkAddresses = linkAddresses.map(::ip),
        dnsServers = dnsServers.map(::ip),
        firstSeenElapsedMs = firstSeenElapsedMs,
    )

    @Test
    fun `non vpn network is never the tailnet`() {
        assertFalse(TailnetNetworkClassifier.isTailnet(snapshot(1, isVpn = false, linkAddresses = listOf("100.64.0.5"))))
    }

    @Test
    fun `vpn without tailnet addresses is not the tailnet`() {
        assertFalse(TailnetNetworkClassifier.isTailnet(snapshot(1, linkAddresses = listOf("10.8.0.2"))))
        assertFalse(TailnetNetworkClassifier.isTailnet(snapshot(1, dnsServers = listOf("100.100.100.100"))))
    }

    @Test
    fun `vpn with a cgnat or tailscale ipv6 link address is the tailnet`() {
        assertTrue(TailnetNetworkClassifier.isTailnet(snapshot(1, linkAddresses = listOf("100.64.0.5"))))
        assertTrue(TailnetNetworkClassifier.isTailnet(snapshot(1, linkAddresses = listOf("fd7a:115c:a1e0::5"))))
        assertTrue(TailnetNetworkClassifier.isTailnet(snapshot(1, linkAddresses = listOf("10.8.0.2", "100.64.0.5"))))
    }

    @Test
    fun `pick returns null when nothing qualifies`() {
        assertNull(TailnetNetworkClassifier.pick(emptyList()))
        assertNull(
            TailnetNetworkClassifier.pick(
                listOf(
                    snapshot(1, isVpn = false, linkAddresses = listOf("100.64.0.5")),
                    snapshot(2, linkAddresses = listOf("10.8.0.2")),
                ),
            ),
        )
    }

    @Test
    fun `tailscale ipv6 beats ipv4 only`() {
        val ipv4Only = snapshot(1, linkAddresses = listOf("100.64.0.5"), firstSeenElapsedMs = 900L)
        val dualStack = snapshot(2, linkAddresses = listOf("100.64.0.6", "fd7a:115c:a1e0::6"), firstSeenElapsedMs = 100L)
        assertEquals(2L, TailnetNetworkClassifier.pick(listOf(ipv4Only, dualStack))?.handle)
    }

    @Test
    fun `magic dns resolver breaks a tie`() {
        val plain = snapshot(1, linkAddresses = listOf("100.64.0.5"), firstSeenElapsedMs = 900L)
        val magicDns = snapshot(
            2,
            linkAddresses = listOf("100.64.0.6"),
            dnsServers = listOf("100.100.100.100"),
            firstSeenElapsedMs = 100L,
        )
        assertEquals(2L, TailnetNetworkClassifier.pick(listOf(plain, magicDns))?.handle)
    }

    @Test
    fun `most recent then highest handle break remaining ties`() {
        val older = snapshot(7, linkAddresses = listOf("100.64.0.5"), firstSeenElapsedMs = 100L)
        val newer = snapshot(3, linkAddresses = listOf("100.64.0.6"), firstSeenElapsedMs = 200L)
        assertEquals(3L, TailnetNetworkClassifier.pick(listOf(older, newer))?.handle)
        val sameTimeLow = snapshot(4, linkAddresses = listOf("100.64.0.5"), firstSeenElapsedMs = 200L)
        val sameTimeHigh = snapshot(9, linkAddresses = listOf("100.64.0.6"), firstSeenElapsedMs = 200L)
        assertEquals(9L, TailnetNetworkClassifier.pick(listOf(sameTimeLow, sameTimeHigh))?.handle)
    }
}
```
- **Steps:**
  1. `cd <repo>` and confirm the branch: `git rev-parse --abbrev-ref HEAD` → `feat/android-tailscale-always-on`.
  2. Symbol check: `grep -n 'fun ipLiteralOrNull\|fun isTailnetAddress' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetAddresses.kt` → 2 hits (from Task A1).
  3. Create `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt` with the first block verbatim (the write tool creates missing directories).
  4. Create `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt` with the second block verbatim.
  5. Run the Verify commands 1-8 below and keep their real output for the report.
  6. `git add app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt`
  7. `git commit -m "feat(android): add address-based tailnet network classifier"`
- **Verify:** (run each command from the worktree; expected output after the arrow)
  1. `cd <repo> && test -f app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt && test -f app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt && echo present` → `present`
  2. `cd <repo> && sha256sum app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt` → `5596073dea552379ea0ae4b170a07c37dfd2bd925a1b834218d8096c1c0dfab2  app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt` and `80c6608efb9a3227c94c3bd03191c015158730a51fab70db3d36afac182c4c86  app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt` (if a hash differs, re-copy the block exactly; a difference caused ONLY by the final newline is acceptable and must be reported under Deviations)
  3. `cd <repo> && python3 -c "import pathlib,sys; [print(p, (t:=pathlib.Path(p).read_text()).count('{')-t.count('}'), t.count('(')-t.count(')')) for p in sys.argv[1:]]" app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt` → both lines end with `0 0`
  4. `cd <repo> && grep -c '^package com.hermesandroid.relay.net.tailnet$' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt` → `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt:1` and `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt:1`
  5. `cd <repo> && python3 scripts/check-android-collection-apis.py` → `Android collection API compatibility check passed (Kotlin sources)` (exit 0)
  6. `cd <repo> && grep -rnE 'bindProcessToNetwork|setProcessDefaultNetwork|VpnService|setUnderlyingNetworks' app/src/main; echo rc=$?` → no match lines, then `rc=1`
  7. `cd <repo> && git diff --stat e5989426 -- gradle/libs.versions.toml app/build.gradle.kts` → empty output (no dependency change)
  8. `cd <repo> && git status --short` → exactly `?? app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt` and `?? app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt` before the commit (the first task shows the directory form `?? app/src/main/kotlin/com/hermesandroid/relay/net/` / `?? app/src/test/kotlin/com/hermesandroid/relay/net/` instead — also acceptable); after the commit: `git show --stat --format=%s HEAD` lists exactly these 2 files and the subject below.
  9. NOT verifiable on this host (no JDK): compilation and the unit tests in `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifierTest.kt`. They run in cloud CI once slice D registers the test class in `FOCUSED_TESTS`; report them under "what you could NOT verify".
- **Commit:** `feat(android): add address-based tailnet network classifier`

#### Task A4: Add the platform isolation boundary (`TailnetNetworkSource`, block reasons, exception, Android source)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt`, `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt`
- **Depends on:** Task A3
- **Estimate:** L (334 + 187 lines, new files)
- **Note for the builder:** this file is the ONLY file in the app allowed to call `bindSocket` on an Android `Network` (slice D's gate allowlists exactly this path). The test file also defines `internal class FakeTailnetNetworkSource`, which Tasks A5 and A6 reuse; do not move it.
- **Create / modify:** two NEW files. The file content is EXACTLY the fenced block content followed by one trailing newline. Do not reformat, reorder imports, or add/remove lines.

  `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether a tailnet-style VPN network is currently visible to this app. */
sealed interface TailnetNetworkStatus {
    data object Absent : TailnetNetworkStatus

    data class Present(
        val network: Network,
        /** The network's link addresses that are in a tailnet range. */
        val linkAddresses: List<InetAddress>,
    ) : TailnetNetworkStatus
}

/**
 * Why a Hermes connection was refused while "Always connect via Tailscale" is on.
 * "Tailscale is off", "not signed in" and "Hermes Relay excluded by split tunneling" are
 * indistinguishable to an app, so they are all [TailnetNetworkAbsent].
 */
enum class TailnetBlockReason {
    PolicyNoEligibleRoute,
    TailnetNetworkAbsent,
    HostNotOnTailnet,
    HostUnreachable,
}

/**
 * Thrown by the tailnet socket factory, DNS and address guard. It is an [UnknownHostException]
 * and therefore an [IOException], which OkHttp accepts from `Dns`, `SocketFactory` and
 * interceptors alike.
 */
class TailnetUnavailableException(
    val reason: TailnetBlockReason,
    message: String,
) : UnknownHostException(message)

/**
 * THE isolation boundary for all platform behaviour of the tailnet enforcement (ADR 75).
 * Nothing outside this file inspects Android networks or binds sockets to a network.
 */
interface TailnetNetworkSource {
    val status: StateFlow<TailnetNetworkStatus>

    fun start()

    fun stop()

    /** Binds an UNCONNECTED [socket] to the tailnet network, or throws [TailnetUnavailableException]. */
    fun bindSocket(socket: Socket)

    /** Resolves [host] on the tailnet; returns ONLY tailnet addresses, or throws [TailnetUnavailableException]. */
    fun lookup(host: String): List<InetAddress>
}

/** Pure lookup rules shared by every [TailnetNetworkSource]. */
internal object TailnetLookupRules {

    /**
     * [resolver] resolves a name on the tailnet network; null means no tailnet network is present.
     *  - IP literal: returned iff it is a tailnet address, else [TailnetBlockReason.HostNotOnTailnet]
     *    (the resolver is never called for a literal).
     *  - Name with no network: [TailnetBlockReason.TailnetNetworkAbsent].
     *  - Name: resolver answers filtered to tailnet addresses; empty or failed lookup:
     *    [TailnetBlockReason.HostNotOnTailnet].
     */
    fun lookup(host: String, resolver: ((String) -> List<InetAddress>)?): List<InetAddress> {
        val literal = TailnetAddresses.ipLiteralOrNull(host)
        if (literal != null) {
            if (TailnetAddresses.isTailnetAddress(literal)) return listOf(literal)
            throw TailnetUnavailableException(
                TailnetBlockReason.HostNotOnTailnet,
                "Address is not on the tailnet",
            )
        }
        if (resolver == null) {
            throw TailnetUnavailableException(
                TailnetBlockReason.TailnetNetworkAbsent,
                "No tailnet network is available",
            )
        }
        val answers = try {
            resolver(host)
        } catch (e: UnknownHostException) {
            throw TailnetUnavailableException(
                TailnetBlockReason.HostNotOnTailnet,
                "Name did not resolve on the tailnet",
            ).apply { initCause(e) }
        } catch (e: SecurityException) {
            throw TailnetUnavailableException(
                TailnetBlockReason.TailnetNetworkAbsent,
                "The tailnet network refused the lookup",
            ).apply { initCause(e) }
        }
        val tailnet = answers.filter { TailnetAddresses.isTailnetAddress(it) }
        if (tailnet.isEmpty()) {
            throw TailnetUnavailableException(
                TailnetBlockReason.HostNotOnTailnet,
                "Name did not resolve to a tailnet address",
            )
        }
        return tailnet
    }
}

/**
 * The process source handed to the enforcer before `TailnetEnforcer.initialize` has run.
 * Until [attach] it is permanently absent (fail closed); afterwards it delegates to the
 * attached source and mirrors its [status].
 */
internal class DeferredTailnetNetworkSource(
    private val scope: CoroutineScope,
) : TailnetNetworkSource {
    private val mutableStatus = MutableStateFlow<TailnetNetworkStatus>(TailnetNetworkStatus.Absent)
    override val status: StateFlow<TailnetNetworkStatus> = mutableStatus.asStateFlow()

    @Volatile
    private var delegate: TailnetNetworkSource? = null

    val isAttached: Boolean
        get() = delegate != null

    @Synchronized
    fun attach(source: TailnetNetworkSource) {
        if (delegate != null) return
        delegate = source
        scope.launch {
            source.status.collect { mutableStatus.value = it }
        }
    }

    override fun start() {
        delegate?.start()
    }

    override fun stop() {
        delegate?.stop()
    }

    override fun bindSocket(socket: Socket) {
        val attached = delegate ?: throw TailnetUnavailableException(
            TailnetBlockReason.TailnetNetworkAbsent,
            "Tailnet enforcement is not initialized in this process",
        )
        attached.bindSocket(socket)
    }

    override fun lookup(host: String): List<InetAddress> {
        val attached = delegate ?: return TailnetLookupRules.lookup(host, null)
        return attached.lookup(host)
    }
}

/**
 * Production source. Watches VPN-transport networks, classifies them with
 * [TailnetNetworkClassifier] (addresses only) and binds Hermes sockets per socket.
 *
 * UNPROVEN on real devices (settled by the on-device matrix, see ADR 75): that Tailscale's VPN
 * network is delivered to this callback with tailnet link addresses; whether an app excluded by
 * Tailscale split tunneling gets a bind error or a silent black hole (either way the result is
 * fail closed: a refused bind or an unreachable host); that `getAllByName` on the network uses
 * MagicDNS for `.ts.net` names.
 */
class AndroidTailnetNetworkSource(
    context: Context,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) : TailnetNetworkSource {

    private data class Entry(
        val network: Network,
        val firstSeenElapsedMs: Long,
        val isVpn: Boolean?,
        val linkAddresses: List<InetAddress>?,
        val dnsServers: List<InetAddress>,
    )

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val mutableStatus = MutableStateFlow<TailnetNetworkStatus>(TailnetNetworkStatus.Absent)
    override val status: StateFlow<TailnetNetworkStatus> = mutableStatus.asStateFlow()

    @Volatile
    private var picked: Network? = null

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            upsert(network) { it }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val vpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            upsert(network) { it.copy(isVpn = vpn) }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val addresses = linkProperties.linkAddresses.map { it.address }
            val dns = linkProperties.dnsServers.toList()
            upsert(network) { it.copy(linkAddresses = addresses, dnsServers = dns) }
        }

        override fun onLost(network: Network) {
            entries.remove(network.networkHandle)
            republish()
        }
    }

    @Synchronized
    override fun start() {
        if (registered) return
        val cm = connectivityManager
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager unavailable; tailnet stays absent")
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    @Synchronized
    override fun stop() {
        if (!registered) return
        val cm = connectivityManager
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}")
            }
        }
        registered = false
        entries.clear()
        republish()
    }

    override fun bindSocket(socket: Socket) {
        val network = picked ?: throw TailnetUnavailableException(
            TailnetBlockReason.TailnetNetworkAbsent,
            "No tailnet network is available",
        )
        val cm = connectivityManager
        if (cm == null || cm.getNetworkCapabilities(network) == null) {
            entries.remove(network.networkHandle)
            republish()
            throw TailnetUnavailableException(
                TailnetBlockReason.TailnetNetworkAbsent,
                "The tailnet network is no longer available",
            )
        }
        try {
            network.bindSocket(socket)
        } catch (e: IOException) {
            throw TailnetUnavailableException(
                TailnetBlockReason.TailnetNetworkAbsent,
                "The tailnet network refused the socket",
            ).apply { initCause(e) }
        } catch (e: SecurityException) {
            throw TailnetUnavailableException(
                TailnetBlockReason.TailnetNetworkAbsent,
                "The tailnet network refused the socket",
            ).apply { initCause(e) }
        }
    }

    override fun lookup(host: String): List<InetAddress> {
        val network = picked ?: return TailnetLookupRules.lookup(host, null)
        return TailnetLookupRules.lookup(host) { name -> network.getAllByName(name).toList() }
    }

    private fun upsert(network: Network, change: (Entry) -> Entry) {
        entries.compute(network.networkHandle) { _, old ->
            change(old ?: Entry(network, elapsed(), null, null, emptyList()))
        }
        republish()
    }

    @Synchronized
    private fun republish() {
        val snapshots = entries.values.mapNotNull { entry ->
            val isVpn = entry.isVpn ?: return@mapNotNull null
            val addresses = entry.linkAddresses ?: return@mapNotNull null
            NetworkSnapshot(
                handle = entry.network.networkHandle,
                isVpn = isVpn,
                linkAddresses = addresses,
                dnsServers = entry.dnsServers,
                firstSeenElapsedMs = entry.firstSeenElapsedMs,
            )
        }
        val chosen = TailnetNetworkClassifier.pick(snapshots)
        val chosenEntry = chosen?.let { entries[it.handle] }
        picked = chosenEntry?.network
        mutableStatus.value = if (chosen != null && chosenEntry != null) {
            TailnetNetworkStatus.Present(
                network = chosenEntry.network,
                linkAddresses = chosen.linkAddresses.filter { TailnetAddresses.isTailnetAddress(it) },
            )
        } else {
            TailnetNetworkStatus.Absent
        }
    }

    private companion object {
        const val TAG = "TailnetNetworkSource"
    }
}
```

  `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import android.net.Network
import io.mockk.mockk
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test double for [TailnetNetworkSource], shared by the tailnet tests in this package.
 * [bindSocket] and [lookup] behave like the production source: absent network fails closed.
 */
internal class FakeTailnetNetworkSource : TailnetNetworkSource {
    val mutableStatus = MutableStateFlow<TailnetNetworkStatus>(TailnetNetworkStatus.Absent)
    override val status: StateFlow<TailnetNetworkStatus> = mutableStatus
    val dnsTable = mutableMapOf<String, List<InetAddress>>()
    var bindCalls = 0
    var lookupCalls = 0
    var startCalls = 0
    var stopCalls = 0

    fun setPresent() {
        mutableStatus.value = TailnetNetworkStatus.Present(
            network = mockk<Network>(relaxed = true),
            linkAddresses = listOf(TailnetAddresses.ipLiteralOrNull("100.64.0.2")!!),
        )
    }

    fun setAbsent() {
        mutableStatus.value = TailnetNetworkStatus.Absent
    }

    override fun start() {
        startCalls += 1
    }

    override fun stop() {
        stopCalls += 1
    }

    override fun bindSocket(socket: Socket) {
        if (mutableStatus.value is TailnetNetworkStatus.Absent) {
            throw TailnetUnavailableException(TailnetBlockReason.TailnetNetworkAbsent, "absent")
        }
        bindCalls += 1
    }

    override fun lookup(host: String): List<InetAddress> {
        lookupCalls += 1
        val resolver: ((String) -> List<InetAddress>)? =
            if (mutableStatus.value is TailnetNetworkStatus.Present) ::resolveFromTable else null
        return TailnetLookupRules.lookup(host, resolver)
    }

    private fun resolveFromTable(name: String): List<InetAddress> =
        dnsTable[name] ?: throw UnknownHostException(name)
}

class TailnetNetworkSourceTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun ip(literal: String): InetAddress = TailnetAddresses.ipLiteralOrNull(literal)!!

    @Test
    fun `tailnet literal is returned without calling the resolver`() {
        var calls = 0
        val result = TailnetLookupRules.lookup("100.64.0.9") { calls += 1; emptyList() }
        assertEquals(listOf(ip("100.64.0.9")), result)
        assertEquals(0, calls)
        assertEquals(listOf(ip("100.64.0.9")), TailnetLookupRules.lookup("100.64.0.9", null))
    }

    @Test
    fun `non tailnet literal is refused`() {
        val error = assertThrows(TailnetUnavailableException::class.java) {
            TailnetLookupRules.lookup("10.0.0.20") { listOf(ip("10.0.0.20")) }
        }
        assertEquals(TailnetBlockReason.HostNotOnTailnet, error.reason)
    }

    @Test
    fun `name without a tailnet network fails closed`() {
        val error = assertThrows(TailnetUnavailableException::class.java) {
            TailnetLookupRules.lookup("host.tail0000.ts.net", null)
        }
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, error.reason)
    }

    @Test
    fun `resolver answers are filtered to tailnet addresses`() {
        val result = TailnetLookupRules.lookup("host.tail0000.ts.net") {
            listOf(ip("203.0.113.7"), ip("100.64.0.9"), ip("fd7a:115c:a1e0::9"))
        }
        assertEquals(listOf(ip("100.64.0.9"), ip("fd7a:115c:a1e0::9")), result)
    }

    @Test
    fun `resolver answers without a tailnet address are refused`() {
        val error = assertThrows(TailnetUnavailableException::class.java) {
            TailnetLookupRules.lookup("host.tail0000.ts.net") { listOf(ip("203.0.113.7")) }
        }
        assertEquals(TailnetBlockReason.HostNotOnTailnet, error.reason)
    }

    @Test
    fun `failed resolution is reported as not on the tailnet`() {
        val error = assertThrows(TailnetUnavailableException::class.java) {
            TailnetLookupRules.lookup("host.tail0000.ts.net") { throw UnknownHostException("nope") }
        }
        assertEquals(TailnetBlockReason.HostNotOnTailnet, error.reason)
    }

    @Test
    fun `tailnet exception is an io exception`() {
        val error: Throwable = TailnetUnavailableException(TailnetBlockReason.HostUnreachable, "x")
        assertTrue(error is IOException)
    }

    @Test
    fun `deferred source fails closed before attach`() {
        val deferred = DeferredTailnetNetworkSource(scope)
        assertFalse(deferred.isAttached)
        assertEquals(TailnetNetworkStatus.Absent, deferred.status.value)
        Socket().use { socket ->
            val error = assertThrows(TailnetUnavailableException::class.java) { deferred.bindSocket(socket) }
            assertEquals(TailnetBlockReason.TailnetNetworkAbsent, error.reason)
        }
        val lookupError = assertThrows(TailnetUnavailableException::class.java) {
            deferred.lookup("host.tail0000.ts.net")
        }
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, lookupError.reason)
        assertEquals(listOf(ip("100.64.0.9")), deferred.lookup("100.64.0.9"))
    }

    @Test
    fun `deferred source delegates and mirrors status after attach`() {
        val deferred = DeferredTailnetNetworkSource(scope)
        val fake = FakeTailnetNetworkSource()
        deferred.attach(fake)
        assertTrue(deferred.isAttached)
        fake.setPresent()
        assertSame(fake.status.value, deferred.status.value)
        Socket().use { socket -> deferred.bindSocket(socket) }
        assertEquals(1, fake.bindCalls)
        fake.dnsTable["host.tail0000.ts.net"] = listOf(ip("100.64.0.9"))
        assertEquals(listOf(ip("100.64.0.9")), deferred.lookup("host.tail0000.ts.net"))
        deferred.start()
        deferred.stop()
        assertEquals(1, fake.startCalls)
        assertEquals(1, fake.stopCalls)
        fake.setAbsent()
        assertEquals(TailnetNetworkStatus.Absent, deferred.status.value)
    }

    @Test
    fun `second attach is ignored`() {
        val deferred = DeferredTailnetNetworkSource(scope)
        val first = FakeTailnetNetworkSource()
        val second = FakeTailnetNetworkSource()
        deferred.attach(first)
        deferred.attach(second)
        first.setPresent()
        Socket().use { socket -> deferred.bindSocket(socket) }
        assertEquals(1, first.bindCalls)
        assertEquals(0, second.bindCalls)
    }
}
```
- **Steps:**
  1. `cd <repo>` and confirm the branch: `git rev-parse --abbrev-ref HEAD` → `feat/android-tailscale-always-on`.
  2. Symbol check: `grep -n 'object TailnetNetworkClassifier\|data class NetworkSnapshot' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkClassifier.kt` → 2 hits; `grep -n 'testImplementation(libs.mockk)\|testImplementation(libs.kotlinx.coroutines.test)' app/build.gradle.kts` → 2 hits (mockk is used by the test double; no new dependency).
  3. Create `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt` with the first block verbatim (the write tool creates missing directories).
  4. Create `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt` with the second block verbatim.
  5. Run the Verify commands 1-8 below and keep their real output for the report.
  6. `git add app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt`
  7. `git commit -m "feat(android): add tailnet network source isolation boundary"`
- **Verify:** (run each command from the worktree; expected output after the arrow)
  1. `cd <repo> && test -f app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt && test -f app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt && echo present` → `present`
  2. `cd <repo> && sha256sum app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt` → `d1baad93741ff51f74ddec0ecc9238c480eb4dcf1298f76e0d903f76cb7bcaae  app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt` and `a74a4da65dd47d2d9bd2f9bc7132a6521e6dbaef44036cca15261ab476e61168  app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt` (if a hash differs, re-copy the block exactly; a difference caused ONLY by the final newline is acceptable and must be reported under Deviations)
  3. `cd <repo> && python3 -c "import pathlib,sys; [print(p, (t:=pathlib.Path(p).read_text()).count('{')-t.count('}'), t.count('(')-t.count(')')) for p in sys.argv[1:]]" app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt` → both lines end with `0 0`
  4. `cd <repo> && grep -c '^package com.hermesandroid.relay.net.tailnet$' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt` → `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt:1` and `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt:1`
  5. `cd <repo> && python3 scripts/check-android-collection-apis.py` → `Android collection API compatibility check passed (Kotlin sources)` (exit 0)
  6. `cd <repo> && grep -rnE 'bindProcessToNetwork|setProcessDefaultNetwork|VpnService|setUnderlyingNetworks' app/src/main; echo rc=$?` → no match lines, then `rc=1`
  7. `cd <repo> && git diff --stat e5989426 -- gradle/libs.versions.toml app/build.gradle.kts` → empty output (no dependency change)
  8. `cd <repo> && git status --short` → exactly `?? app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt` and `?? app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt` before the commit (the first task shows the directory form `?? app/src/main/kotlin/com/hermesandroid/relay/net/` / `?? app/src/test/kotlin/com/hermesandroid/relay/net/` instead — also acceptable); after the commit: `git show --stat --format=%s HEAD` lists exactly these 2 files and the subject below.
  9. NOT verifiable on this host (no JDK): compilation and the unit tests in `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt`. They run in cloud CI once slice D registers the test class in `FOCUSED_TESTS`; report them under "what you could NOT verify".
- **Commit:** `feat(android): add tailnet network source isolation boundary`

#### Task A5: Add the single enforcement owner (`TailnetEnforcer`)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt`, `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt`
- **Depends on:** Task A4
- **Estimate:** L (264 + 156 lines, new files)
- **Create / modify:** two NEW files. The file content is EXACTLY the fenced block content followed by one trailing newline. Do not reformat, reorder imports, or add/remove lines.

  `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import android.content.Context
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * THE single enforcement owner for "Always connect via Tailscale" (ADR 75).
 *
 * It is the only holder of the policy bit ([setPolicy]), the only route to the tailnet network
 * (through its [TailnetNetworkSource]) and the only producer of the socket factory, DNS,
 * address guard and URL check used by Hermes-host transports.
 *
 * Every decision is taken per socket / per lookup / per request, never at client build time,
 * so long-lived clients follow the toggle without being rebuilt. With the policy off every
 * decoration is a pass-through: a plain `Socket()`, the fallback DNS, and a guard that only
 * calls `proceed`.
 *
 * Fail closed: with the policy on and no tailnet network, sockets are refused before any
 * network I/O, names are not resolved, and non-tailnet peers are rejected.
 */
class TailnetEnforcer private constructor(
    private val source: TailnetNetworkSource,
    private val scope: CoroutineScope,
) {
    private data class TailnetPolicy(val connectionId: String?, val enabled: Boolean)

    @Volatile
    private var policy = TailnetPolicy(connectionId = null, enabled = false)

    private val mutableEnforcing = MutableStateFlow(false)

    /** True while the active connection has the policy on. */
    val enforcing: StateFlow<Boolean> = mutableEnforcing.asStateFlow()

    private val mutableGeneration = MutableStateFlow(0L)

    /** Bumps whenever the policy or the tailnet network status changes. */
    val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    /** The tailnet network status from the source. */
    val networkStatus: StateFlow<TailnetNetworkStatus>
        get() = source.status

    /** The connection id the current policy was set for, or null. */
    val activeConnectionId: String?
        get() = policy.connectionId

    private val registeredClients: MutableSet<OkHttpClient> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<OkHttpClient, Boolean>()))
    private val boundSocketFactory: SocketFactory = TailnetSocketFactory(this)
    private val guard: Interceptor = TailnetAddressGuard(this)

    init {
        scope.launch {
            var last = source.status.value
            source.status.collect { status ->
                if (status != last) {
                    last = status
                    bumpGeneration()
                }
            }
        }
    }

    /**
     * Sets the policy for the active connection. Called by the connection owner whenever the
     * active connection changes or its toggle flips. Enforcing iff [enabled] and
     * [activeConnectionId] is not null.
     */
    fun setPolicy(activeConnectionId: String?, enabled: Boolean) {
        val next = TailnetPolicy(connectionId = activeConnectionId, enabled = enabled)
        val changed = synchronized(this) {
            val different = policy != next
            policy = next
            mutableEnforcing.value = enabled && activeConnectionId != null
            different
        }
        if (changed) bumpGeneration()
    }

    fun isEnforcing(): Boolean = mutableEnforcing.value

    /**
     * Null when [url] may be used now. While enforcing: [TailnetBlockReason.HostNotOnTailnet]
     * for a non-tailnet (or null) URL, [TailnetBlockReason.TailnetNetworkAbsent] when no tailnet
     * network is visible.
     */
    fun checkUrl(url: String?): TailnetBlockReason? {
        if (!isEnforcing()) return null
        if (!TailnetAddresses.isTailnetUrl(url)) return TailnetBlockReason.HostNotOnTailnet
        if (source.status.value is TailnetNetworkStatus.Absent) return TailnetBlockReason.TailnetNetworkAbsent
        return null
    }

    /** One shared factory; binds each new socket to the tailnet while enforcing. */
    fun socketFactory(): SocketFactory = boundSocketFactory

    /** Tailnet-only DNS while enforcing; [fallback] otherwise. */
    fun dns(fallback: Dns = Dns.SYSTEM): Dns = TailnetDns(this, fallback)

    /** Network interceptor that rejects any non-tailnet peer while enforcing. */
    fun addressGuard(): Interceptor = guard

    /** Tracks [client] (weakly) so its idle pooled connections are evicted on every generation bump. */
    fun register(client: OkHttpClient): OkHttpClient {
        registeredClients.add(client)
        return client
    }

    internal fun bindIfEnforcing(socket: Socket) {
        if (isEnforcing()) source.bindSocket(socket)
    }

    internal fun lookup(host: String, fallback: Dns): List<InetAddress> =
        if (isEnforcing()) source.lookup(host) else fallback.lookup(host)

    internal fun socketAddressFor(host: String?, port: Int): InetSocketAddress =
        if (host != null && isEnforcing()) {
            InetSocketAddress(source.lookup(host).first(), port)
        } else {
            InetSocketAddress(host, port)
        }

    private fun bumpGeneration() {
        synchronized(this) {
            mutableGeneration.value = mutableGeneration.value + 1
        }
        val clients = synchronized(registeredClients) { registeredClients.toList() }
        if (clients.isEmpty()) return
        scope.launch {
            clients.forEach { client -> runCatching { client.connectionPool.evictAll() } }
        }
    }

    companion object {
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val processSource = DeferredTailnetNetworkSource(processScope)
        private val initialized = AtomicBoolean(false)
        private val instance: TailnetEnforcer by lazy { TailnetEnforcer(processSource, processScope) }

        /**
         * Attaches and starts the Android network source. Call once from the main process
         * `Application.onCreate`; later calls are no-ops. Before this runs, [get] still returns
         * the process enforcer, whose source is permanently absent (fail closed while enforcing).
         */
        fun initialize(context: Context) {
            if (!initialized.compareAndSet(false, true)) return
            val androidSource = AndroidTailnetNetworkSource(context.applicationContext)
            processSource.attach(androidSource)
            androidSource.start()
        }

        /** The process-wide enforcer. Always the same instance. */
        fun get(): TailnetEnforcer = instance

        /** Test seam: an enforcer over an arbitrary source and scope. */
        internal fun create(source: TailnetNetworkSource, scope: CoroutineScope): TailnetEnforcer =
            TailnetEnforcer(source, scope)
    }
}

/** Creates plain sockets and binds each one to the tailnet while the enforcer is enforcing. */
internal class TailnetSocketFactory(private val enforcer: TailnetEnforcer) : SocketFactory() {

    override fun createSocket(): Socket {
        val socket = Socket()
        try {
            enforcer.bindIfEnforcing(socket)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        } catch (e: RuntimeException) {
            closeQuietly(socket)
            throw e
        }
        return socket
    }

    override fun createSocket(host: String?, port: Int): Socket {
        val remote = enforcer.socketAddressFor(host, port)
        return connected(createSocket(), remote, null, 0)
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        val remote = enforcer.socketAddressFor(host, port)
        return connected(createSocket(), remote, localHost, localPort)
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        connected(createSocket(), InetSocketAddress(host, port), null, 0)

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = connected(createSocket(), InetSocketAddress(address, port), localAddress, localPort)

    private fun connected(
        socket: Socket,
        remote: InetSocketAddress,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket {
        try {
            if (localAddress != null) socket.bind(InetSocketAddress(localAddress, localPort))
            socket.connect(remote)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
        return socket
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }
}

/** Tailnet-only DNS while enforcing; the fallback DNS otherwise. Decided per lookup. */
internal class TailnetDns(
    private val enforcer: TailnetEnforcer,
    private val fallback: Dns,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = enforcer.lookup(hostname, fallback)
}

/**
 * Network interceptor: while enforcing, the connected peer must be a tailnet address.
 * Catches IP-literal hosts (OkHttp does not call `Dns` for literals), proxies and stale pooled
 * connections. Also the idempotency marker for `enforceTailnetPolicy`.
 */
internal class TailnetAddressGuard(private val enforcer: TailnetEnforcer) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (enforcer.isEnforcing()) {
            val peer = chain.connection()?.route()?.socketAddress?.address
            if (peer == null || !TailnetAddresses.isTailnetAddress(peer)) {
                throw TailnetUnavailableException(
                    TailnetBlockReason.HostNotOnTailnet,
                    "Connection is not on the tailnet",
                )
            }
        }
        return chain.proceed(chain.request())
    }
}
```

  `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetEnforcerTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val source = FakeTailnetNetworkSource()
    private val enforcer = TailnetEnforcer.create(source, scope)

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun ip(literal: String): InetAddress = TailnetAddresses.ipLiteralOrNull(literal)!!

    private class CountingDns(private val answer: List<InetAddress>) : Dns {
        var calls = 0

        override fun lookup(hostname: String): List<InetAddress> {
            calls += 1
            return answer
        }
    }

    @Test
    fun `policy off is a pass through`() {
        assertFalse(enforcer.isEnforcing())
        enforcer.socketFactory().createSocket().use { }
        assertEquals(0, source.bindCalls)
        val fallback = CountingDns(listOf(ip("10.0.0.20")))
        assertEquals(listOf(ip("10.0.0.20")), enforcer.dns(fallback).lookup("hermes.example.com"))
        assertEquals(1, fallback.calls)
        assertEquals(0, source.lookupCalls)
        assertNull(enforcer.checkUrl("http://10.0.0.20:8642"))
        assertNull(enforcer.checkUrl(null))
    }

    @Test
    fun `enabled without an active connection does not enforce`() {
        enforcer.setPolicy(null, true)
        assertFalse(enforcer.isEnforcing())
        assertFalse(enforcer.enforcing.value)
    }

    @Test
    fun `policy changes bump the generation once per change`() {
        assertEquals(0L, enforcer.generation.value)
        enforcer.setPolicy("conn-a", true)
        assertTrue(enforcer.isEnforcing())
        assertTrue(enforcer.enforcing.value)
        assertEquals("conn-a", enforcer.activeConnectionId)
        assertEquals(1L, enforcer.generation.value)
        enforcer.setPolicy("conn-a", true)
        assertEquals(1L, enforcer.generation.value)
        enforcer.setPolicy("conn-b", true)
        assertEquals(2L, enforcer.generation.value)
        enforcer.setPolicy("conn-b", false)
        assertFalse(enforcer.isEnforcing())
        assertEquals(3L, enforcer.generation.value)
    }

    @Test
    fun `network status changes bump the generation`() {
        assertEquals(0L, enforcer.generation.value)
        source.setPresent()
        assertEquals(1L, enforcer.generation.value)
        assertTrue(enforcer.networkStatus.value is TailnetNetworkStatus.Present)
        source.setAbsent()
        assertEquals(2L, enforcer.generation.value)
    }

    @Test
    fun `enforcing with no tailnet network refuses sockets and names`() {
        enforcer.setPolicy("conn-a", true)
        val socketError = assertThrows(TailnetUnavailableException::class.java) {
            enforcer.socketFactory().createSocket()
        }
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, socketError.reason)
        val fallback = CountingDns(listOf(ip("10.0.0.20")))
        val dnsError = assertThrows(TailnetUnavailableException::class.java) {
            enforcer.dns(fallback).lookup("host.tail0000.ts.net")
        }
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, dnsError.reason)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `enforcing with a tailnet network binds every socket`() {
        source.setPresent()
        enforcer.setPolicy("conn-a", true)
        enforcer.socketFactory().createSocket().use { }
        enforcer.socketFactory().createSocket().use { }
        assertEquals(2, source.bindCalls)
    }

    @Test
    fun `enforcing dns returns only tailnet answers and never the fallback`() {
        source.setPresent()
        source.dnsTable["host.tail0000.ts.net"] = listOf(ip("203.0.113.7"), ip("100.64.0.9"))
        enforcer.setPolicy("conn-a", true)
        val fallback = CountingDns(listOf(ip("10.0.0.20")))
        assertEquals(listOf(ip("100.64.0.9")), enforcer.dns(fallback).lookup("host.tail0000.ts.net"))
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `toggle is honoured per call by the same factory and dns`() {
        source.setPresent()
        val factory = enforcer.socketFactory()
        val fallback = CountingDns(listOf(ip("10.0.0.20")))
        val dns = enforcer.dns(fallback)
        enforcer.setPolicy("conn-a", true)
        factory.createSocket().use { }
        assertEquals(1, source.bindCalls)
        enforcer.setPolicy("conn-a", false)
        factory.createSocket().use { }
        assertEquals(1, source.bindCalls)
        assertEquals(listOf(ip("10.0.0.20")), dns.lookup("hermes.example.com"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `check url table while enforcing`() {
        enforcer.setPolicy("conn-a", true)
        assertEquals(TailnetBlockReason.TailnetNetworkAbsent, enforcer.checkUrl("https://host.tail0000.ts.net"))
        assertEquals(TailnetBlockReason.HostNotOnTailnet, enforcer.checkUrl("http://10.0.0.20:8642"))
        source.setPresent()
        assertNull(enforcer.checkUrl("https://host.tail0000.ts.net"))
        assertNull(enforcer.checkUrl("ws://100.64.0.9:8767"))
        assertEquals(TailnetBlockReason.HostNotOnTailnet, enforcer.checkUrl("https://hermes.example.com"))
        assertEquals(TailnetBlockReason.HostNotOnTailnet, enforcer.checkUrl(null))
    }

    @Test
    fun `process enforcer is a singleton and register returns the same client`() {
        assertSame(TailnetEnforcer.get(), TailnetEnforcer.get())
        val client = OkHttpClient()
        assertSame(client, enforcer.register(client))
    }
}
```
- **Steps:**
  1. `cd <repo>` and confirm the branch: `git rev-parse --abbrev-ref HEAD` → `feat/android-tailscale-always-on`.
  2. Symbol check: `grep -n 'class DeferredTailnetNetworkSource\|class AndroidTailnetNetworkSource\|class TailnetUnavailableException\|enum class TailnetBlockReason' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt` → 4 hits; `grep -n 'internal class FakeTailnetNetworkSource' app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSourceTest.kt` → 1 hit.
  3. Create `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt` with the first block verbatim (the write tool creates missing directories).
  4. Create `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt` with the second block verbatim.
  5. Run the Verify commands 1-8 below and keep their real output for the report.
  6. `git add app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt`
  7. `git commit -m "feat(android): add tailnet enforcement owner"`
- **Verify:** (run each command from the worktree; expected output after the arrow)
  1. `cd <repo> && test -f app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt && test -f app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt && echo present` → `present`
  2. `cd <repo> && sha256sum app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt` → `85eebb28e2a93bd995aa092572ad4e329f347af840869a7cd786efed1b4259e4  app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt` and `1c8a1aaf65c728574fe2be1ad9e6a7708fb9c0c3ad97c9e76f89cd7e414d1568  app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt` (if a hash differs, re-copy the block exactly; a difference caused ONLY by the final newline is acceptable and must be reported under Deviations)
  3. `cd <repo> && python3 -c "import pathlib,sys; [print(p, (t:=pathlib.Path(p).read_text()).count('{')-t.count('}'), t.count('(')-t.count(')')) for p in sys.argv[1:]]" app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt` → both lines end with `0 0`
  4. `cd <repo> && grep -c '^package com.hermesandroid.relay.net.tailnet$' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt` → `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt:1` and `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt:1`
  5. `cd <repo> && python3 scripts/check-android-collection-apis.py` → `Android collection API compatibility check passed (Kotlin sources)` (exit 0)
  6. `cd <repo> && grep -rnE 'bindProcessToNetwork|setProcessDefaultNetwork|VpnService|setUnderlyingNetworks' app/src/main; echo rc=$?` → no match lines, then `rc=1`
  7. `cd <repo> && git diff --stat e5989426 -- gradle/libs.versions.toml app/build.gradle.kts` → empty output (no dependency change)
  8. `cd <repo> && git status --short` → exactly `?? app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt` and `?? app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt` before the commit (the first task shows the directory form `?? app/src/main/kotlin/com/hermesandroid/relay/net/` / `?? app/src/test/kotlin/com/hermesandroid/relay/net/` instead — also acceptable); after the commit: `git show --stat --format=%s HEAD` lists exactly these 2 files and the subject below.
  9. NOT verifiable on this host (no JDK): compilation and the unit tests in `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcerTest.kt`. They run in cloud CI once slice D registers the test class in `FOCUSED_TESTS`; report them under "what you could NOT verify".
- **Commit:** `feat(android): add tailnet enforcement owner`

#### Task A6: Add `HermesClients` and the `enforceTailnetPolicy` builder extension (same file)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt`, `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt`
- **Depends on:** Task A5
- **Estimate:** S (41 + 131 lines, new files)
- **Note for the builder:** `enforceTailnetPolicy` is a TOP-LEVEL extension function in `HermesClients.kt` (not a member of `object HermesClients`). Callers import it as `import com.hermesandroid.relay.net.tailnet.enforceTailnetPolicy`.
- **Create / modify:** two NEW files. The file content is EXACTLY the fenced block content followed by one trailing newline. Do not reformat, reorder imports, or add/remove lines.

  `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Decorates this builder for Hermes-host traffic under "Always connect via Tailscale" (ADR 75):
 * the enforcer's socket factory, DNS (wrapping [dnsFallback]) and address guard.
 *
 * Normative order inside a builder: existing timeouts / pinner / cookie jar, then this call,
 * then `build()`. Do not call `socketFactory(...)` or `dns(...)` on the same builder after
 * this call; pass a custom resolver as [dnsFallback] instead.
 *
 * Idempotent: a builder that already carries the tailnet address guard (for example one from
 * `client.newBuilder()` of a decorated client) is returned unchanged.
 *
 * The decoration is always installed; whether it enforces is decided by the enforcer on every
 * socket, lookup and request, so a client built while the policy is off enforces as soon as the
 * policy turns on, and vice versa.
 */
fun OkHttpClient.Builder.enforceTailnetPolicy(
    enforcer: TailnetEnforcer = TailnetEnforcer.get(),
    dnsFallback: Dns = Dns.SYSTEM,
): OkHttpClient.Builder {
    if (networkInterceptors().any { it is TailnetAddressGuard }) return this
    socketFactory(enforcer.socketFactory())
    dns(enforcer.dns(dnsFallback))
    addNetworkInterceptor(enforcer.addressGuard())
    return this
}

/** The ONE constructor every Hermes-host OkHttp client goes through. */
object HermesClients {

    /** `builder.enforceTailnetPolicy(enforcer, dnsFallback).build()`, registered with [enforcer]. */
    fun build(
        builder: OkHttpClient.Builder = OkHttpClient.Builder(),
        enforcer: TailnetEnforcer = TailnetEnforcer.get(),
        dnsFallback: Dns = Dns.SYSTEM,
    ): OkHttpClient = enforcer.register(builder.enforceTailnetPolicy(enforcer, dnsFallback).build())
}
```

  `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt`:
```kotlin
package com.hermesandroid.relay.net.tailnet

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesClientsTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val source = FakeTailnetNetworkSource()
    private val enforcer = TailnetEnforcer.create(source, scope)
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        job.cancel()
    }

    private fun client(): OkHttpClient = HermesClients.build(
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false),
        enforcer,
    )

    private fun guardCount(builder: OkHttpClient.Builder): Int =
        builder.networkInterceptors().count { it is TailnetAddressGuard }

    @Test
    fun `decoration is idempotent`() {
        val builder = OkHttpClient.Builder()
            .enforceTailnetPolicy(enforcer)
            .enforceTailnetPolicy(enforcer)
        assertEquals(1, guardCount(builder))
    }

    @Test
    fun `derived builders keep the decoration without duplicating it`() {
        val base = client()
        assertTrue(base.socketFactory is TailnetSocketFactory)
        assertTrue(base.dns is TailnetDns)
        val derived = base.newBuilder()
        assertEquals(1, guardCount(derived))
        assertEquals(1, guardCount(derived.enforceTailnetPolicy(enforcer)))
        val derivedClient = derived.build()
        assertTrue(derivedClient.socketFactory is TailnetSocketFactory)
        assertTrue(derivedClient.dns is TailnetDns)
    }

    @Test
    fun `policy off leaves requests unchanged`() {
        server.enqueue(MockResponse().setBody("ok"))
        client().newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `policy on rejects a non tailnet peer before any request is sent`() {
        source.setPresent()
        enforcer.setPolicy("conn-a", true)
        server.enqueue(MockResponse().setBody("ok"))
        val literalUrl = "http://127.0.0.1:${server.port}/"
        assertThrows(IOException::class.java) {
            client().newCall(Request.Builder().url(literalUrl).build()).execute().close()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `policy on refuses names that do not resolve on the tailnet`() {
        source.setPresent()
        enforcer.setPolicy("conn-a", true)
        server.enqueue(MockResponse().setBody("ok"))
        assertThrows(IOException::class.java) {
            client().newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the same client follows the toggle per request`() {
        val shared = client()
        source.setPresent()
        enforcer.setPolicy("conn-a", true)
        assertThrows(IOException::class.java) {
            shared.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/").build()).execute().close()
        }
        enforcer.setPolicy("conn-a", false)
        server.enqueue(MockResponse().setBody("ok"))
        shared.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `tailnet dns answers are used while enforcing`() {
        source.setPresent()
        source.dnsTable["host.tail0000.ts.net"] = listOf(InetAddress.getByAddress(byteArrayOf(100, 64, 0, 9)))
        enforcer.setPolicy("conn-a", true)
        assertEquals(
            listOf(InetAddress.getByAddress(byteArrayOf(100, 64, 0, 9))),
            client().dns.lookup("host.tail0000.ts.net"),
        )
    }
}
```
- **Steps:**
  1. `cd <repo>` and confirm the branch: `git rev-parse --abbrev-ref HEAD` → `feat/android-tailscale-always-on`.
  2. Symbol check: `grep -n 'internal class TailnetAddressGuard\|internal class TailnetSocketFactory\|internal class TailnetDns\|fun register(client: OkHttpClient)' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetEnforcer.kt` → 4 hits; `grep -n 'testImplementation(libs.okhttp.mockwebserver)' app/build.gradle.kts` → 1 hit.
  3. Create `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt` with the first block verbatim (the write tool creates missing directories).
  4. Create `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt` with the second block verbatim.
  5. Run the Verify commands 1-8 below and keep their real output for the report.
  6. `git add app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt`
  7. `git commit -m "feat(android): add HermesClients tailnet client constructor"`
- **Verify:** (run each command from the worktree; expected output after the arrow)
  1. `cd <repo> && test -f app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt && test -f app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt && echo present` → `present`
  2. `cd <repo> && sha256sum app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt` → `67af7200dc10a66e07086505b95b852adf6e1ee116a50630b612ca1c0aefdf6d  app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt` and `2a1d2930eaf6b3c9a13d4e0431bc55bbe9a7a81611dc6f7432344a2fe7845735  app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt` (if a hash differs, re-copy the block exactly; a difference caused ONLY by the final newline is acceptable and must be reported under Deviations)
  3. `cd <repo> && python3 -c "import pathlib,sys; [print(p, (t:=pathlib.Path(p).read_text()).count('{')-t.count('}'), t.count('(')-t.count(')')) for p in sys.argv[1:]]" app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt` → both lines end with `0 0`
  4. `cd <repo> && grep -c '^package com.hermesandroid.relay.net.tailnet$' app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt` → `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt:1` and `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt:1`
  5. `cd <repo> && python3 scripts/check-android-collection-apis.py` → `Android collection API compatibility check passed (Kotlin sources)` (exit 0)
  6. `cd <repo> && grep -rnE 'bindProcessToNetwork|setProcessDefaultNetwork|VpnService|setUnderlyingNetworks' app/src/main; echo rc=$?` → no match lines, then `rc=1`
  7. `cd <repo> && git diff --stat e5989426 -- gradle/libs.versions.toml app/build.gradle.kts` → empty output (no dependency change)
  8. `cd <repo> && git status --short` → exactly `?? app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/HermesClients.kt` and `?? app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt` before the commit (the first task shows the directory form `?? app/src/main/kotlin/com/hermesandroid/relay/net/` / `?? app/src/test/kotlin/com/hermesandroid/relay/net/` instead — also acceptable); after the commit: `git show --stat --format=%s HEAD` lists exactly these 2 files and the subject below.
  9. NOT verifiable on this host (no JDK): compilation and the unit tests in `app/src/test/kotlin/com/hermesandroid/relay/net/tailnet/HermesClientsTest.kt`. They run in cloud CI once slice D registers the test class in `FOCUSED_TESTS`; report them under "what you could NOT verify".
- **Commit:** `feat(android): add HermesClients tailnet client constructor`

## Slice A open questions

1. **Package/path conflict (MERGE vs FILE_OWNERSHIP).** design_MERGED.md C2-C6 (and D1) place these files in
   `…/network/shared/`; FILE_OWNERSHIP.md row A (orchestrator, binding on which files a slice may write) places them
   in `…/net/tailnet/`. This slice follows FILE_OWNERSHIP. Consequences the orchestrator must propagate:
   (a) slices B/C import `com.hermesandroid.relay.net.tailnet.*`, not `…network.shared.*`;
   (b) slice D's static gate must allowlist `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetNetworkSource.kt`
   as the single `tailnet-source` file, and classify `…/net/tailnet/HermesClients.kt` (it contains the literal
   `OkHttpClient.Builder()` as a default argument, and `enforceTailnetPolicy(`) as `hermes-enforced`;
   (c) none of the six files imports `…network.relay` or `…network.upstream`, so a later mechanical move into
   `network/shared` would still pass the ADR 34 Konsist fence (`ArchitectureBoundaryTest.kt`).
2. **`enforceTailnetPolicy` file.** MERGE C6 names `TailnetOkHttp.kt`; the slice brief and D1 C7 put it in
   `HermesClients.kt`. Implemented in `HermesClients.kt` with MERGE C6's exact signature as a TOP-LEVEL extension
   (D1 C7 sketched it as a member of `object HermesClients`, which would force every call site into
   `with(HermesClients)`). No `TailnetOkHttp.kt` exists; nobody may create one.
3. **"When not enforcing: must be a no-op" (MERGE C6)** is implemented as runtime behaviour, not build-time
   omission: the decoration is always installed and every socket / lookup / request asks the enforcer. A build-time
   no-op would leave long-lived clients (Coil, relay, gateway) unbound after the user turns the policy on — a silent
   downgrade that FD2 forbids — and the slice brief requires per-request consultation.
4. **Enum/exception location.** MERGE C5 lists `TailnetBlockReason` in the TailnetEnforcer block; it lives in
   `TailnetNetworkSource.kt` together with `TailnetUnavailableException` so every commit compiles in dependency
   order. Same package, so imports are identical. Values are MERGE's four; D1's `PolicyNoRoute`,
   `TailscaleNotInstalled`, `TailnetDown`, `BindRefused` do NOT exist — a refused bind maps to
   `TailnetNetworkAbsent` (MERGE refutation A5: off / not signed in / excluded are indistinguishable). "Tailscale not
   installed" is a UI-side package query (slice B/C), not a block reason. Slices B/C must use MERGE names only.
5. **Omitted D1 items (MERGE wins):** `TailnetEnforcer.DISABLED` and `BINDING_MODE` / `AddressGuardOnly` are not
   implemented (MERGE refutation A10: no weakening mode). `get()` always returns the one process enforcer. D1's
   `setActivePolicy` is named `setPolicy` (MERGE C5). D1 C9 `TailnetGate` / reducer is not in this slice's
   ownership (not in FILE_OWNERSHIP row A); if the orchestrator wants it, it needs an owner.
6. **Broker rule.** MERGE C3 makes a candidate eligible when every URL including `broker.url` is a tailnet URL and it
   is not `experimental`; D1 C3 additionally rejects any candidate with a broker. MERGE was followed. Real Hermes
   Reach brokers are public WSS hosts, so they are ineligible either way (test `broker on a public host is not
   eligible`).
7. **Wiring owed by slice B (not done here, listed so it is not lost):** call `TailnetEnforcer.initialize(this)` in
   `HermesRelayApp.onCreate()` inside the main-process branch; call `TailnetEnforcer.get().setPolicy(connection?.id,
   connection?.alwaysViaTailscale == true)` from the active-connection observer before transports are (re)built and
   from `setTailscaleAlwaysConnectEnabled`; pass custom resolvers as `dnsFallback` (NativeDashboardAuth's retrying
   DNS) and never call `.dns(...)`/`.socketFactory(...)` after `enforceTailnetPolicy` on the same builder chain.
8. **Test registration owed by slice D:** add to `scripts/android-prepush.py` `FOCUSED_TESTS` (and the
   `ci-android.yml` `--tests` list if slice D decides so):
   `com.hermesandroid.relay.net.tailnet.TailnetAddressesTest`, `com.hermesandroid.relay.net.tailnet.TailnetRoutePolicyTest`,
   `com.hermesandroid.relay.net.tailnet.TailnetNetworkClassifierTest`, `com.hermesandroid.relay.net.tailnet.TailnetNetworkSourceTest`,
   `com.hermesandroid.relay.net.tailnet.TailnetEnforcerTest`, `com.hermesandroid.relay.net.tailnet.HermesClientsTest`.
   BUILD_PROTOCOL rule 9 would have the slice A builder do this, but the file is slice D's; slice A builders must not
   edit it.
9. `UNPROVEN:` (device only, isolated in `AndroidTailnetNetworkSource`): Tailscale's VPN network is delivered to a
   `TRANSPORT_VPN` callback with tailnet link addresses; `Network.bindSocket` for an app excluded by Tailscale split
   tunneling errors vs black-holes (either way fail closed); `Network.getAllByName` uses MagicDNS for `.ts.net`.
   Settle with the device matrix in design_MERGED.md "Open/unproven list".
10. `UNPROVEN:` how IPv6 API hosts are stored. `ApiEndpoint.url` is built as `"http://$host:$port"`
    (`Endpoint.kt:71-73`); a bracketed host (`[fd7a:…]`) parses and is eligible, an unbracketed IPv6 host yields an
    unparsable URL and is therefore ineligible (fail closed, never a leak).
11. A system HTTP proxy makes the route peer the proxy address, so the guard rejects Hermes requests while the policy
    is on (fail closed). `Proxy.NO_PROXY` is deliberately not forced, to keep policy-OFF behaviour identical.
12. `PLAN:` the `HermesClientsTest` case `policy on refuses names that do not resolve on the tailnet` relies on
    `MockWebServer.url("/")`'s host; whether it is `localhost` (refused by the tailnet DNS) or `127.0.0.1` (refused by
    the guard), the assertion (IOException, zero requests received) holds. Confirmed only when CI runs it.

## Slice A self-check

- Only files from FILE_OWNERSHIP row A are created (six production + six tests); no existing file is touched; no
  second file for the same purpose (no `TailnetOkHttp.kt`, no separate fake file — the fake lives in
  `TailnetNetworkSourceTest.kt`).
- Every NEW file is given as complete content with `package` and every import; every task lists the exact symbols
  it depends on and a grep that proves they exist before the builder starts.
- Brace/parenthesis balance of all twelve blocks was checked mechanically (0/0) and a sha256 per file is given so a
  reviewer can prove verbatim copying.
- No placeholders and no deferred-work markers; unproven platform facts are marked `UNPROVEN:`/`PLAN:`.
- No new dependency (`gradle/libs.versions.toml`, `app/build.gradle.kts` untouched; tests use JUnit4, MockK,
  MockWebServer and kotlinx-coroutines already on the test classpath). No reflection, no experimental APIs.
- Invariants: no `VpnService`, no `bindProcessToNetwork`/`setProcessDefaultNetwork`, no interface-name matching,
  no VPN-owner identification, no wire-format change; the only `bindSocket` on a `Network` is in
  `TailnetNetworkSource.kt`.
- Fail closed everywhere the tailnet is absent while enforcing (socket refused, name refused, non-tailnet peer
  refused, pre-initialization source permanently absent); zero behaviour change while OFF.
- `TailnetRoutePolicyTest` exists (Task A2) and covers public/Funnel, role-agnostic address rule, surface mixing,
  experimental, broker, no-URL, blank-URL and order preservation.
- Hygiene: only synthetic names/addresses (`tail0000.ts.net`, `example.ts.net`, `100.64.0.x`, `fd7a:115c:a1e0::x`,
  `10.0.0.20`, `203.0.113.7`, `hermes.example.com`); no personal names, no real tailnet names.
- Each task has a Verify block and a Conventional Commit subject; compile and unit-test proof is explicitly deferred
  to cloud CI (no JDK here).


---

## Slice B — call-site integration
## Slice B contracts consumed
- D1 C6 TailnetEnforcer (policy + socketFactory/dns/addressGuard + checkUrl)
- D1 C7 HermesClients + OkHttpClient.Builder.enforceTailnetPolicy (normative decoration order)
- D1 C8 ConnectionManager route gate hooks (tailnetPolicyActive, tailnetUrlCheck, tailnetResolveBlock)
- D1 §1.3 / §1.4 transport inventory + decoration order (26 transports; slice B owns the call sites listed in FILE_OWNERSHIP row B)
- D1 §3.2 silent-downgrade sites S1–S12 (slice B implements the sites that live in its owned files; UI-only sites owned by slice C are listed under Open questions)
- RESOLVED_FACTS 1 (Coil 3 OkHttpNetworkFetcherFactory(callFactory = { ... }) is available)

### Tasks
#### Task B1: Add Android 11+ package visibility queries for the Tailscale app
- **Files (owns):** `app/src/main/AndroidManifest.xml`, `app/src/googlePlay/AndroidManifest.xml`
- **Depends on:** none
- **Estimate:** S
- **Create / modify:**
  In `app/src/main/AndroidManifest.xml`, insert a targeted `<queries>` block for the single package `com.tailscale.ipn` immediately before `<application>`.

  ```OLD (exact)
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:name=".HermesRelayApp"
        android:allowBackup="true"
        android:enableOnBackInvokedCallback="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:localeConfig="@xml/locales_config"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/Theme.HermesRelay">
  ```
  ```NEW
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <queries>
        <package android:name="com.tailscale.ipn" />
    </queries>

    <application
        android:name=".HermesRelayApp"
        android:allowBackup="true"
        android:enableOnBackInvokedCallback="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:localeConfig="@xml/locales_config"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/Theme.HermesRelay">
  ```

  In `app/src/googlePlay/AndroidManifest.xml`, insert the same `<queries>` block just after the `<manifest ...>` element open.

  ```OLD (exact)
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Media3 ExoPlayer contributes a power-management permission from its
         library manifest. Strip it from the merged Play artifact. -->
  ```
  ```NEW
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <queries>
        <package android:name="com.tailscale.ipn" />
    </queries>

    <!-- Media3 ExoPlayer contributes a power-management permission from its
         library manifest. Strip it from the merged Play artifact. -->
  ```
- **Steps:**
  1. Open `app/src/main/AndroidManifest.xml`.
  2. Insert the `<queries>` block exactly as in NEW.
  3. Open `app/src/googlePlay/AndroidManifest.xml`.
  4. Insert the `<queries>` block exactly as in NEW.
- **Verify:**
  - `cd <repo> && python3 scripts/check-android-capabilities.py` → exit 0 and includes `Play capability manifest validated (source overlays)`
  - `cd <repo> && grep -n "com.tailscale.ipn" app/src/main/AndroidManifest.xml app/src/googlePlay/AndroidManifest.xml` → 2 hits
- **Commit:** `feat(android): add package visibility for Tailscale intent`

#### Task B2: Gate ConnectionManager route selection + dial path (S1/S3/S9) and bind Relay WSS (T1)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/network/relay/ConnectionManager.kt`
- **Depends on:** none
- **Estimate:** M
- **Create / modify:**
  Transport classification covered by this task:
  - T1 Relay WSS = enforced (Bound)
  - Route lock (L1) + dial guard (L1) = enforced (no silent downgrade)

  A) Add tailnet policy hooks to the constructor, plus a tailnet resolve-block flow.

  ```OLD (exact)
    private val dashboardRelayRequestProvider: (suspend (String) -> Request?)? = null,
    /** Deterministic race seam immediately before an ingress failure may poison route state. */
    private val beforeIngressFailureCommit: suspend () -> Unit = {},
) {
  ```
  ```NEW
    private val dashboardRelayRequestProvider: (suspend (String) -> Request?)? = null,
    /** Deterministic race seam immediately before an ingress failure may poison route state. */
    private val beforeIngressFailureCommit: suspend () -> Unit = {},
    // Tailnet policy hooks (D1 C8). Defaulted so existing unit tests compile unchanged.
    private val tailnetPolicyActive: () -> Boolean = { false },
    private val tailnetUrlCheck: (String) -> TailnetBlockReason? = { null },
) {
  ```

  Insert the tailnet resolve-block state immediately after the manual-override state.

  ```OLD (exact)
    private val _manualRoleOverride = MutableStateFlow<String?>(null)
    val manualRoleOverrideFlow: StateFlow<String?> = _manualRoleOverride.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
  ```
  ```NEW
    private val _manualRoleOverride = MutableStateFlow<String?>(null)
    val manualRoleOverrideFlow: StateFlow<String?> = _manualRoleOverride.asStateFlow()

    private val _tailnetResolveBlock = MutableStateFlow<TailnetBlockReason?>(null)
    val tailnetResolveBlock: StateFlow<TailnetBlockReason?> = _tailnetResolveBlock.asStateFlow()

    private fun writesTailnetBlock(surface: EndpointSurface): Boolean =
        surface == EndpointSurface.Dashboard || surface == EndpointSurface.Standard

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
  ```

  Imports to add (top of file, keep alphabetical with existing):
  - `import com.hermesandroid.relay.network.shared.HermesClients`
  - `import com.hermesandroid.relay.network.shared.TailnetBlockReason`
  - `import com.hermesandroid.relay.network.shared.TailnetRoutePolicy`

  B) Bind the Relay WSS client (T1) via HermesClients.

  ```OLD (exact)
    private fun buildClient(url: String? = null): OkHttpClient {
        okHttpClientFactory?.let { return it() }
        val builder = OkHttpClient.Builder()
            // OkHttp's 10s default connectTimeout is LAN-tuned; a Tailscale
            // DERP-relayed cold-start handshake can exceed it, and a failed
            // connect feeds the onFailure → markUnreachable → route-flap loop.
            // Give the remote first-handshake room to complete.
            .connectTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        // Swap in the current pin snapshot on every connect. We DON'T hold a
        // long-lived OkHttpClient with a stale pinner — otherwise a re-pair
        // that wipes a pin would still be subject to the pre-wipe rules.
        certPinStore?.let { store ->
            try {
                builder.certificatePinner(
                    url?.let(store::buildPinnerSnapshotFor) ?: store.buildPinnerSnapshot(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "CertificatePinner build failed: ${e.message}")
                builder.certificatePinner(CertificatePinner.DEFAULT)
            }
        }
        return builder.build()
    }
  ```
  ```NEW
    private fun buildClient(url: String? = null): OkHttpClient {
        okHttpClientFactory?.let { return it() }
        val builder = OkHttpClient.Builder()
            // OkHttp's 10s default connectTimeout is LAN-tuned; a Tailscale
            // DERP-relayed cold-start handshake can exceed it, and a failed
            // connect feeds the onFailure → markUnreachable → route-flap loop.
            // Give the remote first-handshake room to complete.
            .connectTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        // Swap in the current pin snapshot on every connect. We DON'T hold a
        // long-lived OkHttpClient with a stale pinner — otherwise a re-pair
        // that wipes a pin would still be subject to the pre-wipe rules.
        certPinStore?.let { store ->
            try {
                builder.certificatePinner(
                    url?.let(store::buildPinnerSnapshotFor) ?: store.buildPinnerSnapshot(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "CertificatePinner build failed: ${e.message}")
                builder.certificatePinner(CertificatePinner.DEFAULT)
            }
        }
        return HermesClients.build(builder)
    }
  ```

  C) Disable resolver-null → caller-supplied URL fallback while the tailnet policy is active (S3).

  ```OLD (exact)
            val resolvedRelayUrl = relayResolved?.relayWebSocketUrl()?.takeIf { it.isNotBlank() }
            val targetUrl = resolvedRelayUrl ?: url.takeIf { it.isNotBlank() }
  ```
  ```NEW
            val resolvedRelayUrl = relayResolved?.relayWebSocketUrl()?.takeIf { it.isNotBlank() }
            val targetUrl = resolvedRelayUrl ?: url.takeIf { it.isNotBlank() && !tailnetPolicyActive() }
  ```

  D) Apply tailnet-only filtering at the single resolver funnel and set/clear the tailnet block reason (S1 + part of S2).

  ```OLD (exact)
        val eligibleEndpoints = endpoints.filter(candidateFilter)
        if (eligibleEndpoints.isEmpty()) return null

        // Manual override: if the user pinned a role in the Endpoints card,
        // try that one first; fall through to the strict-priority algorithm
        // if it isn't reachable.
        _manualRoleOverride.value?.let { preferredRole ->
            val preferred = eligibleEndpoints.firstOrNull {
                it.role.equals(preferredRole, ignoreCase = true)
            }
            if (preferred != null) {
                // Single-element list still respects the 2s probe gate.
                val winner = resolver.resolve(listOf(preferred), surface)
                if (winner != null) return winner
                Log.i(TAG, "manualRoleOverride=$preferredRole not reachable — " +
                    "falling through to strict-priority resolve")
            }
        }

        return resolver.resolve(eligibleEndpoints, surface)
  ```
  ```NEW
        val policyOn = tailnetPolicyActive()
        val preFilter = endpoints.filter(candidateFilter)
        if (preFilter.isEmpty()) return null
        val eligibleEndpoints = if (policyOn) TailnetRoutePolicy.filter(preFilter) else preFilter
        if (eligibleEndpoints.isEmpty()) {
            if (policyOn && writesTailnetBlock(surface)) {
                _tailnetResolveBlock.value = TailnetBlockReason.PolicyNoRoute
            }
            return null
        }

        // Manual override: if the user pinned a role in the Endpoints card,
        // try that one first; fall through to the strict-priority algorithm
        // if it isn't reachable.
        _manualRoleOverride.value?.let { preferredRole ->
            val preferred = eligibleEndpoints.firstOrNull {
                it.role.equals(preferredRole, ignoreCase = true)
            }
            if (preferred != null) {
                // Single-element list still respects the 2s probe gate.
                val winner = resolver.resolve(listOf(preferred), surface)
                if (policyOn && writesTailnetBlock(surface)) {
                    _tailnetResolveBlock.value = null
                }
                if (winner != null) return winner
                Log.i(TAG, "manualRoleOverride=$preferredRole not reachable — " +
                    "falling through to strict-priority resolve")
            }
        }

        val winner = resolver.resolve(eligibleEndpoints, surface)
        if (policyOn && writesTailnetBlock(surface)) {
            _tailnetResolveBlock.value = if (winner == null) TailnetBlockReason.HostUnreachable else null
        }
        return winner
  ```

  E) Add the dial-time URL guard at the single dial choke point (S9).

  Insert immediately after the existing `ws://` guard return (i.e., after the `return` on the ws:// gate).

  ```OLD (exact)
        if (isInsecure && !_insecureMode.value) {
            Log.e(TAG, "Blocked ws:// connection — insecure mode is disabled. Use wss:// or enable insecure mode in Settings.")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Error,
                title = context?.getString(R.string.conn_diag_socket_blocked) ?: "Relay socket blocked",
                detail = "ws:// is disabled",
                operation = "Open Relay WebSocket",
                configuredUrl = url,
                suggestion = "Use wss:// or explicitly allow plain ws:// for a trusted LAN or VPN.",
            )
            return
        }
        if (isRelayRateLimitBackoffActive(
  ```
  ```NEW
        if (isInsecure && !_insecureMode.value) {
            Log.e(TAG, "Blocked ws:// connection — insecure mode is disabled. Use wss:// or enable insecure mode in Settings.")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Error,
                title = context?.getString(R.string.conn_diag_socket_blocked) ?: "Relay socket blocked",
                detail = "ws:// is disabled",
                operation = "Open Relay WebSocket",
                configuredUrl = url,
                suggestion = "Use wss:// or explicitly allow plain ws:// for a trusted LAN or VPN.",
            )
            return
        }

        tailnetUrlCheck(normalized)?.let { reason ->
            Log.e(TAG, "Blocked non-Tailscale relay socket — Always connect via Tailscale is on")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Error,
                title = context?.getString(R.string.tailnet_diag_blocked) ?: "Blocked: not a Tailscale route",
                detail = reason.name,
                operation = "Open Relay WebSocket",
                configuredUrl = url,
                requestUrl = normalized,
            )
            _tailnetResolveBlock.value = reason
            return
        }

        if (isRelayRateLimitBackoffActive(
  ```
- **Steps:**
  1. Open `ConnectionManager.kt`.
  2. Apply constructor-param edit A.
  3. Apply state-flow insertion A.
  4. Apply Relay WSS client binding edit B.
  5. Apply caller-URL fallback gating edit C.
  6. Apply resolver input filtering + block-reason updates edit D.
  7. Apply dial guard insertion edit E.
- **Verify:**
  - `cd <repo> && grep -n "tailnetPolicyActive" app/src/main/kotlin/com/hermesandroid/relay/network/relay/ConnectionManager.kt` → ≥1 hit
  - `cd <repo> && grep -n "TailnetRoutePolicy.filter" app/src/main/kotlin/com/hermesandroid/relay/network/relay/ConnectionManager.kt` → ≥1 hit
  - `cd <repo> && grep -n "HermesClients.build" app/src/main/kotlin/com/hermesandroid/relay/network/relay/ConnectionManager.kt` → ≥1 hit
- **Commit:** `feat(android): gate route selection and dial path under tailnet policy`

#### Task B3: Bind core Hermes-host OkHttp clients and wire ConnectionManager tailnet hooks
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt`
- **Depends on:** none
- **Estimate:** M
- **Create / modify:**
  Transport classification covered by this task:
  - T2 relayOkHttp = enforced (Bound)
  - T4 endpointProbeClient = enforced (Bound)
  - T8 pluginProxyClientForUrl base builder = enforced (Bound)
  - ConnectionManager tailnet hooks wiring = enforced

  Imports to add (top of file):
  - `import com.hermesandroid.relay.network.shared.HermesClients`
  - `import com.hermesandroid.relay.network.shared.HermesClients.enforceTailnetPolicy`
  - `import com.hermesandroid.relay.network.shared.TailnetEnforcer`

  A) Bind `endpointProbeClient` via HermesClients.

  ```OLD (exact)
    private val endpointProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()
  ```
  ```NEW
    private val endpointProbeClient: OkHttpClient = HermesClients.build(
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS),
    )
  ```

  B) Bind `relayOkHttp` via HermesClients.

  ```OLD (exact)
    private val relayOkHttp: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(2, TimeUnit.MINUTES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
  ```
  ```NEW
    private val relayOkHttp: OkHttpClient = HermesClients.build(
        OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.MINUTES)
            .connectTimeout(15, TimeUnit.SECONDS),
    )
  ```

  C) Wire ConnectionManager tailnet hooks (D1 C8 wiring).

  ```OLD (exact)
    private val connectionManager = ConnectionManager(
        multiplexer,
        authManager.certPinStore,
        reconnectGate = { authManager.hasPairContext },
        context = application,
        endpointResolver = endpointResolver,
        endpointCandidatesProvider = { activeRouteCandidatesSnapshot() },
        relayCandidateEligibility = { candidate ->
            relayCandidateEligibleForAuthenticatedDashboard(
                candidate = candidate,
                authenticatedDashboardOrigin = activeConnection.value
                    ?.authenticatedDashboardOrigin,
            )
        },
        proxyClientProvider = { url -> pluginProxyClientForUrl(url) },
        // Pull the active device id through AuthManager — it's the same id
        // PairingPreferences keys the endpoint list on. Nullable wrapper
        // because AuthManager.getOrCreateDeviceId() is suspending.
        deviceIdProvider = { runCatching { authManager.getOrCreateDeviceId() }.getOrNull() },
        dashboardRelayRequestProvider = { relayUrl ->
            dashboardRelayRequestForIngress(relayUrl)
        },
    )
  ```
  ```NEW
    private val connectionManager = ConnectionManager(
        multiplexer,
        authManager.certPinStore,
        reconnectGate = { authManager.hasPairContext },
        context = application,
        endpointResolver = endpointResolver,
        endpointCandidatesProvider = { activeRouteCandidatesSnapshot() },
        relayCandidateEligibility = { candidate ->
            relayCandidateEligibleForAuthenticatedDashboard(
                candidate = candidate,
                authenticatedDashboardOrigin = activeConnection.value
                    ?.authenticatedDashboardOrigin,
            )
        },
        proxyClientProvider = { url -> pluginProxyClientForUrl(url) },
        // Pull the active device id through AuthManager — it's the same id
        // PairingPreferences keys the endpoint list on. Nullable wrapper
        // because AuthManager.getOrCreateDeviceId() is suspending.
        deviceIdProvider = { runCatching { authManager.getOrCreateDeviceId() }.getOrNull() },
        dashboardRelayRequestProvider = { relayUrl ->
            dashboardRelayRequestForIngress(relayUrl)
        },
        tailnetPolicyActive = { TailnetEnforcer.get().isEnforcing() },
        tailnetUrlCheck = { TailnetEnforcer.get().checkUrl(it) },
    )
  ```

  D) Ensure plugin-proxy client construction is decorated (T8).

  ```OLD (exact)
        val configuredBuilder = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
  ```
  ```NEW
        val configuredBuilder = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .enforceTailnetPolicy()
  ```
- **Steps:**
  1. Open `ConnectionViewModel.kt`.
  2. Add imports.
  3. Apply edits A–D exactly.
- **Verify:**
  - `cd <repo> && grep -n "HermesClients.build" app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → ≥2 hits (probe + relayOkHttp)
  - `cd <repo> && grep -n "tailnetPolicyActive" app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → ≥1 hit (ConnectionManager ctor)
- **Commit:** `feat(android): bind core okhttp clients and wire tailnet hooks`

#### Task B4: Prevent saved-URL downgrades for HTTP surfaces while tailnet policy is ON (S4 + S12 writer gate)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt`
- **Depends on:** Task B3
- **Estimate:** L
- **Create / modify:**
  Transport classification covered by this task:
  - S4 effective URL resolvers = enforced (blank-out non-tailnet when tailnetOnly)
  - S12 authenticatedDashboardOrigin writer gate = URL-gated (never persist non-tailnet origin while ON)

  Imports to add:
  - `import com.hermesandroid.relay.network.shared.TailnetAddresses`

  A) Add `tailnetOnly` parameter + post-filter to the three effective-URL resolvers.

  1) Relay URL:

  ```OLD (exact)
internal fun resolveEffectiveRelayUrl(
    savedRelayUrl: String,
    savedApiUrl: String,
    activeRelayEndpoint: EndpointCandidate?,
    relayConfigured: Boolean,
): String {
    if (!relayConfigured) return ""
    activeRelayEndpoint?.pluginProxyRoutesOrNull()?.relayWebSocketUrl?.let { return it }
    activeRelayEndpoint?.relay?.url?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    return if (RelayUrlDeriver.isAutoManagedRelayUrl(savedRelayUrl, savedApiUrl)) {
        RelayUrlDeriver.deriveFromApiUrl(savedApiUrl) ?: savedRelayUrl
    } else {
        savedRelayUrl
    }
}
  ```
  ```NEW
internal fun resolveEffectiveRelayUrl(
    savedRelayUrl: String,
    savedApiUrl: String,
    activeRelayEndpoint: EndpointCandidate?,
    relayConfigured: Boolean,
    tailnetOnly: Boolean = false,
): String {
    if (!relayConfigured) return ""
    val r = activeRelayEndpoint?.pluginProxyRoutesOrNull()?.relayWebSocketUrl
        ?: activeRelayEndpoint?.relay?.url?.trim()?.takeIf(String::isNotBlank)
        ?: if (RelayUrlDeriver.isAutoManagedRelayUrl(savedRelayUrl, savedApiUrl)) {
            RelayUrlDeriver.deriveFromApiUrl(savedApiUrl) ?: savedRelayUrl
        } else {
            savedRelayUrl
        }
    return if (tailnetOnly && !TailnetAddresses.isTailnetUrl(r)) "" else r
}
  ```

  2) Dashboard URL:

  ```OLD (exact)
internal fun resolveEffectiveDashboardUrl(
    connection: Connection?,
    endpoint: EndpointCandidate?,
): String {
    if (connection == null) return ""
    connection.authenticatedDashboardOrigin
        ?.let(::normalizeCredentialFreeAuthenticatedDashboardOrigin)
        ?.let { return it }
    // The resolver publishes independently of the active connection. During a
    // switch its last winner can still belong to the outgoing installation.
    // Never use that winner as authority for the incoming connection's bearer.
    val routes = connection.routeCandidates.ifEmpty {
        Connection.buildRouteCandidates(
            apiServerUrl = connection.apiServerUrl,
            relayUrl = connection.relayUrl,
            dashboardUrl = connection.configuredDashboardUrl,
        )
    }
    val ownedEndpoint = endpoint?.takeIf { it in routes }
    ownedEndpoint?.pluginProxyRoutesOrNull()?.dashboardBaseUrl?.let { return it }
    ownedEndpoint?.dashboard?.url
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    ownedEndpoint?.api?.url?.let { apiUrl ->
        connection.dashboardUrl
            ?.takeIf { it.isNotBlank() && Connection.urlsShareHost(it, apiUrl) }
            ?.let { return it }
        Connection.deriveDefaultDashboardUrl(apiUrl)?.let { return it }
    }
    return connection.resolvedDashboardUrl
}
  ```
  ```NEW
internal fun resolveEffectiveDashboardUrl(
    connection: Connection?,
    endpoint: EndpointCandidate?,
    tailnetOnly: Boolean = false,
): String {
    if (connection == null) return ""
    val r = connection.authenticatedDashboardOrigin
        ?.let(::normalizeCredentialFreeAuthenticatedDashboardOrigin)
        ?: run {
            // The resolver publishes independently of the active connection. During a
            // switch its last winner can still belong to the outgoing installation.
            // Never use that winner as authority for the incoming connection's bearer.
            val routes = connection.routeCandidates.ifEmpty {
                Connection.buildRouteCandidates(
                    apiServerUrl = connection.apiServerUrl,
                    relayUrl = connection.relayUrl,
                    dashboardUrl = connection.configuredDashboardUrl,
                )
            }
            val ownedEndpoint = endpoint?.takeIf { it in routes }
            ownedEndpoint?.pluginProxyRoutesOrNull()?.dashboardBaseUrl
                ?: ownedEndpoint?.dashboard?.url
                    ?.takeIf { it.isNotBlank() }
                ?: ownedEndpoint?.api?.url?.let { apiUrl ->
                    connection.dashboardUrl
                        ?.takeIf { it.isNotBlank() && Connection.urlsShareHost(it, apiUrl) }
                        ?: Connection.deriveDefaultDashboardUrl(apiUrl)
                }
                ?: connection.resolvedDashboardUrl
        }
    return if (tailnetOnly && !TailnetAddresses.isTailnetUrl(r)) "" else r
}
  ```

  3) API URL:

  ```OLD (exact)
internal fun resolveEffectiveApiServerUrl(
    savedUrl: String,
    endpoint: EndpointCandidate?,
): String {
    if (savedUrl.isBlank()) return ""
    endpoint?.pluginProxyRoutesOrNull()?.apiBaseUrl?.let { return it }
    return endpoint?.api?.url?.takeIf { it.isNotBlank() } ?: savedUrl
}
  ```
  ```NEW
internal fun resolveEffectiveApiServerUrl(
    savedUrl: String,
    endpoint: EndpointCandidate?,
    tailnetOnly: Boolean = false,
): String {
    if (savedUrl.isBlank()) return ""
    val r = endpoint?.pluginProxyRoutesOrNull()?.apiBaseUrl
        ?: endpoint?.api?.url?.takeIf { it.isNotBlank() }
        ?: savedUrl
    return if (tailnetOnly && !TailnetAddresses.isTailnetUrl(r)) "" else r
}
  ```

  B) Pass the active connection's `alwaysViaTailscale` into the effective URL flows.

  1) effectiveApiServerUrl:

  ```OLD (exact)
    val effectiveApiServerUrl: StateFlow<String> = combine(
        _apiServerUrl,
        connectionManager.activeApiEndpoint,
    ) { savedUrl, endpoint ->
        resolveEffectiveApiServerUrl(savedUrl, endpoint)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
  ```
  ```NEW
    val effectiveApiServerUrl: StateFlow<String> = combine(
        _apiServerUrl,
        connectionManager.activeApiEndpoint,
        activeConnection,
    ) { savedUrl, endpoint, connection ->
        resolveEffectiveApiServerUrl(savedUrl, endpoint, tailnetOnly = connection?.alwaysViaTailscale == true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
  ```

  2) effectiveRelayUrl:

  ```OLD (exact)
    val effectiveRelayUrl: StateFlow<String> = combine(
        _relayUrl,
        _apiServerUrl,
        connectionManager.activeRelayEndpoint,
        relayConfigured,
    ) { savedRelayUrl, savedApiUrl, endpoint, configured ->
        resolveEffectiveRelayUrl(savedRelayUrl, savedApiUrl, endpoint, configured)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
  ```
  ```NEW
    val effectiveRelayUrl: StateFlow<String> = combine(
        _relayUrl,
        _apiServerUrl,
        connectionManager.activeRelayEndpoint,
        relayConfigured,
        activeConnection,
    ) { savedRelayUrl, savedApiUrl, endpoint, configured, connection ->
        resolveEffectiveRelayUrl(
            savedRelayUrl,
            savedApiUrl,
            endpoint,
            configured,
            tailnetOnly = connection?.alwaysViaTailscale == true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
  ```

  3) effectiveDashboardUrl:

  ```OLD (exact)
    val effectiveDashboardUrl: StateFlow<String> = combine(
        activeConnection,
        connectionManager.activeEndpoint,
    ) { connection, endpoint ->
        resolveEffectiveDashboardUrl(connection, endpoint)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
  ```
  ```NEW
    val effectiveDashboardUrl: StateFlow<String> = combine(
        activeConnection,
        connectionManager.activeEndpoint,
    ) { connection, endpoint ->
        resolveEffectiveDashboardUrl(connection, endpoint, tailnetOnly = connection?.alwaysViaTailscale == true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
  ```

  C) Gate authenticated dashboard origin persistence while the policy is ON (S12).

  ```OLD (exact)
internal suspend fun persistAuthenticatedDashboardOriginWithRollback(
    previous: Connection,
    normalizedOrigin: String,
    persist: suspend (Connection) -> Unit,
    activated: suspend () -> Boolean,
): Boolean {
    val promoted = withAuthenticatedDashboardOrigin(previous, normalizedOrigin)
    var persisted = false
    val success = try {
        persist(promoted)
        persisted = true
        activated()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (persisted) persist(previous)
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!success && persisted) persist(previous)
    return success
}
  ```
  ```NEW
internal suspend fun persistAuthenticatedDashboardOriginWithRollback(
    previous: Connection,
    normalizedOrigin: String,
    persist: suspend (Connection) -> Unit,
    activated: suspend () -> Boolean,
): Boolean {
    if (previous.alwaysViaTailscale && !TailnetAddresses.isTailnetUrl(normalizedOrigin)) {
        return false
    }
    val promoted = withAuthenticatedDashboardOrigin(previous, normalizedOrigin)
    var persisted = false
    val success = try {
        persist(promoted)
        persisted = true
        activated()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (persisted) persist(previous)
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!success && persisted) persist(previous)
    return success
}
  ```
- **Steps:**
  1. Open `ConnectionViewModel.kt`.
  2. Add the TailnetAddresses import.
  3. Apply resolver edits A1–A3.
  4. Apply effective-flow edits B1–B3.
  5. Apply persistence gate edit C.
- **Verify:**
  - `cd <repo> && grep -n "tailnetOnly" app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → ≥5 hits
  - `cd <repo> && grep -n "persistAuthenticatedDashboardOriginWithRollback" -n app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → ≥1 hit
- **Commit:** `feat(android): blank out non-tailnet effective urls under tailnet policy`

#### Task B5: Wire TailnetEnforcer policy activation + block non-eligible manual route writes (S10)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt`
- **Depends on:** Task B4
- **Estimate:** L
- **Create / modify:**
  Transport classification covered by this task:
  - Policy-on route selection uses TailnetEnforcer.isEnforcing (enforced)
  - S10 route preference writers are gated (enforced)

  Imports to add:
  - `import com.hermesandroid.relay.network.shared.TailnetRoutePolicy`

  A) Whenever the active connection changes, set the TailnetEnforcer active policy BEFORE restoring overrides / rebuilding transports.

  1) In `restorePersistedActiveConnectionContext(connection)`:

  ```OLD (exact)
        _apiServerUrl.value = connection.apiServerUrl
        _relayUrl.value = restoredRelayUrl
        connectionManager.setManualRoleOverride(connection.preferredRouteRole)
  ```
  ```NEW
        _apiServerUrl.value = connection.apiServerUrl
        _relayUrl.value = restoredRelayUrl
        TailnetEnforcer.get().setPolicy(connection.id, connection.alwaysViaTailscale)
        connectionManager.setManualRoleOverride(connection.preferredRouteRole)
  ```

  2) In the `activeConnectionId.collect { connectionId -> ... }` block:

  ```OLD (exact)
                val connection = connectionId?.let { cid ->
                    connectionStore.connections.value.firstOrNull { it.id == cid }
                }
                connectionManager.setManualRoleOverride(connection?.preferredRouteRole)
  ```
  ```NEW
                val connection = connectionId?.let { cid ->
                    connectionStore.connections.value.firstOrNull { it.id == cid }
                }
                TailnetEnforcer.get().setPolicy(connection?.id, connection?.alwaysViaTailscale == true)
                connectionManager.setManualRoleOverride(connection?.preferredRouteRole)
  ```

  3) In `activateCommittedDraftContext(connection)`:

  ```OLD (exact)
        _apiServerUrl.value = connection.apiServerUrl
        _relayUrl.value = connection.relayUrl
        connectionManager.setManualRoleOverride(connection.preferredRouteRole)
  ```
  ```NEW
        _apiServerUrl.value = connection.apiServerUrl
        _relayUrl.value = connection.relayUrl
        TailnetEnforcer.get().setPolicy(connection.id, connection.alwaysViaTailscale)
        connectionManager.setManualRoleOverride(connection.preferredRouteRole)
  ```

  B) Add a one-shot UI event for a blocked route preference write (used by slice C).

  Insert near the other one-shot event flows (close to `_avatarEvents`).

  ```NEW
    private val _tailnetRouteBlockedEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val tailnetRouteBlockedEvents: SharedFlow<Int> = _tailnetRouteBlockedEvents.asSharedFlow()
  ```

  C) Gate `setPreferredEndpointRole` and `useRouteNow` when the active connection is tailnet-only (S10).

  ```OLD (exact)
    fun setPreferredEndpointRole(role: String?) {
        connectionManager.setManualRoleOverride(role)
        viewModelScope.launch {
            val activeId = connectionStore.activeConnectionId.value ?: return@launch
            val current = connectionStore.connections.value.firstOrNull { it.id == activeId }
                ?: return@launch
            connectionStore.updateConnection(
                current.copy(preferredRouteRole = role?.takeIf { it.isNotBlank() }),
            )
        }
        probeNow()
    }
  ```
  ```NEW
    fun setPreferredEndpointRole(role: String?) {
        val connection = activeConnection.value
        if (connection?.alwaysViaTailscale == true && role != null) {
            val eligible = connection.routeCandidates.any {
                it.role.equals(role, ignoreCase = true) && TailnetRoutePolicy.isEligible(it)
            }
            if (!eligible) {
                _tailnetRouteBlockedEvents.tryEmit(R.string.tailnet_route_blocked_toast)
                return
            }
        }
        connectionManager.setManualRoleOverride(role)
        viewModelScope.launch {
            val activeId = connectionStore.activeConnectionId.value ?: return@launch
            val current = connectionStore.connections.value.firstOrNull { it.id == activeId }
                ?: return@launch
            connectionStore.updateConnection(
                current.copy(preferredRouteRole = role?.takeIf { it.isNotBlank() }),
            )
        }
        probeNow()
    }
  ```

  ```OLD (exact)
    fun useRouteNow(role: String?) {
        connectionManager.setManualRoleOverride(
            role ?: activeConnection.value?.preferredRouteRole,
        )
        probeNow()
    }
  ```
  ```NEW
    fun useRouteNow(role: String?) {
        val connection = activeConnection.value
        if (connection?.alwaysViaTailscale == true && role != null) {
            val eligible = connection.routeCandidates.any {
                it.role.equals(role, ignoreCase = true) && TailnetRoutePolicy.isEligible(it)
            }
            if (!eligible) {
                _tailnetRouteBlockedEvents.tryEmit(R.string.tailnet_route_blocked_toast)
                return
            }
        }
        connectionManager.setManualRoleOverride(
            role ?: activeConnection.value?.preferredRouteRole,
        )
        probeNow()
    }
  ```

  NOTE: This task assumes `Connection.alwaysViaTailscale` exists (contract C1; owned outside slice B).
- **Steps:**
  1. Open `ConnectionViewModel.kt`.
  2. Apply the TailnetEnforcer calls in A1–A3.
  3. Add the `_tailnetRouteBlockedEvents` flow in B.
  4. Apply gating edits C.
- **Verify:**
  - `cd <repo> && grep -n "tailnetRouteBlockedEvents" app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → ≥1 hit
  - `cd <repo> && grep -n "setPolicy" app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → ≥3 hits
- **Commit:** `feat(android): wire tailnet policy activation and gate route preference writers`

#### Task B6: Bind Dashboard REST + native PKCE base clients (T9/T13) via HermesClients
- **Files (owns):**
  - `app/src/main/kotlin/com/hermesandroid/relay/network/upstream/DashboardApiClient.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/connection/UpstreamTransportController.kt`
- **Depends on:** none
- **Estimate:** S
- **Create / modify:**
  Transport classification covered by this task:
  - T9 DashboardApiClient.defaultClient = enforced (Bound)
  - T13 NativeDashboardAuthClient base builder in UpstreamTransportController = enforced (Bound)

  A) DashboardApiClient.defaultClient(): return a HermesClients-built client.

  ```OLD (exact)
            bearerAuth?.let {
                it.preferCookiesWhen(cookieJar::hasCookiesFor)
                builder.addInterceptor(it)
                builder.authenticator(it)
            }
            return builder.build()
  ```
  ```NEW
            bearerAuth?.let {
                it.preferCookiesWhen(cookieJar::hasCookiesFor)
                builder.addInterceptor(it)
                builder.authenticator(it)
            }
            return com.hermesandroid.relay.network.shared.HermesClients.build(builder)
  ```

  (Use the fully-qualified reference as shown to avoid import churn in this file.)

  B) UpstreamTransportController.nativeDashboardAuthClientForActive(): build the base client via HermesClients.

  ```OLD (exact)
        val base = okhttp3.OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
  ```
  ```NEW
        val base = com.hermesandroid.relay.network.shared.HermesClients.build(
            okhttp3.OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS),
        )
  ```
- **Steps:**
  1. Apply edit A in `DashboardApiClient.kt`.
  2. Apply edit B in `UpstreamTransportController.kt`.
- **Verify:**
  - `cd <repo> && grep -n "HermesClients.build" app/src/main/kotlin/com/hermesandroid/relay/network/upstream/DashboardApiClient.kt app/src/main/kotlin/com/hermesandroid/relay/viewmodel/connection/UpstreamTransportController.kt` → ≥2 hits
- **Commit:** `feat(android): bind dashboard clients via hermesclients`

#### Task B7: Bind HermesApiClient + GatewayChatClient (T7/T11) via HermesClients
- **Files (owns):**
  - `app/src/main/kotlin/com/hermesandroid/relay/network/upstream/HermesApiClient.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/network/upstream/GatewayChatClient.kt`
- **Depends on:** none
- **Estimate:** S
- **Create / modify:**
  Transport classification covered by this task:
  - T7 HermesApiClient default OkHttp client = enforced (Bound)
  - T11 GatewayChatClient underlying WebSocket client = enforced (Bound)

  A) HermesApiClient: build the default client via HermesClients.

  ```OLD (exact)
    private val client: OkHttpClient = httpClient ?: okHttpClient ?: OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
  ```
  ```NEW
    private val client: OkHttpClient = httpClient ?: okHttpClient ?: HermesClients.build(
        OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.MINUTES)
            .connectTimeout(10, TimeUnit.SECONDS),
    )
  ```

  Imports to add:
  - `import com.hermesandroid.relay.network.shared.HermesClients`

  B) GatewayChatClient: build the WebSocket client via HermesClients.

  ```OLD (exact)
    private val client: OkHttpClient = (okHttpClient ?: OkHttpClient())
        .newBuilder()
        // The 10s default connectTimeout is LAN-tuned; a remote dashboard
        // reached over Tailscale (DERP cold start) can take longer to complete
        // the WS upgrade. A failed connect leaves Android on its Gateway owner and a
        // 5s cooldown, so give the first remote handshake room.
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
  ```
  ```NEW
    private val client: OkHttpClient = HermesClients.build(
        (okHttpClient ?: OkHttpClient())
            .newBuilder()
            // The 10s default connectTimeout is LAN-tuned; a remote dashboard
            // reached over Tailscale (DERP cold start) can take longer to complete
            // the WS upgrade. A failed connect leaves Android on its Gateway owner and a
            // 5s cooldown, so give the first remote handshake room.
            .connectTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS),
    )
  ```

  Imports to add:
  - `import com.hermesandroid.relay.network.shared.HermesClients`
- **Steps:**
  1. Apply edit A in `HermesApiClient.kt`.
  2. Apply edit B in `GatewayChatClient.kt`.
- **Verify:**
  - `cd <repo> && grep -n "HermesClients.build" app/src/main/kotlin/com/hermesandroid/relay/network/upstream/HermesApiClient.kt app/src/main/kotlin/com/hermesandroid/relay/network/upstream/GatewayChatClient.kt` → ≥2 hits
- **Commit:** `feat(android): bind api and gateway clients via hermesclients`

#### Task B8: Bind RelayVoiceClient + NativeDashboardAuth client (T15a/T13) via HermesClients
- **Files (owns):**
  - `app/src/main/kotlin/com/hermesandroid/relay/runtime/HermesRuntimeBinder.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/network/upstream/NativeDashboardAuth.kt`
- **Depends on:** none
- **Estimate:** S
- **Create / modify:**
  Transport classification covered by this task:
  - T15a RelayVoiceClient OkHttp client = enforced (Bound)
  - T13 NativeDashboardAuthClient OkHttp client = enforced (Bound, DNS fallback preserved)

  A) HermesRuntimeBinder: build RelayVoiceClient okHttpClient via HermesClients.

  ```OLD (exact)
        relayVoiceClient = RelayVoiceClient(
            context = application,
            okHttpClient = OkHttpClient.Builder()
                .readTimeout(2, TimeUnit.MINUTES)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build(),
            relayUrlProvider = { connection.effectiveRelayUrl.value },
  ```
  ```NEW
        relayVoiceClient = RelayVoiceClient(
            context = application,
            okHttpClient = HermesClients.build(
                OkHttpClient.Builder()
                    .readTimeout(2, TimeUnit.MINUTES)
                    .connectTimeout(15, TimeUnit.SECONDS),
            ),
            relayUrlProvider = { connection.effectiveRelayUrl.value },
  ```

  Imports to add:
  - `import com.hermesandroid.relay.network.shared.HermesClients`

  B) NativeDashboardAuthClient: replace the inline builder with a HermesClients-built client, using RetryingNativeAuthDns as the dnsFallback.

  ```OLD (exact)
class NativeDashboardAuthClient(
    baseUrl: String,
    private val tokenStore: NativeDashboardTokenStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(RetryingNativeAuthDns())
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
) {
  ```
  ```NEW
class NativeDashboardAuthClient(
    baseUrl: String,
    private val tokenStore: NativeDashboardTokenStore,
    private val client: OkHttpClient = HermesClients.build(
        builder = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS),
        dnsFallback = RetryingNativeAuthDns(),
    ),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
) {
  ```

  Imports to add:
  - `import com.hermesandroid.relay.network.shared.HermesClients`
- **Steps:**
  1. Apply edit A in `HermesRuntimeBinder.kt`.
  2. Apply edit B in `NativeDashboardAuth.kt`.
- **Verify:**
  - `cd <repo> && grep -n "HermesClients.build" app/src/main/kotlin/com/hermesandroid/relay/runtime/HermesRuntimeBinder.kt app/src/main/kotlin/com/hermesandroid/relay/network/upstream/NativeDashboardAuth.kt` → ≥2 hits
- **Commit:** `feat(android): bind voice and native auth clients via hermesclients`

#### Task B9: Split MediaSaver remote fetches by authority (T23) and bind the Hermes-host path
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/util/MediaSaver.kt`
- **Depends on:** none
- **Estimate:** M
- **Create / modify:**
  Transport classification covered by this task:
  - T23 MediaSaver.fetchRemoteBytes = split by authority

  Imports to add:
  - `import com.hermesandroid.relay.network.shared.HermesClients`
  - `import com.hermesandroid.relay.network.shared.TailnetAddresses`
  - `import java.net.URI`

  Replace the single `httpClient` with a plain client + a hermes-bound client, and add a provider for the active Hermes authorities.

  ```OLD (exact)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
  ```
  ```NEW
    @Volatile
    private var hermesRouteAuthoritiesProvider: (() -> Set<String>)? = null

    fun setHermesRouteAuthoritiesProvider(provider: (() -> Set<String>)?) {
        hermesRouteAuthoritiesProvider = provider
    }

    fun hermesRouteAuthoritiesSnapshot(): Set<String> =
        hermesRouteAuthoritiesProvider?.invoke().orEmpty()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val hermesClient: OkHttpClient by lazy {
        HermesClients.build(
            OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS),
        )
    }

    private fun authorityOf(rawUrl: String): String? {
        val httpUrl = when {
            rawUrl.startsWith("ws://", ignoreCase = true) -> "http://${rawUrl.substringAfter("://")}" 
            rawUrl.startsWith("wss://", ignoreCase = true) -> "https://${rawUrl.substringAfter("://")}" 
            else -> rawUrl
        }
        val uri = runCatching { URI(httpUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return "$host:$port"
    }

    private fun shouldUseHermesClient(url: String): Boolean {
        if (TailnetAddresses.isTailnetUrl(url)) return true
        val authority = authorityOf(url) ?: return false
        return authority in hermesRouteAuthoritiesSnapshot()
    }
  ```

  Then select the client per request.

  ```OLD (exact)
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { resp ->
  ```
  ```NEW
            val request = Request.Builder().url(url).get().build()
            val client = if (shouldUseHermesClient(url)) hermesClient else httpClient
            client.newCall(request).execute().use { resp ->
  ```

  NOTE: Slice B wires the provider from ConnectionViewModel in Task B5 (or slice C may wire it if preferred).
- **Steps:**
  1. Open `MediaSaver.kt`.
  2. Apply the httpClient replacement edit.
  3. Apply the fetchRemoteBytes client-selection edit.
- **Verify:**
  - `cd <repo> && grep -n "setHermesRouteAuthoritiesProvider" app/src/main/kotlin/com/hermesandroid/relay/util/MediaSaver.kt` → ≥1 hit
- **Commit:** `feat(android): split mediasaver fetches by authority under tailnet policy`

#### Task B10: Split Coil image loading by authority (T24) and bind the Hermes-host path
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/HermesRelayApp.kt`
- **Depends on:** none
- **Estimate:** M
- **Create / modify:**
  Transport classification covered by this task:
  - T24 Coil ImageLoader = split by authority

  Imports to add:
  - `import coil3.network.okhttp.OkHttpNetworkFetcherFactory`
  - `import com.hermesandroid.relay.network.shared.HermesClients`
  - `import com.hermesandroid.relay.network.shared.TailnetAddresses`
  - `import com.hermesandroid.relay.network.shared.TailnetEnforcer`
  - `import com.hermesandroid.relay.util.MediaSaver`
  - `import okhttp3.Call`
  - `import okhttp3.OkHttpClient`
  - `import okhttp3.Request`

  A) Replace the Coil fetcher registration with a callFactory-dispatching one.

  ```OLD (exact)
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
  ```
  ```NEW
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { HermesAwareCallFactory() },
                    ),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
  ```

  B) Add a private Call.Factory implementation inside HermesRelayApp.

  ```NEW
    private class HermesAwareCallFactory : Call.Factory {
        private val plainClient: OkHttpClient by lazy {
            OkHttpClient.Builder().build()
        }

        private val hermesClient: OkHttpClient by lazy {
            HermesClients.build(OkHttpClient.Builder())
        }

        override fun newCall(request: Request): Call {
            val authority = "${request.url.host.lowercase()}:${request.url.port}"
            val useHermes = TailnetAddresses.isTailnetHost(request.url.host) ||
                authority in MediaSaver.hermesRouteAuthoritiesSnapshot()
            return (if (useHermes) hermesClient else plainClient).newCall(request)
        }
    }
  ```

  C) Initialize TailnetEnforcer from the application main process (D1 C6).

  Insert this inside `onCreate()`, after `AppForegroundTracker.initialize()` and guarded by `isMainApplicationProcess()`.

  ```NEW
        if (isMainApplicationProcess()) {
            TailnetEnforcer.initialize(this)
        }
  ```
- **Steps:**
  1. Open `HermesRelayApp.kt`.
  2. Apply edit A.
  3. Add HermesAwareCallFactory per edit B.
  4. Add TailnetEnforcer initialization per edit C.
- **Verify:**
  - `cd <repo> && grep -n "HermesAwareCallFactory" app/src/main/kotlin/com/hermesandroid/relay/HermesRelayApp.kt` → ≥1 hit
  - `cd <repo> && grep -n "TailnetEnforcer.initialize" app/src/main/kotlin/com/hermesandroid/relay/HermesRelayApp.kt` → ≥1 hit
- **Commit:** `feat(android): split coil call factory by authority and init tailnet enforcer`

#### Task B11: URL-gate WebView + Custom Tabs entry points (T25/T26)
- **Files (owns):**
  - `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/DashboardSignInScreen.kt`
  - `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/NativeDashboardBrowserLauncher.kt`
- **Depends on:** Task B2
- **Estimate:** M
- **Create / modify:**
  Transport classification covered by this task:
  - T25 WebView = URL-gated
  - T26 Custom Tabs = URL-gated

  Imports to add in both files as needed:
  - `import com.hermesandroid.relay.network.shared.TailnetEnforcer`

  A) DashboardSignInScreen WebView: block initial `loadUrl(loginUrl)` when checkUrl rejects it.

  ```OLD (exact)
                        webView = this
                        loadUrl(loginUrl)
  ```
  ```NEW
                        webView = this
                        val reason = TailnetEnforcer.get().checkUrl(loginUrl)
                        if (reason != null) {
                            val message = resources.getString(R.string.tailnet_diag_blocked)
                            statusText = message
                            onError(message)
                        } else {
                            loadUrl(loginUrl)
                        }
  ```

  B) NativeDashboardBrowserLauncher: block launching a non-tailnet dashboard authorization URL while enforcing.

  ```OLD (exact)
internal fun launchNativeDashboardAuthorization(
    context: Context,
    authorizationUrl: String,
) {
    val uri = Uri.parse(authorizationUrl)
  ```
  ```NEW
internal fun launchNativeDashboardAuthorization(
    context: Context,
    authorizationUrl: String,
) {
    TailnetEnforcer.get().checkUrl(authorizationUrl)?.let {
        // URL-gated entry point; UI surface (slice C) renders the blocked card.
        return
    }
    val uri = Uri.parse(authorizationUrl)
  ```

  NOTE: This is intentionally a hard gate and must not silently fall back to a LAN URL while policy is ON.
- **Steps:**
  1. Apply edit A in `DashboardSignInScreen.kt`.
  2. Apply edit B in `NativeDashboardBrowserLauncher.kt`.
- **Verify:**
  - `cd <repo> && grep -n "checkUrl" app/src/main/kotlin/com/hermesandroid/relay/ui/screens/DashboardSignInScreen.kt app/src/main/kotlin/com/hermesandroid/relay/ui/screens/NativeDashboardBrowserLauncher.kt` → ≥2 hits
- **Commit:** `feat(android): url-gate dashboard webview and custom tabs under tailnet policy`

## Slice B open questions
1. FILE_OWNERSHIP mismatch: D1 §3.2 S11 mentions `ConnectionsSettingsScreen.kt:556-562` (display-only second picker) and S10 mentions EndpointsCard row/menu hiding; those files are owned by slice C per FILE_OWNERSHIP.md row C. Slice B implements the writer-side gating in ConnectionViewModel (Task B5), but slice C must implement the UI hiding + display-only picker fix.
2. D1 T25 also references `DashboardManagementScreen.kt` WebView URL gating; FILE_OWNERSHIP row B does not list it. If it exists and contains `loadUrl(...)` for dashboard management, it must be gated the same way as DashboardSignInScreen.
3. Contract C1 (`Connection.alwaysViaTailscale: Boolean`) lives in `ConnectionData.kt` which is not listed under any slice in FILE_OWNERSHIP.md. An owner must be assigned (likely slice C or D) so the field exists before slice B compiles.

## Slice B self-check
- Every Hermes-host OkHttp client construction site owned by slice B is either:
  - Bound (HermesClients.build / enforceTailnetPolicy), OR
  - Split by authority (MediaSaver / Coil), OR
  - URL-gated (WebView / Custom Tabs).
- Silent downgrade sites implemented in slice-B-owned files: S1 (ConnectionManager resolve funnel), S3 (caller-url fallback suppression), S4 (effective URL blank-out), S9 (dial-time guard), S10 (writer-side gating).
- All edits are mechanical, minimal, and avoid unrelated reformat.


---

## Slice C — UI and i18n

Worktree: `<repo>` (branch `feat/android-tailscale-always-on`, base `e5989426`).
Every OLD block below was checked against the base commit to occur EXACTLY ONCE in its file. Every task was also applied to a throwaway copy of the base tree (`git archive e5989426`): the locale gate passed and the six new `source_sha256` values were computed from that result (Task C8).

Where it sits (owner question): **Settings → Always connect via Tailscale**, the row directly UNDER **Settings → Gateways** (`SettingsScreen.kt:572-578`), opening a new subpage `TailscaleSettingsScreen` (route `settings/tailscale`). The subpage holds the ONLY switch. **Connection detail → Routes tab** gets a read-only status row (no switch) that opens the same subpage.

## Slice C contracts consumed

- **MERGE C8 (UI contract), used verbatim:** `sealed interface TailscaleAlwaysConnectUiState { data object Off; data class On(val status: TailscaleAlwaysConnectStatus) }`, `enum class TailscaleAlwaysConnectStatus { Healthy, TailscaleAppMissing, TailnetUnavailable, NoTailscaleRouteConfigured, HostNotOnTailnet, HostUnreachableOverTailnet, ConnectedViaNonTailnet }`, `@Composable fun TailscaleAlwaysConnectCard(uiState, onSetEnabled, onOpenTailscale, onInstallTailscale, onManageRoutes, onTurnOffAndRetry)`. All three live in the NEW file `app/src/main/kotlin/com/hermesandroid/relay/ui/components/TailscaleAlwaysConnectCard.kt`, package `com.hermesandroid.relay.ui.components`. **Slice C DEFINES these types (Task C9); slice B's ViewModel imports them from there.**
- **ViewModel seams, provided by slice B (`app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt`):**
  `val tailscaleAlwaysConnectUiState: StateFlow<TailscaleAlwaysConnectUiState>` and `fun setTailscaleAlwaysConnectEnabled(enabled: Boolean)` (acts on the ACTIVE connection). Used by Tasks C11 and C14.
- **Existing ViewModel members, already at base** (verified): `activeConnection: StateFlow<Connection?>` (`ConnectionViewModel.kt:1111`), `activeEndpoint` (`:1438`, a `StateFlow<EndpointCandidate?>`), `observeDeviceEndpoints()` (`:7703`), `saveExtraRoute(role, dashboardUrl, original, onResult)` (`:7739-7744`), `RouteProbeStatus` with `Probing` (`:7963-7970`), `routeProbeStatus` (`:7973`), `probeNow()` (`:8003`).
- **MERGE C1:** `Connection.alwaysViaTailscale: Boolean = false` in `data/ConnectionData.kt`. Used by Task C17 only.
- **MERGE C3 / slice A:** `object TailnetRoutePolicy { fun isEligible(candidate: EndpointCandidate): Boolean }`, package **`com.hermesandroid.relay.network.shared`** (this is the path in FILE_OWNERSHIP.md row A; see open question 1). Used by Tasks C16 and C17.
- **MERGE C9 strings** (15 keys) + **FROZEN_STRINGS.md** (4 `tailnet_*` keys, English from D1 §4.6) + **16 slice-C keys** (appended to FROZEN_STRINGS.md): `settings_tailscale_desc`, `settings_tailscale_intro`, `settings_tailscale_no_active_connection`, `settings_tailscale_applies_to`, `settings_tailscale_status_connected`, `settings_tailscale_only_this_app_title`, `settings_tailscale_only_this_app_body`, `settings_tailscale_step_install`, `settings_tailscale_step_split`, `settings_tailscale_step_select`, `settings_tailscale_step_verify`, `settings_tailscale_other_apps_note`, `settings_tailscale_action_verify`, `settings_tailscale_verify_ok`, `settings_tailscale_verify_failed`, `settings_tailscale_verify_needs_on`. 35 keys in total, each in all 7 catalogs.
- **D1 C10 remediation → concrete Android APIs** (in Task C9): Install = `Intent(ACTION_VIEW, "market://details?id=com.tailscale.ipn")`, and on `ActivityNotFoundException` the `https://play.google.com/store/apps/details?id=com.tailscale.ipn` page. Open = `packageManager.getLaunchIntentForPackage("com.tailscale.ipn")`, falling back to Install when it is null. Manage routes = the existing `RouteEditorDialog` with Tailscale preselected (Task C10). Turn off and retry = `setTailscaleAlwaysConnectEnabled(false)` then `probeNow()`. The device-wide VPN settings screen is deliberately NOT offered (D1 C10). The `<queries>` entry for `com.tailscale.ipn` is slice B's manifest task.
- **Repo conventions honoured:** no `@Preview` in new files. Subpage scaffold copied from `AdvancedSettingsScreen.kt:29-63`, the closest existing settings subpage: `Scaffold` + `TopAppBar` + an `ArrowBack` icon using `R.string.settings_back` + a `verticalScroll` column with 16.dp padding and 12.dp spacing. Settings row = the existing `SettingsCategoryRow(icon, title, subtitle, onClick, isDarkTheme)` (`SettingsScreen.kt:1561-1568`). Nav = a `Screen` data object + a `composable(...)` guarded exactly like `Screen.AdvancedSettings` (`RelayApp.kt:624`, `:3011-3028`). kebab-case `testTag`s. Catalogs are translated (not English fallback) and use `\'` for apostrophes (verified in the base catalogs).
- **Platform truth honoured in copy:** the app never claims to know which other apps use Tailscale. "Verify my connection" re-probes and reports only Hermes Relay's own connection state. The "only this app" checklist points to Tailscale's own **App split tunneling** include mode.

### Tasks
#### Task C1: Add the 35 Tailscale strings to the English (canonical) catalog
- **Files (owns):** `app/src/main/res/values/strings.xml`
- **Depends on:** none
- **Estimate:** M (35 added lines, one insertion)
- **Create / modify:**
  Insert the 35 keys directly after the `settings_advanced_intro` line (it occurs once in this file).
  ```OLD (exact)
    <string name="settings_advanced_intro">Optional and specialized features live here to keep the main Settings screen focused.</string>
  ```
  ```NEW
    <string name="settings_advanced_intro">Optional and specialized features live here to keep the main Settings screen focused.</string>
    <string name="settings_tailscale_always_connect_title">Always connect via Tailscale</string>
    <string name="settings_tailscale_always_connect_subtitle_on">Required — no fallback to LAN or public routes</string>
    <string name="settings_tailscale_always_connect_subtitle_off">Optional — may fall back to other routes</string>
    <string name="settings_tailscale_always_connect_why">When this is on, Hermes Relay will only connect using a Tailscale route for this connection. If Tailscale isn\'t available, the app will refuse to connect and will tell you how to fix it.</string>
    <string name="settings_tailscale_required_title">Tailscale required</string>
    <string name="settings_tailscale_required_body_app_missing">Tailscale is not installed on this phone.</string>
    <string name="settings_tailscale_required_body_not_connected">This connection is set to always use Tailscale, but Tailscale is not available to Hermes Relay right now. Open Tailscale and connect, and make sure Hermes Relay is included in split tunneling.</string>
    <string name="settings_tailscale_required_body_no_route">This connection is set to always use Tailscale, but no Tailscale route is configured for it.</string>
    <string name="settings_tailscale_required_body_host_not_on_tailnet">This connection is set to always use Tailscale, but the route is not a tailnet address (.ts.net, 100.x, or fd7a:115c:a1e0::/48).</string>
    <string name="settings_tailscale_required_body_host_unreachable">Tailscale is on, but this Hermes host is not reachable over the tailnet route.</string>
    <string name="settings_tailscale_required_body_non_tailscale_active">This connection is set to always use Tailscale, but the current route is not Tailscale.</string>
    <string name="settings_tailscale_action_install_tailscale">Install Tailscale</string>
    <string name="settings_tailscale_action_open_tailscale">Open Tailscale</string>
    <string name="settings_tailscale_action_turn_off">Turn off and retry</string>
    <string name="settings_tailscale_action_manage_routes">Manage routes</string>
    <string name="tailnet_diag_blocked">Blocked: not a Tailscale route</string>
    <string name="tailnet_last_route_removed_warning">This was the last Tailscale route. The connection will stay blocked until you add one or turn off Always connect via Tailscale.</string>
    <string name="tailnet_route_blocked_chip">Tailscale only</string>
    <string name="tailnet_route_blocked_toast">Always connect via Tailscale is on — this route can\'t be used.</string>
    <string name="settings_tailscale_desc">Private remote access for this app</string>
    <string name="settings_tailscale_intro">This setting only changes how Hermes Relay connects to your Hermes computer. It never routes your whole phone through Tailscale.</string>
    <string name="settings_tailscale_no_active_connection">No gateway is active. Choose one in Settings → Gateways, then come back here.</string>
    <string name="settings_tailscale_applies_to">Applies to: %1$s</string>
    <string name="settings_tailscale_status_connected">Connected via Tailscale</string>
    <string name="settings_tailscale_only_this_app_title">Only this app</string>
    <string name="settings_tailscale_only_this_app_body">Which apps use Tailscale is decided in the Tailscale app, not here. To send only Hermes Relay through Tailscale:</string>
    <string name="settings_tailscale_step_install">1. Install Tailscale and sign in to the same tailnet as your Hermes computer.</string>
    <string name="settings_tailscale_step_split">2. In Tailscale, open App split tunneling and choose to include only the apps you select.</string>
    <string name="settings_tailscale_step_select">3. Select Hermes Relay. Do not leave the list empty: an empty list sends every app through Tailscale.</string>
    <string name="settings_tailscale_step_verify">4. Come back here and tap Verify my connection.</string>
    <string name="settings_tailscale_other_apps_note">Hermes Relay can check only its own connection. It cannot see which other apps use Tailscale.</string>
    <string name="settings_tailscale_action_verify">Verify my connection</string>
    <string name="settings_tailscale_verify_ok">Verified: Hermes Relay is connected to your Hermes computer over Tailscale (%1$s). This check covers this app only.</string>
    <string name="settings_tailscale_verify_failed">Not verified: Hermes Relay is not connected over Tailscale right now. Follow the steps above, then try again.</string>
    <string name="settings_tailscale_verify_needs_on">Turn on Always connect via Tailscale to verify this app\'s connection.</string>
  ```

- **Steps:**
  1. Open `app/src/main/res/values/strings.xml`.
  2. Confirm the anchor occurs once: `grep -c 'name="settings_advanced_intro"' app/src/main/res/values/strings.xml` → `1`.
  3. Replace the OLD line with the NEW block (the same line followed by the 35 new `<string>` lines). Copy the text character-for-character, including `\'` escapes, `%1$s` placeholders and non-ASCII punctuation (— – → „ “ « » 「 」 ： （ ）).
  4. Save as UTF-8 without BOM, keeping LF line endings. Change no other line.
  5. Leave `docs/localization-status.json` alone here. Task C8 refreshes the hashes once all 7 catalogs are done.
- **Verify:**
  ```bash
  cd <repo>
  grep -c 'name="settings_tailscale_\|name="tailnet_' app/src/main/res/values/strings.xml      # expect: 35
  python3 -c "import xml.etree.ElementTree as E;E.parse('app/src/main/res/values/strings.xml');print('xml ok')"   # expect: xml ok
  python3 - <<'PY'
  import re,pathlib
  for p in sorted(pathlib.Path('app/src/main/res').glob('values*/strings.xml')):
      for m in re.finditer(r'<string name="(settings_tailscale_[a-z_]+|tailnet_[a-z_]+)">(.*?)</string>', p.read_text(encoding='utf-8')):
          if re.search(r"(?<!\\)'", m.group(2)) or '"' in m.group(2): print('BAD', p, m.group(1))
  print('apostrophe-check done')
  PY
  # expect: only the line "apostrophe-check done" (no line starting with BAD)
  python3 scripts/check-android-locales.py; echo rc=$?
  # expect rc=1 until Tasks C1-C8 are all committed (missing keys in later catalogs and/or stale
  # source_sha256). This is expected here; record the real output. Task C8 is the green gate.
  ```
- **Commit:** `feat(android): add Tailscale always-connect strings (en)`

#### Task C2: Add the 35 Tailscale strings to the German catalog
- **Files (owns):** `app/src/main/res/values-de/strings.xml`
- **Depends on:** Task C1
- **Estimate:** M (35 added lines, one insertion)
- **Create / modify:**
  Insert the 35 keys directly after the `settings_advanced_intro` line (it occurs once in this file).
  ```OLD (exact)
    <string name="settings_advanced_intro">Optionale und spezielle Funktionen befinden sich hier, damit die Haupteinstellungen übersichtlich bleiben.</string>
  ```
  ```NEW
    <string name="settings_advanced_intro">Optionale und spezielle Funktionen befinden sich hier, damit die Haupteinstellungen übersichtlich bleiben.</string>
    <string name="settings_tailscale_always_connect_title">Immer über Tailscale verbinden</string>
    <string name="settings_tailscale_always_connect_subtitle_on">Erforderlich – kein Ausweichen auf LAN- oder öffentliche Routen</string>
    <string name="settings_tailscale_always_connect_subtitle_off">Optional – kann auf andere Routen ausweichen</string>
    <string name="settings_tailscale_always_connect_why">Wenn diese Option aktiv ist, verbindet sich Hermes Relay für diese Verbindung nur über eine Tailscale-Route. Ist Tailscale nicht verfügbar, verweigert die App die Verbindung und zeigt dir, wie du das behebst.</string>
    <string name="settings_tailscale_required_title">Tailscale erforderlich</string>
    <string name="settings_tailscale_required_body_app_missing">Tailscale ist auf diesem Smartphone nicht installiert.</string>
    <string name="settings_tailscale_required_body_not_connected">Diese Verbindung soll immer Tailscale verwenden, aber Tailscale steht Hermes Relay gerade nicht zur Verfügung. Öffne Tailscale, verbinde dich und stelle sicher, dass Hermes Relay beim Split-Tunneling einbezogen ist.</string>
    <string name="settings_tailscale_required_body_no_route">Diese Verbindung soll immer Tailscale verwenden, aber für sie ist keine Tailscale-Route eingerichtet.</string>
    <string name="settings_tailscale_required_body_host_not_on_tailnet">Diese Verbindung soll immer Tailscale verwenden, aber die Route ist keine Tailnet-Adresse (.ts.net, 100.x oder fd7a:115c:a1e0::/48).</string>
    <string name="settings_tailscale_required_body_host_unreachable">Tailscale ist aktiv, aber dieser Hermes-Host ist über die Tailnet-Route nicht erreichbar.</string>
    <string name="settings_tailscale_required_body_non_tailscale_active">Diese Verbindung soll immer Tailscale verwenden, aber die aktuelle Route ist keine Tailscale-Route.</string>
    <string name="settings_tailscale_action_install_tailscale">Tailscale installieren</string>
    <string name="settings_tailscale_action_open_tailscale">Tailscale öffnen</string>
    <string name="settings_tailscale_action_turn_off">Ausschalten und erneut versuchen</string>
    <string name="settings_tailscale_action_manage_routes">Routen verwalten</string>
    <string name="tailnet_diag_blocked">Blockiert: keine Tailscale-Route</string>
    <string name="tailnet_last_route_removed_warning">Das war die letzte Tailscale-Route. Die Verbindung bleibt blockiert, bis du eine hinzufügst oder „Immer über Tailscale verbinden“ ausschaltest.</string>
    <string name="tailnet_route_blocked_chip">Nur Tailscale</string>
    <string name="tailnet_route_blocked_toast">„Immer über Tailscale verbinden“ ist aktiv – diese Route kann nicht verwendet werden.</string>
    <string name="settings_tailscale_desc">Privater Fernzugriff für diese App</string>
    <string name="settings_tailscale_intro">Diese Einstellung ändert nur, wie Hermes Relay sich mit deinem Hermes-Computer verbindet. Sie leitet niemals dein ganzes Smartphone über Tailscale.</string>
    <string name="settings_tailscale_no_active_connection">Kein Gateway ist aktiv. Wähle eines unter Einstellungen → Gateways und kehre dann hierher zurück.</string>
    <string name="settings_tailscale_applies_to">Gilt für: %1$s</string>
    <string name="settings_tailscale_status_connected">Über Tailscale verbunden</string>
    <string name="settings_tailscale_only_this_app_title">Nur diese App</string>
    <string name="settings_tailscale_only_this_app_body">Welche Apps Tailscale nutzen, wird in der Tailscale-App festgelegt, nicht hier. So leitest du nur Hermes Relay über Tailscale:</string>
    <string name="settings_tailscale_step_install">1. Installiere Tailscale und melde dich im selben Tailnet an wie dein Hermes-Computer.</string>
    <string name="settings_tailscale_step_split">2. Öffne in Tailscale „App split tunneling“ und wähle, dass nur ausgewählte Apps einbezogen werden.</string>
    <string name="settings_tailscale_step_select">3. Wähle Hermes Relay aus. Lass die Liste nicht leer: Eine leere Liste leitet alle Apps über Tailscale.</string>
    <string name="settings_tailscale_step_verify">4. Kehre hierher zurück und tippe auf „Meine Verbindung prüfen“.</string>
    <string name="settings_tailscale_other_apps_note">Hermes Relay kann nur seine eigene Verbindung prüfen. Es kann nicht sehen, welche anderen Apps Tailscale nutzen.</string>
    <string name="settings_tailscale_action_verify">Meine Verbindung prüfen</string>
    <string name="settings_tailscale_verify_ok">Bestätigt: Hermes Relay ist über Tailscale mit deinem Hermes-Computer verbunden (%1$s). Diese Prüfung gilt nur für diese App.</string>
    <string name="settings_tailscale_verify_failed">Nicht bestätigt: Hermes Relay ist gerade nicht über Tailscale verbunden. Folge den Schritten oben und versuche es erneut.</string>
    <string name="settings_tailscale_verify_needs_on">Schalte „Immer über Tailscale verbinden“ ein, um die Verbindung dieser App zu prüfen.</string>
  ```

- **Steps:**
  1. Open `app/src/main/res/values-de/strings.xml`.
  2. Confirm the anchor occurs once: `grep -c 'name="settings_advanced_intro"' app/src/main/res/values-de/strings.xml` → `1`.
  3. Replace the OLD line with the NEW block (the same line followed by the 35 new `<string>` lines). Copy the text character-for-character, including `\'` escapes, `%1$s` placeholders and non-ASCII punctuation (— – → „ “ « » 「 」 ： （ ）).
  4. Save as UTF-8 without BOM, keeping LF line endings. Change no other line.
- **Verify:**
  ```bash
  cd <repo>
  grep -c 'name="settings_tailscale_\|name="tailnet_' app/src/main/res/values-de/strings.xml      # expect: 35
  python3 -c "import xml.etree.ElementTree as E;E.parse('app/src/main/res/values-de/strings.xml');print('xml ok')"   # expect: xml ok
  python3 - <<'PY'
  import re,pathlib
  for p in sorted(pathlib.Path('app/src/main/res').glob('values*/strings.xml')):
      for m in re.finditer(r'<string name="(settings_tailscale_[a-z_]+|tailnet_[a-z_]+)">(.*?)</string>', p.read_text(encoding='utf-8')):
          if re.search(r"(?<!\\)'", m.group(2)) or '"' in m.group(2): print('BAD', p, m.group(1))
  print('apostrophe-check done')
  PY
  # expect: only the line "apostrophe-check done" (no line starting with BAD)
  python3 scripts/check-android-locales.py; echo rc=$?
  # expect rc=1 until Tasks C1-C8 are all committed (missing keys in later catalogs and/or stale
  # source_sha256). This is expected here; record the real output. Task C8 is the green gate.
  ```
- **Commit:** `feat(android): add Tailscale always-connect strings (de)`

#### Task C3: Add the 35 Tailscale strings to the Spanish catalog
- **Files (owns):** `app/src/main/res/values-es/strings.xml`
- **Depends on:** Task C2
- **Estimate:** M (35 added lines, one insertion)
- **Create / modify:**
  Insert the 35 keys directly after the `settings_advanced_intro` line (it occurs once in this file).
  ```OLD (exact)
    <string name="settings_advanced_intro">Las funciones opcionales y especializadas están aquí para mantener despejada la pantalla principal de Ajustes.</string>
  ```
  ```NEW
    <string name="settings_advanced_intro">Las funciones opcionales y especializadas están aquí para mantener despejada la pantalla principal de Ajustes.</string>
    <string name="settings_tailscale_always_connect_title">Conectar siempre mediante Tailscale</string>
    <string name="settings_tailscale_always_connect_subtitle_on">Obligatorio: sin recurrir a rutas LAN o públicas</string>
    <string name="settings_tailscale_always_connect_subtitle_off">Opcional: puede recurrir a otras rutas</string>
    <string name="settings_tailscale_always_connect_why">Cuando esta opción está activada, Hermes Relay solo se conectará usando una ruta de Tailscale para esta conexión. Si Tailscale no está disponible, la aplicación se negará a conectarse y le indicará cómo solucionarlo.</string>
    <string name="settings_tailscale_required_title">Tailscale obligatorio</string>
    <string name="settings_tailscale_required_body_app_missing">Tailscale no está instalado en este teléfono.</string>
    <string name="settings_tailscale_required_body_not_connected">Esta conexión está configurada para usar siempre Tailscale, pero Tailscale no está disponible para Hermes Relay en este momento. Abra Tailscale y conéctese, y asegúrese de que Hermes Relay esté incluido en el túnel dividido.</string>
    <string name="settings_tailscale_required_body_no_route">Esta conexión está configurada para usar siempre Tailscale, pero no tiene ninguna ruta de Tailscale configurada.</string>
    <string name="settings_tailscale_required_body_host_not_on_tailnet">Esta conexión está configurada para usar siempre Tailscale, pero la ruta no es una dirección de la tailnet (.ts.net, 100.x o fd7a:115c:a1e0::/48).</string>
    <string name="settings_tailscale_required_body_host_unreachable">Tailscale está activado, pero no se puede acceder a este host de Hermes por la ruta de la tailnet.</string>
    <string name="settings_tailscale_required_body_non_tailscale_active">Esta conexión está configurada para usar siempre Tailscale, pero la ruta actual no es de Tailscale.</string>
    <string name="settings_tailscale_action_install_tailscale">Instalar Tailscale</string>
    <string name="settings_tailscale_action_open_tailscale">Abrir Tailscale</string>
    <string name="settings_tailscale_action_turn_off">Desactivar y reintentar</string>
    <string name="settings_tailscale_action_manage_routes">Administrar rutas</string>
    <string name="tailnet_diag_blocked">Bloqueada: no es una ruta de Tailscale</string>
    <string name="tailnet_last_route_removed_warning">Esta era la última ruta de Tailscale. La conexión seguirá bloqueada hasta que agregue una o desactive Conectar siempre mediante Tailscale.</string>
    <string name="tailnet_route_blocked_chip">Solo Tailscale</string>
    <string name="tailnet_route_blocked_toast">Conectar siempre mediante Tailscale está activado: no se puede usar esta ruta.</string>
    <string name="settings_tailscale_desc">Acceso remoto privado para esta aplicación</string>
    <string name="settings_tailscale_intro">Este ajuste solo cambia cómo Hermes Relay se conecta a su computadora Hermes. Nunca envía todo el teléfono a través de Tailscale.</string>
    <string name="settings_tailscale_no_active_connection">No hay ningún gateway activo. Elija uno en Ajustes → Gateways y vuelva aquí.</string>
    <string name="settings_tailscale_applies_to">Se aplica a: %1$s</string>
    <string name="settings_tailscale_status_connected">Conectado mediante Tailscale</string>
    <string name="settings_tailscale_only_this_app_title">Solo esta aplicación</string>
    <string name="settings_tailscale_only_this_app_body">Las aplicaciones que usan Tailscale se eligen en la aplicación Tailscale, no aquí. Para enviar solo Hermes Relay a través de Tailscale:</string>
    <string name="settings_tailscale_step_install">1. Instale Tailscale e inicie sesión en la misma tailnet que su computadora Hermes.</string>
    <string name="settings_tailscale_step_split">2. En Tailscale, abra «App split tunneling» y elija incluir solo las aplicaciones que seleccione.</string>
    <string name="settings_tailscale_step_select">3. Seleccione Hermes Relay. No deje la lista vacía: una lista vacía envía todas las aplicaciones a través de Tailscale.</string>
    <string name="settings_tailscale_step_verify">4. Vuelva aquí y toque «Verificar mi conexión».</string>
    <string name="settings_tailscale_other_apps_note">Hermes Relay solo puede comprobar su propia conexión. No puede ver qué otras aplicaciones usan Tailscale.</string>
    <string name="settings_tailscale_action_verify">Verificar mi conexión</string>
    <string name="settings_tailscale_verify_ok">Verificado: Hermes Relay está conectado a su computadora Hermes mediante Tailscale (%1$s). Esta comprobación solo abarca esta aplicación.</string>
    <string name="settings_tailscale_verify_failed">No verificado: Hermes Relay no está conectado mediante Tailscale en este momento. Siga los pasos anteriores y vuelva a intentarlo.</string>
    <string name="settings_tailscale_verify_needs_on">Active Conectar siempre mediante Tailscale para verificar la conexión de esta aplicación.</string>
  ```

- **Steps:**
  1. Open `app/src/main/res/values-es/strings.xml`.
  2. Confirm the anchor occurs once: `grep -c 'name="settings_advanced_intro"' app/src/main/res/values-es/strings.xml` → `1`.
  3. Replace the OLD line with the NEW block (the same line followed by the 35 new `<string>` lines). Copy the text character-for-character, including `\'` escapes, `%1$s` placeholders and non-ASCII punctuation (— – → „ “ « » 「 」 ： （ ）).
  4. Save as UTF-8 without BOM, keeping LF line endings. Change no other line.
- **Verify:**
  ```bash
  cd <repo>
  grep -c 'name="settings_tailscale_\|name="tailnet_' app/src/main/res/values-es/strings.xml      # expect: 35
  python3 -c "import xml.etree.ElementTree as E;E.parse('app/src/main/res/values-es/strings.xml');print('xml ok')"   # expect: xml ok
  python3 - <<'PY'
  import re,pathlib
  for p in sorted(pathlib.Path('app/src/main/res').glob('values*/strings.xml')):
      for m in re.finditer(r'<string name="(settings_tailscale_[a-z_]+|tailnet_[a-z_]+)">(.*?)</string>', p.read_text(encoding='utf-8')):
          if re.search(r"(?<!\\)'", m.group(2)) or '"' in m.group(2): print('BAD', p, m.group(1))
  print('apostrophe-check done')
  PY
  # expect: only the line "apostrophe-check done" (no line starting with BAD)
  python3 scripts/check-android-locales.py; echo rc=$?
  # expect rc=1 until Tasks C1-C8 are all committed (missing keys in later catalogs and/or stale
  # source_sha256). This is expected here; record the real output. Task C8 is the green gate.
  ```
- **Commit:** `feat(android): add Tailscale always-connect strings (es)`

#### Task C4: Add the 35 Tailscale strings to the Japanese catalog
- **Files (owns):** `app/src/main/res/values-ja/strings.xml`
- **Depends on:** Task C3
- **Estimate:** M (35 added lines, one insertion)
- **Create / modify:**
  Insert the 35 keys directly after the `settings_advanced_intro` line (it occurs once in this file).
  ```OLD (exact)
    <string name="settings_advanced_intro">メインの設定画面をシンプルに保つため、オプション機能と専門機能はここにまとめられています。</string>
  ```
  ```NEW
    <string name="settings_advanced_intro">メインの設定画面をシンプルに保つため、オプション機能と専門機能はここにまとめられています。</string>
    <string name="settings_tailscale_always_connect_title">常に Tailscale 経由で接続</string>
    <string name="settings_tailscale_always_connect_subtitle_on">必須 — LAN やパブリックルートへのフォールバックなし</string>
    <string name="settings_tailscale_always_connect_subtitle_off">任意 — 他のルートにフォールバックする場合があります</string>
    <string name="settings_tailscale_always_connect_why">オンにすると、Hermes Relay はこの接続で Tailscale ルートのみを使って接続します。Tailscale が利用できない場合、アプリは接続を拒否し、解決方法を表示します。</string>
    <string name="settings_tailscale_required_title">Tailscale が必要です</string>
    <string name="settings_tailscale_required_body_app_missing">この電話に Tailscale がインストールされていません。</string>
    <string name="settings_tailscale_required_body_not_connected">この接続は常に Tailscale を使うよう設定されていますが、現在 Hermes Relay は Tailscale を利用できません。Tailscale を開いて接続し、Hermes Relay がスプリットトンネリングの対象に含まれていることを確認してください。</string>
    <string name="settings_tailscale_required_body_no_route">この接続は常に Tailscale を使うよう設定されていますが、Tailscale ルートが設定されていません。</string>
    <string name="settings_tailscale_required_body_host_not_on_tailnet">この接続は常に Tailscale を使うよう設定されていますが、ルートが tailnet アドレス (.ts.net、100.x、fd7a:115c:a1e0::/48) ではありません。</string>
    <string name="settings_tailscale_required_body_host_unreachable">Tailscale はオンですが、この Hermes ホストに tailnet ルート経由で到達できません。</string>
    <string name="settings_tailscale_required_body_non_tailscale_active">この接続は常に Tailscale を使うよう設定されていますが、現在のルートは Tailscale ではありません。</string>
    <string name="settings_tailscale_action_install_tailscale">Tailscale をインストール</string>
    <string name="settings_tailscale_action_open_tailscale">Tailscale を開く</string>
    <string name="settings_tailscale_action_turn_off">オフにして再試行</string>
    <string name="settings_tailscale_action_manage_routes">ルートを管理</string>
    <string name="tailnet_diag_blocked">ブロック: Tailscale ルートではありません</string>
    <string name="tailnet_last_route_removed_warning">これが最後の Tailscale ルートでした。ルートを追加するか「常に Tailscale 経由で接続」をオフにするまで、接続はブロックされたままになります。</string>
    <string name="tailnet_route_blocked_chip">Tailscale のみ</string>
    <string name="tailnet_route_blocked_toast">「常に Tailscale 経由で接続」がオンのため、このルートは使用できません。</string>
    <string name="settings_tailscale_desc">このアプリ専用のプライベートなリモートアクセス</string>
    <string name="settings_tailscale_intro">この設定は Hermes Relay が Hermes コンピューターに接続する方法だけを変更します。電話全体の通信を Tailscale 経由にすることはありません。</string>
    <string name="settings_tailscale_no_active_connection">アクティブなゲートウェイがありません。[設定] → [ゲートウェイ] で選択してから、ここに戻ってください。</string>
    <string name="settings_tailscale_applies_to">対象: %1$s</string>
    <string name="settings_tailscale_status_connected">Tailscale 経由で接続済み</string>
    <string name="settings_tailscale_only_this_app_title">このアプリのみ</string>
    <string name="settings_tailscale_only_this_app_body">どのアプリが Tailscale を使うかは、ここではなく Tailscale アプリで決まります。Hermes Relay だけを Tailscale 経由にするには:</string>
    <string name="settings_tailscale_step_install">1. Tailscale をインストールし、Hermes コンピューターと同じ tailnet にサインインします。</string>
    <string name="settings_tailscale_step_split">2. Tailscale で「App split tunneling」を開き、選択したアプリのみを含めるように設定します。</string>
    <string name="settings_tailscale_step_select">3. Hermes Relay を選択します。リストを空のままにしないでください。空のリストではすべてのアプリが Tailscale 経由になります。</string>
    <string name="settings_tailscale_step_verify">4. ここに戻り、「接続を確認」をタップします。</string>
    <string name="settings_tailscale_other_apps_note">Hermes Relay が確認できるのは自身の接続だけです。他のどのアプリが Tailscale を使っているかは確認できません。</string>
    <string name="settings_tailscale_action_verify">接続を確認</string>
    <string name="settings_tailscale_verify_ok">確認済み: Hermes Relay は Tailscale 経由で Hermes コンピューターに接続されています (%1$s)。この確認はこのアプリのみが対象です。</string>
    <string name="settings_tailscale_verify_failed">未確認: Hermes Relay は現在 Tailscale 経由で接続されていません。上記の手順を行ってから、もう一度お試しください。</string>
    <string name="settings_tailscale_verify_needs_on">このアプリの接続を確認するには、「常に Tailscale 経由で接続」をオンにしてください。</string>
  ```

- **Steps:**
  1. Open `app/src/main/res/values-ja/strings.xml`.
  2. Confirm the anchor occurs once: `grep -c 'name="settings_advanced_intro"' app/src/main/res/values-ja/strings.xml` → `1`.
  3. Replace the OLD line with the NEW block (the same line followed by the 35 new `<string>` lines). Copy the text character-for-character, including `\'` escapes, `%1$s` placeholders and non-ASCII punctuation (— – → „ “ « » 「 」 ： （ ）).
  4. Save as UTF-8 without BOM, keeping LF line endings. Change no other line.
- **Verify:**
  ```bash
  cd <repo>
  grep -c 'name="settings_tailscale_\|name="tailnet_' app/src/main/res/values-ja/strings.xml      # expect: 35
  python3 -c "import xml.etree.ElementTree as E;E.parse('app/src/main/res/values-ja/strings.xml');print('xml ok')"   # expect: xml ok
  python3 - <<'PY'
  import re,pathlib
  for p in sorted(pathlib.Path('app/src/main/res').glob('values*/strings.xml')):
      for m in re.finditer(r'<string name="(settings_tailscale_[a-z_]+|tailnet_[a-z_]+)">(.*?)</string>', p.read_text(encoding='utf-8')):
          if re.search(r"(?<!\\)'", m.group(2)) or '"' in m.group(2): print('BAD', p, m.group(1))
  print('apostrophe-check done')
  PY
  # expect: only the line "apostrophe-check done" (no line starting with BAD)
  python3 scripts/check-android-locales.py; echo rc=$?
  # expect rc=1 until Tasks C1-C8 are all committed (missing keys in later catalogs and/or stale
  # source_sha256). This is expected here; record the real output. Task C8 is the green gate.
  ```
- **Commit:** `feat(android): add Tailscale always-connect strings (ja)`

#### Task C5: Add the 35 Tailscale strings to the Russian catalog
- **Files (owns):** `app/src/main/res/values-ru/strings.xml`
- **Depends on:** Task C4
- **Estimate:** M (35 added lines, one insertion)
- **Create / modify:**
  Insert the 35 keys directly after the `settings_advanced_intro` line (it occurs once in this file).
  ```OLD (exact)
    <string name="settings_advanced_intro">Дополнительные и специальные функции собраны здесь, чтобы не перегружать главный экран настроек.</string>
  ```
  ```NEW
    <string name="settings_advanced_intro">Дополнительные и специальные функции собраны здесь, чтобы не перегружать главный экран настроек.</string>
    <string name="settings_tailscale_always_connect_title">Всегда подключаться через Tailscale</string>
    <string name="settings_tailscale_always_connect_subtitle_on">Обязательно — без переключения на LAN или публичные маршруты</string>
    <string name="settings_tailscale_always_connect_subtitle_off">Необязательно — возможен переход на другие маршруты</string>
    <string name="settings_tailscale_always_connect_why">Когда параметр включён, Hermes Relay подключается для этого соединения только через маршрут Tailscale. Если Tailscale недоступен, приложение откажется подключаться и подскажет, как это исправить.</string>
    <string name="settings_tailscale_required_title">Требуется Tailscale</string>
    <string name="settings_tailscale_required_body_app_missing">Tailscale не установлен на этом телефоне.</string>
    <string name="settings_tailscale_required_body_not_connected">Для этого подключения всегда требуется Tailscale, но сейчас Tailscale недоступен для Hermes Relay. Откройте Tailscale и подключитесь, а также убедитесь, что Hermes Relay включён в раздельное туннелирование.</string>
    <string name="settings_tailscale_required_body_no_route">Для этого подключения всегда требуется Tailscale, но маршрут Tailscale для него не настроен.</string>
    <string name="settings_tailscale_required_body_host_not_on_tailnet">Для этого подключения всегда требуется Tailscale, но маршрут не является адресом tailnet (.ts.net, 100.x или fd7a:115c:a1e0::/48).</string>
    <string name="settings_tailscale_required_body_host_unreachable">Tailscale включён, но этот хост Hermes недоступен по маршруту tailnet.</string>
    <string name="settings_tailscale_required_body_non_tailscale_active">Для этого подключения всегда требуется Tailscale, но текущий маршрут не использует Tailscale.</string>
    <string name="settings_tailscale_action_install_tailscale">Установить Tailscale</string>
    <string name="settings_tailscale_action_open_tailscale">Открыть Tailscale</string>
    <string name="settings_tailscale_action_turn_off">Выключить и повторить</string>
    <string name="settings_tailscale_action_manage_routes">Управление маршрутами</string>
    <string name="tailnet_diag_blocked">Заблокировано: не маршрут Tailscale</string>
    <string name="tailnet_last_route_removed_warning">Это был последний маршрут Tailscale. Подключение останется заблокированным, пока вы не добавите маршрут или не выключите «Всегда подключаться через Tailscale».</string>
    <string name="tailnet_route_blocked_chip">Только Tailscale</string>
    <string name="tailnet_route_blocked_toast">Включено «Всегда подключаться через Tailscale» — этот маршрут нельзя использовать.</string>
    <string name="settings_tailscale_desc">Частный удалённый доступ для этого приложения</string>
    <string name="settings_tailscale_intro">Этот параметр меняет только то, как Hermes Relay подключается к вашему компьютеру с Hermes. Он никогда не направляет весь трафик телефона через Tailscale.</string>
    <string name="settings_tailscale_no_active_connection">Нет активного шлюза. Выберите его в разделе Настройки → Шлюзы и вернитесь сюда.</string>
    <string name="settings_tailscale_applies_to">Применяется к: %1$s</string>
    <string name="settings_tailscale_status_connected">Подключено через Tailscale</string>
    <string name="settings_tailscale_only_this_app_title">Только это приложение</string>
    <string name="settings_tailscale_only_this_app_body">Какие приложения используют Tailscale, задаётся в приложении Tailscale, а не здесь. Чтобы через Tailscale шёл только Hermes Relay:</string>
    <string name="settings_tailscale_step_install">1. Установите Tailscale и войдите в тот же tailnet, что и ваш компьютер с Hermes.</string>
    <string name="settings_tailscale_step_split">2. В Tailscale откройте «App split tunneling» и выберите режим, в котором включены только выбранные приложения.</string>
    <string name="settings_tailscale_step_select">3. Выберите Hermes Relay. Не оставляйте список пустым: при пустом списке через Tailscale идут все приложения.</string>
    <string name="settings_tailscale_step_verify">4. Вернитесь сюда и нажмите «Проверить подключение».</string>
    <string name="settings_tailscale_other_apps_note">Hermes Relay может проверить только собственное подключение. Он не видит, какие ещё приложения используют Tailscale.</string>
    <string name="settings_tailscale_action_verify">Проверить подключение</string>
    <string name="settings_tailscale_verify_ok">Проверено: Hermes Relay подключён к вашему компьютеру с Hermes через Tailscale (%1$s). Проверка касается только этого приложения.</string>
    <string name="settings_tailscale_verify_failed">Не подтверждено: сейчас Hermes Relay не подключён через Tailscale. Выполните шаги выше и повторите попытку.</string>
    <string name="settings_tailscale_verify_needs_on">Включите «Всегда подключаться через Tailscale», чтобы проверить подключение этого приложения.</string>
  ```

- **Steps:**
  1. Open `app/src/main/res/values-ru/strings.xml`.
  2. Confirm the anchor occurs once: `grep -c 'name="settings_advanced_intro"' app/src/main/res/values-ru/strings.xml` → `1`.
  3. Replace the OLD line with the NEW block (the same line followed by the 35 new `<string>` lines). Copy the text character-for-character, including `\'` escapes, `%1$s` placeholders and non-ASCII punctuation (— – → „ “ « » 「 」 ： （ ）).
  4. Save as UTF-8 without BOM, keeping LF line endings. Change no other line.
- **Verify:**
  ```bash
  cd <repo>
  grep -c 'name="settings_tailscale_\|name="tailnet_' app/src/main/res/values-ru/strings.xml      # expect: 35
  python3 -c "import xml.etree.ElementTree as E;E.parse('app/src/main/res/values-ru/strings.xml');print('xml ok')"   # expect: xml ok
  python3 - <<'PY'
  import re,pathlib
  for p in sorted(pathlib.Path('app/src/main/res').glob('values*/strings.xml')):
      for m in re.finditer(r'<string name="(settings_tailscale_[a-z_]+|tailnet_[a-z_]+)">(.*?)</string>', p.read_text(encoding='utf-8')):
          if re.search(r"(?<!\\)'", m.group(2)) or '"' in m.group(2): print('BAD', p, m.group(1))
  print('apostrophe-check done')
  PY
  # expect: only the line "apostrophe-check done" (no line starting with BAD)
  python3 scripts/check-android-locales.py; echo rc=$?
  # expect rc=1 until Tasks C1-C8 are all committed (missing keys in later catalogs and/or stale
  # source_sha256). This is expected here; record the real output. Task C8 is the green gate.
  ```
- **Commit:** `feat(android): add Tailscale always-connect strings (ru)`

#### Task C6: Add the 35 Tailscale strings to the Simplified Chinese catalog
- **Files (owns):** `app/src/main/res/values-b+zh+Hans/strings.xml`
- **Depends on:** Task C5
- **Estimate:** M (35 added lines, one insertion)
- **Create / modify:**
  Insert the 35 keys directly after the `settings_advanced_intro` line (it occurs once in this file).
  ```OLD (exact)
    <string name="settings_advanced_intro">可选和专用功能集中在此，以保持主设置界面简洁。</string>
  ```
  ```NEW
    <string name="settings_advanced_intro">可选和专用功能集中在此，以保持主设置界面简洁。</string>
    <string name="settings_tailscale_always_connect_title">始终通过 Tailscale 连接</string>
    <string name="settings_tailscale_always_connect_subtitle_on">必需 — 不回退到局域网或公共路由</string>
    <string name="settings_tailscale_always_connect_subtitle_off">可选 — 可能回退到其他路由</string>
    <string name="settings_tailscale_always_connect_why">开启后，Hermes Relay 只会使用 Tailscale 路由建立此连接。如果 Tailscale 不可用，应用将拒绝连接，并告诉你如何解决。</string>
    <string name="settings_tailscale_required_title">需要 Tailscale</string>
    <string name="settings_tailscale_required_body_app_missing">此手机上未安装 Tailscale。</string>
    <string name="settings_tailscale_required_body_not_connected">此连接已设置为始终使用 Tailscale，但 Hermes Relay 目前无法使用 Tailscale。请打开 Tailscale 并连接，并确保 Hermes Relay 已包含在分应用隧道中。</string>
    <string name="settings_tailscale_required_body_no_route">此连接已设置为始终使用 Tailscale，但尚未为其配置 Tailscale 路由。</string>
    <string name="settings_tailscale_required_body_host_not_on_tailnet">此连接已设置为始终使用 Tailscale，但该路由不是 tailnet 地址（.ts.net、100.x 或 fd7a:115c:a1e0::/48）。</string>
    <string name="settings_tailscale_required_body_host_unreachable">Tailscale 已开启，但无法通过 tailnet 路由访问此 Hermes 主机。</string>
    <string name="settings_tailscale_required_body_non_tailscale_active">此连接已设置为始终使用 Tailscale，但当前路由不是 Tailscale。</string>
    <string name="settings_tailscale_action_install_tailscale">安装 Tailscale</string>
    <string name="settings_tailscale_action_open_tailscale">打开 Tailscale</string>
    <string name="settings_tailscale_action_turn_off">关闭并重试</string>
    <string name="settings_tailscale_action_manage_routes">管理路由</string>
    <string name="tailnet_diag_blocked">已阻止：不是 Tailscale 路由</string>
    <string name="tailnet_last_route_removed_warning">这是最后一条 Tailscale 路由。在你添加一条路由或关闭“始终通过 Tailscale 连接”之前，连接将保持被阻止状态。</string>
    <string name="tailnet_route_blocked_chip">仅 Tailscale</string>
    <string name="tailnet_route_blocked_toast">“始终通过 Tailscale 连接”已开启 — 无法使用此路由。</string>
    <string name="settings_tailscale_desc">仅供此应用使用的私密远程访问</string>
    <string name="settings_tailscale_intro">此设置只改变 Hermes Relay 连接到你的 Hermes 电脑的方式，绝不会让整部手机的流量都经过 Tailscale。</string>
    <string name="settings_tailscale_no_active_connection">当前没有活动网关。请在“设置 → 网关”中选择一个，然后返回此处。</string>
    <string name="settings_tailscale_applies_to">适用于：%1$s</string>
    <string name="settings_tailscale_status_connected">已通过 Tailscale 连接</string>
    <string name="settings_tailscale_only_this_app_title">仅此应用</string>
    <string name="settings_tailscale_only_this_app_body">哪些应用使用 Tailscale 由 Tailscale 应用决定，而不是在这里设置。要让只有 Hermes Relay 经过 Tailscale：</string>
    <string name="settings_tailscale_step_install">1. 安装 Tailscale，并登录与你的 Hermes 电脑相同的 tailnet。</string>
    <string name="settings_tailscale_step_split">2. 在 Tailscale 中打开“App split tunneling”，并选择仅包含你选定的应用。</string>
    <string name="settings_tailscale_step_select">3. 选择 Hermes Relay。不要让列表为空：列表为空时，所有应用都会经过 Tailscale。</string>
    <string name="settings_tailscale_step_verify">4. 返回此处并点按“验证我的连接”。</string>
    <string name="settings_tailscale_other_apps_note">Hermes Relay 只能检查自己的连接，无法看到其他哪些应用在使用 Tailscale。</string>
    <string name="settings_tailscale_action_verify">验证我的连接</string>
    <string name="settings_tailscale_verify_ok">已验证：Hermes Relay 已通过 Tailscale 连接到你的 Hermes 电脑（%1$s）。此检查仅针对此应用。</string>
    <string name="settings_tailscale_verify_failed">未验证：Hermes Relay 当前未通过 Tailscale 连接。请按照上面的步骤操作，然后重试。</string>
    <string name="settings_tailscale_verify_needs_on">请开启“始终通过 Tailscale 连接”以验证此应用的连接。</string>
  ```

- **Steps:**
  1. Open `app/src/main/res/values-b+zh+Hans/strings.xml`.
  2. Confirm the anchor occurs once: `grep -c 'name="settings_advanced_intro"' app/src/main/res/values-b+zh+Hans/strings.xml` → `1`.
  3. Replace the OLD line with the NEW block (the same line followed by the 35 new `<string>` lines). Copy the text character-for-character, including `\'` escapes, `%1$s` placeholders and non-ASCII punctuation (— – → „ “ « » 「 」 ： （ ）).
  4. Save as UTF-8 without BOM, keeping LF line endings. Change no other line.
- **Verify:**
  ```bash
  cd <repo>
  grep -c 'name="settings_tailscale_\|name="tailnet_' app/src/main/res/values-b+zh+Hans/strings.xml      # expect: 35
  python3 -c "import xml.etree.ElementTree as E;E.parse('app/src/main/res/values-b+zh+Hans/strings.xml');print('xml ok')"   # expect: xml ok
  python3 - <<'PY'
  import re,pathlib
  for p in sorted(pathlib.Path('app/src/main/res').glob('values*/strings.xml')):
      for m in re.finditer(r'<string name="(settings_tailscale_[a-z_]+|tailnet_[a-z_]+)">(.*?)</string>', p.read_text(encoding='utf-8')):
          if re.search(r"(?<!\\)'", m.group(2)) or '"' in m.group(2): print('BAD', p, m.group(1))
  print('apostrophe-check done')
  PY
  # expect: only the line "apostrophe-check done" (no line starting with BAD)
  python3 scripts/check-android-locales.py; echo rc=$?
  # expect rc=1 until Tasks C1-C8 are all committed (missing keys in later catalogs and/or stale
  # source_sha256). This is expected here; record the real output. Task C8 is the green gate.
  ```
- **Commit:** `feat(android): add Tailscale always-connect strings (b+zh+Hans)`

#### Task C7: Add the 35 Tailscale strings to the Brazilian Portuguese catalog
- **Files (owns):** `app/src/main/res/values-b+pt+BR/strings.xml`
- **Depends on:** Task C6
- **Estimate:** M (35 added lines, one insertion)
- **Create / modify:**
  Insert the 35 keys directly after the `settings_advanced_intro` line (it occurs once in this file).
  ```OLD (exact)
    <string name="settings_advanced_intro">Recursos opcionais e especializados ficam aqui para manter a tela principal de Configurações organizada.</string>
  ```
  ```NEW
    <string name="settings_advanced_intro">Recursos opcionais e especializados ficam aqui para manter a tela principal de Configurações organizada.</string>
    <string name="settings_tailscale_always_connect_title">Sempre conectar pelo Tailscale</string>
    <string name="settings_tailscale_always_connect_subtitle_on">Obrigatório — sem recorrer a rotas LAN ou públicas</string>
    <string name="settings_tailscale_always_connect_subtitle_off">Opcional — pode recorrer a outras rotas</string>
    <string name="settings_tailscale_always_connect_why">Quando esta opção está ativada, o Hermes Relay só se conecta usando uma rota do Tailscale para esta conexão. Se o Tailscale não estiver disponível, o app se recusará a conectar e mostrará como resolver.</string>
    <string name="settings_tailscale_required_title">Tailscale obrigatório</string>
    <string name="settings_tailscale_required_body_app_missing">O Tailscale não está instalado neste celular.</string>
    <string name="settings_tailscale_required_body_not_connected">Esta conexão está configurada para usar sempre o Tailscale, mas o Tailscale não está disponível para o Hermes Relay agora. Abra o Tailscale e conecte-se, e verifique se o Hermes Relay está incluído no túnel dividido.</string>
    <string name="settings_tailscale_required_body_no_route">Esta conexão está configurada para usar sempre o Tailscale, mas nenhuma rota do Tailscale está configurada para ela.</string>
    <string name="settings_tailscale_required_body_host_not_on_tailnet">Esta conexão está configurada para usar sempre o Tailscale, mas a rota não é um endereço da tailnet (.ts.net, 100.x ou fd7a:115c:a1e0::/48).</string>
    <string name="settings_tailscale_required_body_host_unreachable">O Tailscale está ativado, mas este host do Hermes não está acessível pela rota da tailnet.</string>
    <string name="settings_tailscale_required_body_non_tailscale_active">Esta conexão está configurada para usar sempre o Tailscale, mas a rota atual não é do Tailscale.</string>
    <string name="settings_tailscale_action_install_tailscale">Instalar o Tailscale</string>
    <string name="settings_tailscale_action_open_tailscale">Abrir o Tailscale</string>
    <string name="settings_tailscale_action_turn_off">Desativar e tentar de novo</string>
    <string name="settings_tailscale_action_manage_routes">Gerenciar rotas</string>
    <string name="tailnet_diag_blocked">Bloqueada: não é uma rota do Tailscale</string>
    <string name="tailnet_last_route_removed_warning">Esta era a última rota do Tailscale. A conexão continuará bloqueada até você adicionar uma ou desativar Sempre conectar pelo Tailscale.</string>
    <string name="tailnet_route_blocked_chip">Somente Tailscale</string>
    <string name="tailnet_route_blocked_toast">Sempre conectar pelo Tailscale está ativado — esta rota não pode ser usada.</string>
    <string name="settings_tailscale_desc">Acesso remoto privado para este app</string>
    <string name="settings_tailscale_intro">Esta configuração só muda como o Hermes Relay se conecta ao seu computador Hermes. Ela nunca envia todo o celular pelo Tailscale.</string>
    <string name="settings_tailscale_no_active_connection">Nenhum gateway está ativo. Escolha um em Configurações → Gateways e volte aqui.</string>
    <string name="settings_tailscale_applies_to">Aplica-se a: %1$s</string>
    <string name="settings_tailscale_status_connected">Conectado pelo Tailscale</string>
    <string name="settings_tailscale_only_this_app_title">Somente este app</string>
    <string name="settings_tailscale_only_this_app_body">Quais apps usam o Tailscale é definido no app Tailscale, não aqui. Para enviar somente o Hermes Relay pelo Tailscale:</string>
    <string name="settings_tailscale_step_install">1. Instale o Tailscale e entre na mesma tailnet do seu computador Hermes.</string>
    <string name="settings_tailscale_step_split">2. No Tailscale, abra “App split tunneling” e escolha incluir somente os apps que você selecionar.</string>
    <string name="settings_tailscale_step_select">3. Selecione o Hermes Relay. Não deixe a lista vazia: uma lista vazia envia todos os apps pelo Tailscale.</string>
    <string name="settings_tailscale_step_verify">4. Volte aqui e toque em “Verificar minha conexão”.</string>
    <string name="settings_tailscale_other_apps_note">O Hermes Relay só consegue verificar a própria conexão. Ele não vê quais outros apps usam o Tailscale.</string>
    <string name="settings_tailscale_action_verify">Verificar minha conexão</string>
    <string name="settings_tailscale_verify_ok">Verificado: o Hermes Relay está conectado ao seu computador Hermes pelo Tailscale (%1$s). Esta verificação vale somente para este app.</string>
    <string name="settings_tailscale_verify_failed">Não verificado: o Hermes Relay não está conectado pelo Tailscale agora. Siga os passos acima e tente de novo.</string>
    <string name="settings_tailscale_verify_needs_on">Ative Sempre conectar pelo Tailscale para verificar a conexão deste app.</string>
  ```

- **Steps:**
  1. Open `app/src/main/res/values-b+pt+BR/strings.xml`.
  2. Confirm the anchor occurs once: `grep -c 'name="settings_advanced_intro"' app/src/main/res/values-b+pt+BR/strings.xml` → `1`.
  3. Replace the OLD line with the NEW block (the same line followed by the 35 new `<string>` lines). Copy the text character-for-character, including `\'` escapes, `%1$s` placeholders and non-ASCII punctuation (— – → „ “ « » 「 」 ： （ ）).
  4. Save as UTF-8 without BOM, keeping LF line endings. Change no other line.
- **Verify:**
  ```bash
  cd <repo>
  grep -c 'name="settings_tailscale_\|name="tailnet_' app/src/main/res/values-b+pt+BR/strings.xml      # expect: 35
  python3 -c "import xml.etree.ElementTree as E;E.parse('app/src/main/res/values-b+pt+BR/strings.xml');print('xml ok')"   # expect: xml ok
  python3 - <<'PY'
  import re,pathlib
  for p in sorted(pathlib.Path('app/src/main/res').glob('values*/strings.xml')):
      for m in re.finditer(r'<string name="(settings_tailscale_[a-z_]+|tailnet_[a-z_]+)">(.*?)</string>', p.read_text(encoding='utf-8')):
          if re.search(r"(?<!\\)'", m.group(2)) or '"' in m.group(2): print('BAD', p, m.group(1))
  print('apostrophe-check done')
  PY
  # expect: only the line "apostrophe-check done" (no line starting with BAD)
  python3 scripts/check-android-locales.py; echo rc=$?
  # expect rc=1 until Tasks C1-C8 are all committed (missing keys in later catalogs and/or stale
  # source_sha256). This is expected here; record the real output. Task C8 is the green gate.
  ```
- **Commit:** `feat(android): add Tailscale always-connect strings (b+pt+BR)`

#### Task C8: Refresh the six `source_sha256.main` values in docs/localization-status.json
- **Files (owns):** `docs/localization-status.json`
- **Depends on:** Task C7 (i.e. after C1–C7 are committed)
- **Estimate:** S (6 values changed)
- **Create / modify:**
  The registry stores, for each of the 6 non-English locales (de, es, ja, ru, zh-Hans, pt-BR), `source_sha256.main` = SHA-256 of `app/src/main/res/values/strings.xml`, read as UTF-8 with `\r\n` normalised to `\n` (`scripts/check-android-locales.py:190-256`). All six currently hold the same value, `8588c42385e783a2ddf888b515ca6b97a5df44a30d3619c048d3d03d91dd5278`, which occurs exactly 6 times in the file and nowhere else. `sideload` hashes do not change because no sideload catalog is touched.
  The value expected after Task C1 is exactly **`7a98fd2c69bc782ca0762be126bf1dad5515558093bf99c69de744fb754fc023`**. It was computed by applying C1–C7 to an archive of `e5989426`, and the locale gate then passed. Always recompute it with the command below and use the computed value. If it differs from the expected value, someone else changed `values/strings.xml`: use the computed value and record a deviation.
  Command to compute and write the hash (replaces all 6 occurrences in one go; no JSON reformatting):
  ```python
  python3 - <<'PY'
  import hashlib, pathlib
  src = pathlib.Path('app/src/main/res/values/strings.xml').read_text(encoding='utf-8').replace('\r\n', '\n')
  new = hashlib.sha256(src.encode('utf-8')).hexdigest()
  old = '8588c42385e783a2ddf888b515ca6b97a5df44a30d3619c048d3d03d91dd5278'
  reg = pathlib.Path('docs/localization-status.json')
  text = reg.read_text(encoding='utf-8')
  assert text.count(old) == 6, text.count(old)
  reg.write_text(text.replace(old, new), encoding='utf-8')
  print('new main hash', new)
  PY
  ```
- **Steps:**
  1. Confirm Tasks C1–C7 are committed (`git log --oneline -8` shows the seven `add Tailscale always-connect strings` commits).
  2. From the worktree root, run the python block above exactly once.
  3. Check that it printed `new main hash 7a98fd2c69bc782ca0762be126bf1dad5515558093bf99c69de744fb754fc023`. If it printed a different hash, keep it and note the deviation.
- **Verify:**
  ```bash
  cd <repo>
  grep -c '7a98fd2c69bc782ca0762be126bf1dad5515558093bf99c69de744fb754fc023' docs/localization-status.json   # expect: 6
  grep -c '8588c42385e783a2ddf888b515ca6b97a5df44a30d3619c048d3d03d91dd5278' docs/localization-status.json   # expect: 0
  python3 scripts/check-android-locales.py; echo rc=$?
  # expect: "Android locale validation passed (12 catalog(s))" and rc=0
  for d in values values-de values-es values-ja values-ru values-b+zh+Hans values-b+pt+BR; do printf '%s ' $d; grep -c 'name="settings_tailscale_\|name="tailnet_' app/src/main/res/$d/strings.xml; done
  # expect: 35 on every one of the 7 lines
  git diff --stat HEAD~1 -- docs/localization-status.json   # expect: 1 file changed, 6 insertions(+), 6 deletions(-)
  ```
- **Commit:** `feat(android): refresh localization source hashes for Tailscale strings`

#### Task C9: Create TailscaleAlwaysConnectCard.kt (MERGE C8 types, the switch card, the Routes status row, D1 C10 intents)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/components/TailscaleAlwaysConnectCard.kt` (NEW)
- **Depends on:** Task C1 (uses its `R.string` keys)
- **Estimate:** L (385 lines, new file)
- **Create / modify:**
  Complete file content:
  ```kotlin
package com.hermesandroid.relay.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape

/** Navigation route of Settings -> Always connect via Tailscale ([com.hermesandroid.relay.ui.screens.TailscaleSettingsScreen]). */
const val TAILSCALE_SETTINGS_ROUTE = "settings/tailscale"

private const val TAILSCALE_APP_PACKAGE = "com.tailscale.ipn"
private const val TAILSCALE_MARKET_URI = "market://details?id=com.tailscale.ipn"
private const val TAILSCALE_PLAY_WEB_URI = "https://play.google.com/store/apps/details?id=com.tailscale.ipn"

/** What the "Always connect via Tailscale" surfaces render for the active connection. */
sealed interface TailscaleAlwaysConnectUiState {
    data object Off : TailscaleAlwaysConnectUiState
    data class On(val status: TailscaleAlwaysConnectStatus) : TailscaleAlwaysConnectUiState
}

enum class TailscaleAlwaysConnectStatus {
    Healthy,
    TailscaleAppMissing,
    TailnetUnavailable,           // includes "not connected", "not signed in", and "app excluded"
    NoTailscaleRouteConfigured,
    HostNotOnTailnet,
    HostUnreachableOverTailnet,
    ConnectedViaNonTailnet,       // violation / transition; must be visible
}

/** One remediation button. Order in [remediationActions] = button order. */
internal enum class TailscaleAlwaysConnectAction {
    InstallTailscale,
    OpenTailscale,
    ManageRoutes,
    TurnOffAndRetry,
}

internal fun TailscaleAlwaysConnectStatus.remediationActions(): List<TailscaleAlwaysConnectAction> =
    when (this) {
        TailscaleAlwaysConnectStatus.Healthy -> emptyList()
        TailscaleAlwaysConnectStatus.TailscaleAppMissing -> listOf(
            TailscaleAlwaysConnectAction.InstallTailscale,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.TailnetUnavailable -> listOf(
            TailscaleAlwaysConnectAction.OpenTailscale,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.NoTailscaleRouteConfigured -> listOf(
            TailscaleAlwaysConnectAction.ManageRoutes,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.HostNotOnTailnet -> listOf(
            TailscaleAlwaysConnectAction.ManageRoutes,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.HostUnreachableOverTailnet -> listOf(
            TailscaleAlwaysConnectAction.OpenTailscale,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.ConnectedViaNonTailnet -> listOf(
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
    }

/** Body text of the blocked banner; null when the status is [TailscaleAlwaysConnectStatus.Healthy]. */
@StringRes
internal fun TailscaleAlwaysConnectStatus.blockedBodyRes(): Int? =
    when (this) {
        TailscaleAlwaysConnectStatus.Healthy -> null
        TailscaleAlwaysConnectStatus.TailscaleAppMissing ->
            R.string.settings_tailscale_required_body_app_missing
        TailscaleAlwaysConnectStatus.TailnetUnavailable ->
            R.string.settings_tailscale_required_body_not_connected
        TailscaleAlwaysConnectStatus.NoTailscaleRouteConfigured ->
            R.string.settings_tailscale_required_body_no_route
        TailscaleAlwaysConnectStatus.HostNotOnTailnet ->
            R.string.settings_tailscale_required_body_host_not_on_tailnet
        TailscaleAlwaysConnectStatus.HostUnreachableOverTailnet ->
            R.string.settings_tailscale_required_body_host_unreachable
        TailscaleAlwaysConnectStatus.ConnectedViaNonTailnet ->
            R.string.settings_tailscale_required_body_non_tailscale_active
    }

/** Opens the Tailscale store listing: Play Store app first, then the Play web page. */
internal fun openTailscaleInstallPage(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_MARKET_URI))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_PLAY_WEB_URI))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(web)
        } catch (_: ActivityNotFoundException) {
            // No store and no browser: nothing else can be opened.
        }
    }
}

/** Opens the Tailscale app; falls back to its store listing when it cannot be launched. */
internal fun openTailscaleApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE_APP_PACKAGE)
    if (launch == null) {
        openTailscaleInstallPage(context)
        return
    }
    try {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openTailscaleInstallPage(context)
    }
}

/**
 * The ONE switch for "Always connect via Tailscale" plus its status / blocked banner and
 * remediation buttons. Rendered only by the Settings -> Always connect via Tailscale subpage;
 * the Routes tab shows [TailscaleAlwaysConnectStatusRow] instead (no second switch).
 * The switch stays enabled in every state so the policy can be changed while disconnected.
 */
@Composable
fun TailscaleAlwaysConnectCard(
    uiState: TailscaleAlwaysConnectUiState,
    onSetEnabled: (Boolean) -> Unit,
    onOpenTailscale: () -> Unit,
    onInstallTailscale: () -> Unit,
    onManageRoutes: () -> Unit,
    onTurnOffAndRetry: () -> Unit,
) {
    val isOn = uiState is TailscaleAlwaysConnectUiState.On
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = appearanceRoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tailscale-always-connect-card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isOn,
                        role = Role.Switch,
                        onValueChange = onSetEnabled,
                    )
                    .testTag("tailscale-always-connect-toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_tailscale_always_connect_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (isOn) {
                            stringResource(R.string.settings_tailscale_always_connect_subtitle_on)
                        } else {
                            stringResource(R.string.settings_tailscale_always_connect_subtitle_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isOn,
                    onCheckedChange = null,
                )
            }
            Text(
                text = stringResource(R.string.settings_tailscale_always_connect_why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (uiState) {
                TailscaleAlwaysConnectUiState.Off -> Unit
                is TailscaleAlwaysConnectUiState.On -> TailscaleAlwaysConnectStatusBlock(
                    status = uiState.status,
                    onOpenTailscale = onOpenTailscale,
                    onInstallTailscale = onInstallTailscale,
                    onManageRoutes = onManageRoutes,
                    onTurnOffAndRetry = onTurnOffAndRetry,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailscaleAlwaysConnectStatusBlock(
    status: TailscaleAlwaysConnectStatus,
    onOpenTailscale: () -> Unit,
    onInstallTailscale: () -> Unit,
    onManageRoutes: () -> Unit,
    onTurnOffAndRetry: () -> Unit,
) {
    val bodyRes = status.blockedBodyRes()
    if (bodyRes == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.settings_tailscale_status_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = appearanceRoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tailscale-always-connect-blocked"),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_required_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    status.remediationActions().forEach { action ->
                        when (action) {
                            TailscaleAlwaysConnectAction.InstallTailscale -> TextButton(onClick = onInstallTailscale) {
                                Text(stringResource(R.string.settings_tailscale_action_install_tailscale))
                            }
                            TailscaleAlwaysConnectAction.OpenTailscale -> TextButton(onClick = onOpenTailscale) {
                                Text(stringResource(R.string.settings_tailscale_action_open_tailscale))
                            }
                            TailscaleAlwaysConnectAction.ManageRoutes -> TextButton(onClick = onManageRoutes) {
                                Text(stringResource(R.string.settings_tailscale_action_manage_routes))
                            }
                            TailscaleAlwaysConnectAction.TurnOffAndRetry -> TextButton(onClick = onTurnOffAndRetry) {
                                Text(stringResource(R.string.settings_tailscale_action_turn_off))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Read-only status line for the Routes tab. Tapping it opens the Settings subpage that owns
 * the switch; it deliberately has no switch of its own.
 */
@Composable
fun TailscaleAlwaysConnectStatusRow(
    uiState: TailscaleAlwaysConnectUiState,
    onClick: () -> Unit,
) {
    val blocked = uiState is TailscaleAlwaysConnectUiState.On &&
        uiState.status != TailscaleAlwaysConnectStatus.Healthy
    val subtitle = when (uiState) {
        TailscaleAlwaysConnectUiState.Off ->
            stringResource(R.string.settings_tailscale_always_connect_subtitle_off)
        is TailscaleAlwaysConnectUiState.On ->
            if (uiState.status == TailscaleAlwaysConnectStatus.Healthy) {
                stringResource(R.string.settings_tailscale_status_connected)
            } else {
                stringResource(R.string.settings_tailscale_required_title)
            }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = appearanceRoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tailscale-always-connect-status-row"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = if (blocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_tailscale_always_connect_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
  ```
- **Steps:**
  1. Confirm the file does not exist yet: `test ! -e app/src/main/kotlin/com/hermesandroid/relay/ui/components/TailscaleAlwaysConnectCard.kt && echo absent` → `absent`.
  2. Confirm the symbols the file uses exist: `grep -n 'fun appearanceRoundedCornerShape' app/src/main/kotlin/com/hermesandroid/relay/ui/theme/AppearanceShape.kt` → 1 hit (`:94`); `grep -rn 'catch (_:' app/src/main/kotlin | head -1` → at least 1 hit (the repo's Kotlin 2.4.10 accepts `_` catch parameters).
  3. Create the file with the exact content above. Do NOT add `@Preview` and add no other declarations.
  4. Do not create `TailnetRemediation.kt` or any second file for the remediation mapping. MERGE's single card file owns it (`remediationActions()`, `blockedBodyRes()`, `openTailscaleApp`, `openTailscaleInstallPage`).
- **Verify:**
  ```bash
  cd <repo>
  F=app/src/main/kotlin/com/hermesandroid/relay/ui/components/TailscaleAlwaysConnectCard.kt
  grep -c 'sealed interface TailscaleAlwaysConnectUiState\|enum class TailscaleAlwaysConnectStatus\|fun TailscaleAlwaysConnectCard(\|fun TailscaleAlwaysConnectStatusRow(\|const val TAILSCALE_SETTINGS_ROUTE' $F   # expect: 5
  grep -c '@Preview' $F                                   # expect: 0
  grep -c 'ACTION_VPN_SETTINGS\|VpnService\|bindProcessToNetwork' $F   # expect: 0
  grep -o 'R\.string\.[a-z_]*' $F | sort -u | sed 's/R.string.//' | while read k; do grep -q "name=\"$k\"" app/src/main/res/values/strings.xml || echo MISSING $k; done; echo keys-checked
  # expect: only "keys-checked" (no MISSING line)
  python3 scripts/check-android-collection-apis.py        # expect: "Android collection API compatibility check passed (Kotlin sources)"
  ```
  Compile correctness is proven by cloud CI only (no JDK on this host).
- **Commit:** `feat(android): add Tailscale always-connect card and status row`

#### Task C10: Let RouteEditorDialog preselect a role for new routes
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/components/EndpointsCard.kt`
- **Depends on:** none
- **Estimate:** S (3 lines)
- **Create / modify:**
  Edit 1 (signature `EndpointsCard.kt:1032-1035`):
  ```OLD (exact)
    onSave: (role: String, dashboardUrl: String, onResult: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
  ```
  ```NEW
    onSave: (role: String, dashboardUrl: String, onResult: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    /** Role preselected when adding a new route ([original] == null). */
    initialRole: String = "lan",
) {
    val uriHandler = LocalUriHandler.current
  ```
  Edit 2 (initial role `EndpointsCard.kt:1039-1041`):
  ```OLD (exact)
            when (original?.role?.lowercase()) {
                null -> "lan"
                in knownRoles -> original.role.lowercase()
  ```
  ```NEW
            when (original?.role?.lowercase()) {
                null -> initialRole
                in knownRoles -> original.role.lowercase()
  ```
  Imports to add: none.
  The only existing caller (`ActiveConnectionSections.kt:2030`) uses named arguments and keeps the default `"lan"`, so its behaviour is unchanged.
- **Steps:**
  1. Open `EndpointsCard.kt`; apply Edit 1 (add the `initialRole` parameter after `onDismiss`).
  2. Apply Edit 2 (replace `null -> "lan"` with `null -> initialRole`).
  3. Change nothing else.
- **Verify:**
  ```bash
  cd <repo>
  grep -n 'initialRole' app/src/main/kotlin/com/hermesandroid/relay/ui/components/EndpointsCard.kt   # expect: 2 hits (parameter + "null -> initialRole")
  grep -c 'null -> "lan"' app/src/main/kotlin/com/hermesandroid/relay/ui/components/EndpointsCard.kt   # expect: 0
  git diff --stat HEAD~1   # expect: 1 file changed, 3 insertions(+), 1 deletion(-)
  ```
- **Commit:** `feat(android): allow route editor to preselect a role`

#### Task C11: Create TailscaleSettingsScreen.kt (the Settings subpage that owns the switch)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/TailscaleSettingsScreen.kt` (NEW)
- **Depends on:** Task C9, Task C10, and slice B's ConnectionViewModel task that adds `tailscaleAlwaysConnectUiState` + `setTailscaleAlwaysConnectEnabled` (MERGE C8)
- **Estimate:** L (232 lines, new file)
- **Create / modify:**
  Complete file content (structure copied from `AdvancedSettingsScreen.kt:29-63`):
  ```kotlin
package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.primaryRouteUrl
import com.hermesandroid.relay.ui.components.RouteEditorDialog
import com.hermesandroid.relay.ui.components.TailscaleAlwaysConnectCard
import com.hermesandroid.relay.ui.components.TailscaleAlwaysConnectStatus
import com.hermesandroid.relay.ui.components.TailscaleAlwaysConnectUiState
import com.hermesandroid.relay.ui.components.openTailscaleApp
import com.hermesandroid.relay.ui.components.openTailscaleInstallPage
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * Settings -> Always connect via Tailscale. Home of the only switch for the per-connection
 * policy, its blocked banner and remediation buttons, and the "only this app" checklist.
 * Hermes Relay can verify only its own connection; which other apps use Tailscale is set
 * in the Tailscale app and is never claimed here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val uiState by connectionViewModel.tailscaleAlwaysConnectUiState.collectAsState()
    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
    val endpointsFlow = remember(connectionViewModel) { connectionViewModel.observeDeviceEndpoints() }
    val endpoints: List<EndpointCandidate> by endpointsFlow.collectAsState(initial = emptyList())
    val routeProbeStatus: ConnectionViewModel.RouteProbeStatus =
        connectionViewModel.routeProbeStatus.collectAsState().value
    val isProbing = routeProbeStatus is ConnectionViewModel.RouteProbeStatus.Probing
    var routeEditorOpen by remember { mutableStateOf(false) }
    var verifyRequested by remember { mutableStateOf(false) }

    val isOn = uiState is TailscaleAlwaysConnectUiState.On
    val isHealthy = (uiState as? TailscaleAlwaysConnectUiState.On)?.status ==
        TailscaleAlwaysConnectStatus.Healthy

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_tailscale_always_connect_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_tailscale_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val connection = activeConnection
            if (connection == null) {
                Text(
                    text = stringResource(R.string.settings_tailscale_no_active_connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_tailscale_applies_to, connection.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TailscaleAlwaysConnectCard(
                    uiState = uiState,
                    onSetEnabled = { enabled ->
                        connectionViewModel.setTailscaleAlwaysConnectEnabled(enabled)
                    },
                    onOpenTailscale = { openTailscaleApp(context) },
                    onInstallTailscale = { openTailscaleInstallPage(context) },
                    onManageRoutes = { routeEditorOpen = true },
                    onTurnOffAndRetry = {
                        connectionViewModel.setTailscaleAlwaysConnectEnabled(false)
                        connectionViewModel.probeNow()
                    },
                )
                if (routeEditorOpen) {
                    RouteEditorDialog(
                        original = null,
                        initialRole = "tailscale",
                        relayEnabled = connection.relayUrl.isNotBlank() ||
                            endpoints.any { it.relay != null },
                        onSave = { role, dashboardUrl, onResult ->
                            connectionViewModel.saveExtraRoute(
                                role = role,
                                dashboardUrl = dashboardUrl,
                                original = null,
                                onResult = onResult,
                            )
                        },
                        onDismiss = { routeEditorOpen = false },
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = appearanceRoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tailscale-only-this-app"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_tailscale_only_this_app_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_only_this_app_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_step_install),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_step_split),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_step_select),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_step_verify),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_other_apps_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            verifyRequested = true
                            connectionViewModel.probeNow()
                        },
                        enabled = isOn && !isProbing,
                        modifier = Modifier.testTag("tailscale-verify-connection"),
                    ) {
                        Text(stringResource(R.string.settings_tailscale_action_verify))
                    }
                    if (!isOn) {
                        Text(
                            text = stringResource(R.string.settings_tailscale_verify_needs_on),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (verifyRequested) {
                        when {
                            isProbing -> Text(
                                text = stringResource(R.string.active_section_checking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            isHealthy -> Text(
                                text = stringResource(
                                    R.string.settings_tailscale_verify_ok,
                                    activeEndpoint?.primaryRouteUrl().orEmpty(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            else -> Text(
                                text = stringResource(R.string.settings_tailscale_verify_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
  ```
  Behaviour notes for the reviewer:
  - The switch is usable in every state, including a dormant or disconnected connection, because it only needs `activeConnection != null` (D2 state rules). With no active connection, the page shows `settings_tailscale_no_active_connection` and no switch.
  - "Verify my connection" calls the existing `probeNow()` and then reports only this app's state: `Probing` → "Checking…" (the existing `active_section_checking`); `On(Healthy)` → `settings_tailscale_verify_ok` with the active route URL; anything else → `settings_tailscale_verify_failed`. It is disabled while the policy is Off and shows `settings_tailscale_verify_needs_on` instead.
  - "Manage routes" opens the existing `RouteEditorDialog` with `initialRole = "tailscale"` and saves through the existing `saveExtraRoute(...)`, the same call as `ActiveConnectionSections.kt:2034-2041`.
- **Steps:**
  1. Before writing, confirm slice B's seams exist: `grep -n 'val tailscaleAlwaysConnectUiState\|fun setTailscaleAlwaysConnectEnabled' app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → 2 hits. If there are 0 hits, STOP and report `blocked on slice B` (do not add them yourself: that file belongs to slice B).
  2. Confirm the existing members: `grep -n 'val activeConnection: StateFlow<Connection?>\|val activeEndpoint = \|fun observeDeviceEndpoints\|fun saveExtraRoute\|val routeProbeStatus\|fun probeNow' app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → 6 hits. Also `grep -n 'fun EndpointCandidate.primaryRouteUrl' app/src/main/kotlin/com/hermesandroid/relay/data/Endpoint.kt` → 1 hit.
  3. Create the file with the exact content above. No `@Preview`.
- **Verify:**
  ```bash
  cd <repo>
  F=app/src/main/kotlin/com/hermesandroid/relay/ui/screens/TailscaleSettingsScreen.kt
  grep -c 'fun TailscaleSettingsScreen(' $F            # expect: 1
  grep -c 'TailscaleAlwaysConnectCard(' $F             # expect: 1
  grep -c 'initialRole = "tailscale"' $F               # expect: 1
  grep -c '@Preview' $F                                # expect: 0
  grep -o 'R\.string\.[a-z_]*' $F | sort -u | sed 's/R.string.//' | while read k; do grep -q "name=\"$k\"" app/src/main/res/values/strings.xml || echo MISSING $k; done; echo keys-checked
  # expect: only "keys-checked"
  ```
  Compile correctness is proven by cloud CI only.
- **Commit:** `feat(android): add Always connect via Tailscale settings page`

#### Task C12: Add the Settings row directly under Gateways
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt`
- **Depends on:** Task C1
- **Estimate:** S (≈14 lines)
- **Create / modify:**
  Edit 1 (callback parameter, `SettingsScreen.kt:167`):
  ```OLD (exact)
    onNavigateToAdvancedSettings: () -> Unit = {},
  ```
  ```NEW
    onNavigateToAdvancedSettings: () -> Unit = {},
    onNavigateToTailscaleSettings: () -> Unit = {},
  ```
  Edit 2 (row after the Gateways row, `SettingsScreen.kt:572-578`):
  ```OLD (exact)
            SettingsCategoryRow(
                icon = Icons.Filled.Devices,
                title = stringResource(R.string.settings_connections),
                subtitle = stringResource(R.string.settings_connections_desc),
                onClick = onNavigateToConnections,
                isDarkTheme = isDarkTheme,
            )
  ```
  ```NEW
            SettingsCategoryRow(
                icon = Icons.Filled.Devices,
                title = stringResource(R.string.settings_connections),
                subtitle = stringResource(R.string.settings_connections_desc),
                onClick = onNavigateToConnections,
                isDarkTheme = isDarkTheme,
            )

            // Always connect via Tailscale sits directly under Gateways: it is a
            // per-gateway connection policy, so it lives next to the gateway list.
            SettingsCategoryRow(
                icon = Icons.Filled.VpnKey,
                title = stringResource(R.string.settings_tailscale_always_connect_title),
                subtitle = stringResource(R.string.settings_tailscale_desc),
                onClick = onNavigateToTailscaleSettings,
                isDarkTheme = isDarkTheme,
            )
  ```
  Edit 3 (import, next to the existing `Refresh` icon import at `SettingsScreen.kt:53`):
  ```OLD (exact)
import androidx.compose.material.icons.filled.Refresh
  ```
  ```NEW
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
  ```
  Imports to add: `androidx.compose.material.icons.filled.VpnKey` (Edit 3). The icon is already used in the app (`ActiveConnectionSections.kt`), so no new dependency is needed.
- **Steps:**
  1. Apply Edit 1: add `onNavigateToTailscaleSettings: () -> Unit = {},` directly after `onNavigateToAdvancedSettings: () -> Unit = {},`. The default `{}` keeps every other caller compiling.
  2. Apply Edit 2: insert the new `SettingsCategoryRow` directly after the Gateways row and before `ProviderUsageLandingCard(`.
  3. Apply Edit 3 (import).
- **Verify:**
  ```bash
  cd <repo>
  F=app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt
  grep -n 'onNavigateToTailscaleSettings' $F      # expect: 2 hits (parameter + onClick)
  grep -n 'settings_tailscale_always_connect_title\|settings_tailscale_desc' $F   # expect: 2 hits
  grep -c '^import androidx.compose.material.icons.filled.VpnKey$' $F   # expect: 1
  awk '/title = stringResource\(R.string.settings_connections\)/{a=NR} /settings_tailscale_always_connect_title/{b=NR} END{print (b>a && b-a<15) ? "row-under-gateways" : "WRONG-PLACE"}' $F   # expect: row-under-gateways
  ```
- **Commit:** `feat(android): add Tailscale entry under Gateways in Settings`

#### Task C13: Wire the Settings subpage into RelayApp navigation
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt`
- **Depends on:** Task C9, Task C11, Task C12
- **Estimate:** S (≈22 lines)
- **Create / modify:**
  Edit 1 (screen import, before `TerminalScreen` import at `RelayApp.kt:194`):
  ```OLD (exact)
import com.hermesandroid.relay.ui.screens.TerminalScreen
  ```
  ```NEW
import com.hermesandroid.relay.ui.screens.TailscaleSettingsScreen
import com.hermesandroid.relay.ui.screens.TerminalScreen
  ```
  Edit 2 (route constant import, before `AdvancedSettingsScreen` import at `RelayApp.kt:158`):
  ```OLD (exact)
import com.hermesandroid.relay.ui.screens.AdvancedSettingsScreen
  ```
  ```NEW
import com.hermesandroid.relay.ui.components.TAILSCALE_SETTINGS_ROUTE
import com.hermesandroid.relay.ui.screens.AdvancedSettingsScreen
  ```
  Edit 3 (`Screen` sealed object, after `AdvancedSettings` at `RelayApp.kt:624`):
  ```OLD (exact)
    data object AdvancedSettings : Screen("settings/advanced", "Advanced", Icons.Filled.Settings)
  ```
  ```NEW
    data object AdvancedSettings : Screen("settings/advanced", "Advanced", Icons.Filled.Settings)
    data object TailscaleSettings : Screen(TAILSCALE_SETTINGS_ROUTE, "Tailscale", Icons.Filled.Settings)
  ```
  Edit 4 (`SettingsScreen(...)` call site, `RelayApp.kt:2925-2927`):
  ```OLD (exact)
                        onNavigateToAdvancedSettings = {
                            navController.navigate(Screen.AdvancedSettings.route)
                        },
  ```
  ```NEW
                        onNavigateToAdvancedSettings = {
                            navController.navigate(Screen.AdvancedSettings.route)
                        },
                        onNavigateToTailscaleSettings = {
                            navController.navigate(Screen.TailscaleSettings.route)
                        },
  ```
  Edit 5 (`NavHost` destination, inserted directly before the `SupervisedAppearanceSettings` destination at `RelayApp.kt:3029`; supervised guard copied from `RelayApp.kt:3011-3019`):
  ```OLD (exact)
                composable(Screen.SupervisedAppearanceSettings.route) {
  ```
  ```NEW
                composable(Screen.TailscaleSettings.route) {
                    if (!parentAccessForCurrentRoute && supervisedPolicy.enabled) {
                        LaunchedEffect(Unit) {
                            navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        TailscaleSettingsScreen(
                            connectionViewModel = connectionViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(Screen.SupervisedAppearanceSettings.route) {
  ```
  Imports to add: `com.hermesandroid.relay.ui.screens.TailscaleSettingsScreen` and `com.hermesandroid.relay.ui.components.TAILSCALE_SETTINGS_ROUTE` (Edits 1–2). `LaunchedEffect`, `findStartDestination` and `connectionViewModel` are already in scope at this site (`RelayApp.kt:715`, `:3013-3015`).
  The Routes status row (Task C14) navigates through the existing `NavRouteRequest` collector (`RelayApp.kt:1370-1379`). That collector calls `navController.navigate(route) { launchSingleTop = true }` for any registered route, so no extra wiring is needed.
- **Steps:**
  1. Apply Edits 1–5 in order. Each OLD block occurs exactly once.
  2. Change nothing else in RelayApp.kt.
- **Verify:**
  ```bash
  cd <repo>
  F=app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt
  grep -n 'Screen.TailscaleSettings' $F          # expect: 2 hits (navigate + composable)
  grep -n 'data object TailscaleSettings' $F      # expect: 1 hit
  grep -n 'TailscaleSettingsScreen(' $F           # expect: 1 hit
  grep -n 'onNavigateToTailscaleSettings' $F      # expect: 1 hit
  grep -c '^import com.hermesandroid.relay.ui.components.TAILSCALE_SETTINGS_ROUTE$' $F   # expect: 1
  ```
- **Commit:** `feat(android): route Settings to the Tailscale page`

#### Task C14: Add the read-only Tailscale status row to the Routes tab (no second switch)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ActiveConnectionSections.kt`
- **Depends on:** Task C9, Task C13, and slice B's `tailscaleAlwaysConnectUiState` (MERGE C8)
- **Estimate:** S (≈14 lines)
- **Create / modify:**
  Edit 1 (signature, `ActiveConnectionSections.kt:1523-1528`):
  ```OLD (exact)
fun ActiveCardRoutesSection(
    connectionViewModel: ConnectionViewModel,
    connection: Connection,
    liveState: RelayUiState?,
    onEditDashboard: () -> Unit,
) {
  ```
  ```NEW
fun ActiveCardRoutesSection(
    connectionViewModel: ConnectionViewModel,
    connection: Connection,
    liveState: RelayUiState?,
    onEditDashboard: () -> Unit,
    /** Opens Settings -> Always connect via Tailscale (the only place with the switch). */
    onOpenTailscaleSettings: () -> Unit = { NavRouteRequest.tryRequest(TAILSCALE_SETTINGS_ROUTE) },
) {
  ```
  Edit 2 (state, after `ActiveConnectionSections.kt:1543`):
  ```OLD (exact)
    val gatewayAvailabilityForRoutes by connectionViewModel.gatewayAvailability.collectAsState()
  ```
  ```NEW
    val gatewayAvailabilityForRoutes by connectionViewModel.gatewayAvailability.collectAsState()
    val tailscaleAlwaysConnectState by connectionViewModel.tailscaleAlwaysConnectUiState.collectAsState()
  ```
  Edit 3 (status row right after the "Network routes" heading + summary, `ActiveConnectionSections.kt:1799-1805`, which is MERGE C8's insertion point):
  ```OLD (exact)
        Text(
            text = stringResource(R.string.network_routes_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (showTailscaleUnavailableHint) {
  ```
  ```NEW
        Text(
            text = stringResource(R.string.network_routes_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TailscaleAlwaysConnectStatusRow(
            uiState = tailscaleAlwaysConnectState,
            onClick = onOpenTailscaleSettings,
        )

        if (showTailscaleUnavailableHint) {
  ```
  Edit 4 (import):
  ```OLD (exact)
import com.hermesandroid.relay.util.classifyError
  ```
  ```NEW
import com.hermesandroid.relay.util.NavRouteRequest
import com.hermesandroid.relay.util.classifyError
  ```
  Imports to add: `com.hermesandroid.relay.util.NavRouteRequest`. `TailscaleAlwaysConnectStatusRow` and `TAILSCALE_SETTINGS_ROUTE` are in the same package (`ui.components`) and need no import.
  The `ConnectionDetailScreen.kt` caller is not in slice C and does not change. The default `onOpenTailscaleSettings` sends the route through `NavRouteRequest`.
- **Steps:**
  1. Confirm slice B's seam: `grep -n 'val tailscaleAlwaysConnectUiState' app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt` → 1 hit. If there are 0 hits, STOP and report `blocked on slice B`.
  2. Apply Edits 1–4. Do NOT add a `Switch` or any toggle to this file.
- **Verify:**
  ```bash
  cd <repo>
  F=app/src/main/kotlin/com/hermesandroid/relay/ui/components/ActiveConnectionSections.kt
  grep -n 'TailscaleAlwaysConnectStatusRow(' $F          # expect: 1 hit
  grep -n 'tailscaleAlwaysConnectUiState' $F             # expect: 1 hit
  grep -c 'TailscaleAlwaysConnectCard(\|setTailscaleAlwaysConnectEnabled' $F   # expect: 0 (no second switch)
  grep -c '^import com.hermesandroid.relay.util.NavRouteRequest$' $F   # expect: 1
  ```
- **Commit:** `feat(android): show Tailscale policy status on the Routes tab`

#### Task C15: Mark non-Tailscale routes as "Tailscale only" in the route list while the policy is on (D1 S10 UI part)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/components/EndpointsCard.kt`
- **Depends on:** Task C1, Task C10
- **Estimate:** M (≈45 lines across 7 small edits)
- **Create / modify:**
  Edit 1, `EndpointsCard` parameters (`EndpointsCard.kt:133-136`):
  ```OLD (exact)
    onAddRoute: (() -> Unit)? = null,
    onEditRoute: ((EndpointCandidate) -> Unit)? = null,
    onRemoveRoute: ((EndpointCandidate) -> Unit)? = null,
) {
  ```
  ```NEW
    onAddRoute: (() -> Unit)? = null,
    onEditRoute: ((EndpointCandidate) -> Unit)? = null,
    onRemoveRoute: ((EndpointCandidate) -> Unit)? = null,
    /**
     * True while "Always connect via Tailscale" is on for this connection. Rows that
     * [isTailnetEligible] rejects then lose "Use now" / "Prefer" and show a "Tailscale only" chip.
     */
    tailnetOnly: Boolean = false,
    isTailnetEligible: (EndpointCandidate) -> Boolean = { true },
) {
  ```
  Edit 2, per-row flags (`EndpointsCard.kt:208-211`):
  ```OLD (exact)
        endpoints.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            EndpointRow(
                candidate = candidate,
  ```
  ```NEW
        val tailnetEligibleCount = if (tailnetOnly) endpoints.count(isTailnetEligible) else 0
        endpoints.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            val candidateTailnetEligible = isTailnetEligible(candidate)
            EndpointRow(
                candidate = candidate,
                blockedByTailnetPolicy = tailnetOnly && !candidateTailnetEligible,
                removesLastTailnetRoute = tailnetOnly && candidateTailnetEligible && tailnetEligibleCount == 1,
  ```
  Edit 3, `EndpointRow` parameters (`EndpointsCard.kt:279-281`):
  ```OLD (exact)
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
  ```
  ```NEW
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    blockedByTailnetPolicy: Boolean = false,
    removesLastTailnetRoute: Boolean = false,
) {
  ```
  Edit 4, "Tailscale only" chip (`EndpointsCard.kt:332-333`):
  ```OLD (exact)
                        else -> FallbackChip(stringResource(R.string.endpoints_fallback))
                    }
  ```
  ```NEW
                        else -> FallbackChip(stringResource(R.string.endpoints_fallback))
                    }
                    if (blockedByTailnetPolicy) {
                        FallbackChip(stringResource(R.string.tailnet_route_blocked_chip))
                    }
  ```
  Edit 5, hide "Use now" (`EndpointsCard.kt:445-449`):
  ```OLD (exact)
            if (!isActive) {
                TextButton(onClick = onUseNow) {
                    Text(stringResource(R.string.endpoints_use_now))
                }
            }
  ```
  ```NEW
            if (!isActive && !blockedByTailnetPolicy) {
                TextButton(onClick = onUseNow) {
                    Text(stringResource(R.string.endpoints_use_now))
                }
            }
  ```
  Edit 6, hide "Prefer this route" (`EndpointsCard.kt:461-482`; body re-indented by 4 spaces inside the new `if`):
  ```OLD (exact)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = if (isPreferred) stringResource(R.string.endpoints_stop_preferring_menu) else stringResource(R.string.endpoints_prefer_this_route),
                                )
                                Text(
                                    text = if (isPreferred) {
                                        stringResource(R.string.endpoints_back_to_automatic)
                                    } else {
                                        stringResource(R.string.endpoints_always_try_first)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            if (isPreferred) onClearPrefer() else onPrefer()
                        },
                    )
  ```
  ```NEW
                    if (!blockedByTailnetPolicy) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = if (isPreferred) stringResource(R.string.endpoints_stop_preferring_menu) else stringResource(R.string.endpoints_prefer_this_route),
                                    )
                                    Text(
                                        text = if (isPreferred) {
                                            stringResource(R.string.endpoints_back_to_automatic)
                                        } else {
                                            stringResource(R.string.endpoints_always_try_first)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                menuOpen = false
                                if (isPreferred) onClearPrefer() else onPrefer()
                            },
                        )
                    }
  ```
  Edit 7, remove-route dialog warning (`EndpointsCard.kt:537-542`):
  ```OLD (exact)
            text = {
                Text(
                    text = stringResource(R.string.endpoints_remove_route_body, candidate.routeAuthority().orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
  ```
  ```NEW
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.endpoints_remove_route_body, candidate.routeAuthority().orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (removesLastTailnetRoute) {
                        Text(
                            text = stringResource(R.string.tailnet_last_route_removed_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
  ```
  Imports to add: none (`Column`, `Arrangement`, `dp`, `MaterialTheme`, `Text`, `stringResource`, `R` are already imported at `EndpointsCard.kt:3-56`).
  Semantics (D1 S10): for an ineligible row while ON, hide "Use now" and "Prefer", keep "Probe now", "View pin", "Edit" and "Remove", and show the `tailnet_route_blocked_chip` chip. Removing the LAST eligible route is still allowed, and the confirmation dialog shows `tailnet_last_route_removed_warning`. The defaults (`tailnetOnly = false`, `isTailnetEligible = { true }`) keep today's behaviour byte-for-byte whenever the caller does not pass them.
- **Steps:**
  1. Apply Edits 1–7 in order. Each OLD block occurs exactly once at the base commit (Edit 6's OLD is the complete `DropdownMenuItem(...)` for Prefer / Stop preferring).
  2. Do not touch `RouteEditorDialog`'s role chips. LAN and public routes can still be added; they are stored and stay inert while ON (D1 S10).
- **Verify:**
  ```bash
  cd <repo>
  F=app/src/main/kotlin/com/hermesandroid/relay/ui/components/EndpointsCard.kt
  grep -c 'blockedByTailnetPolicy' $F           # expect: 5 (param, call-site arg, chip, Use-now guard, Prefer guard)
  grep -c 'removesLastTailnetRoute' $F          # expect: 3 (param, call-site arg, dialog)
  grep -n 'R.string.tailnet_route_blocked_chip\|R.string.tailnet_last_route_removed_warning' $F   # expect: 2 hits
  grep -c 'tailnetOnly: Boolean = false\|isTailnetEligible: (EndpointCandidate) -> Boolean = { true }' $F   # expect: 2
  ```
- **Commit:** `feat(android): lock non-Tailscale routes in the route list while Tailscale-only`

#### Task C16: Feed the Tailscale-only state into the Routes list
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ActiveConnectionSections.kt`
- **Depends on:** Task C14, Task C15, and slice A's `TailnetRoutePolicy` (MERGE C3)
- **Estimate:** S (4 lines)
- **Create / modify:**
  Edit 1 (`EndpointsCard(...)` call, `ActiveConnectionSections.kt:2025-2029`):
  ```OLD (exact)
                onRemoveRoute = { candidate -> connectionViewModel.removeExtraRoute(candidate) },
            )
        }

        if (routeEditorOpen) {
  ```
  ```NEW
                onRemoveRoute = { candidate -> connectionViewModel.removeExtraRoute(candidate) },
                tailnetOnly = tailscaleAlwaysConnectState is TailscaleAlwaysConnectUiState.On,
                isTailnetEligible = { candidate -> TailnetRoutePolicy.isEligible(candidate) },
            )
        }

        if (routeEditorOpen) {
  ```
  Edit 2 (import):
  ```OLD (exact)
import com.hermesandroid.relay.network.shared.EndpointSurface
  ```
  ```NEW
import com.hermesandroid.relay.network.shared.TailnetRoutePolicy
import com.hermesandroid.relay.network.shared.EndpointSurface
  ```
  Imports to add: `com.hermesandroid.relay.network.shared.TailnetRoutePolicy`.
- **Steps:**
  1. Find slice A's actual package: `grep -rn '^object TailnetRoutePolicy\|^internal object TailnetRoutePolicy' app/src/main/kotlin`. The expected path is `app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/TailnetRoutePolicy.kt`, package `com.hermesandroid.relay.network.shared`. If there is no hit, STOP and report `blocked on slice A`. If it lives in a different package, change ONLY the import line in Edit 2 to that package and record it as a deviation.
  2. Apply Edits 1–2.
- **Verify:**
  ```bash
  cd <repo>
  F=app/src/main/kotlin/com/hermesandroid/relay/ui/components/ActiveConnectionSections.kt
  grep -n 'tailnetOnly = tailscaleAlwaysConnectState is TailscaleAlwaysConnectUiState.On' $F   # expect: 1 hit
  grep -n 'TailnetRoutePolicy.isEligible(candidate)' $F       # expect: 1 hit
  grep -n '^import .*TailnetRoutePolicy$' $F                  # expect: 1 hit, matching the package found in step 1
  ```
- **Commit:** `feat(android): apply Tailscale-only lock to the Routes list`

#### Task C17: Never name a non-Tailscale route on the Gateways card while the policy is on (D1 S11)
- **Files (owns):** `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionsSettingsScreen.kt`
- **Depends on:** slice A's `TailnetRoutePolicy` (MERGE C3) and the task adding `Connection.alwaysViaTailscale` (MERGE C1; see open question 2)
- **Estimate:** S (≈14 lines)
- **Create / modify:**
  Edit 1 (`resolveGatewayCardPresentation`, `ConnectionsSettingsScreen.kt:557-562`):
  ```OLD (exact)
    val selectedRoute = activeEndpoint
        ?: connection.preferredRouteRole
            ?.let { preferred ->
                connection.routeCandidates.firstOrNull { it.role.equals(preferred, ignoreCase = true) }
            }
        ?: connection.routeCandidates.minByOrNull { it.priority }
  ```
  ```NEW
    val selectedRoute = if (connection.alwaysViaTailscale) {
        // Always connect via Tailscale: never name a non-tailnet route as this gateway's route.
        activeEndpoint?.takeIf { TailnetRoutePolicy.isEligible(it) }
            ?: connection.routeCandidates
                .filter { TailnetRoutePolicy.isEligible(it) }
                .minByOrNull { it.priority }
    } else {
        activeEndpoint
            ?: connection.preferredRouteRole
                ?.let { preferred ->
                    connection.routeCandidates.firstOrNull { it.role.equals(preferred, ignoreCase = true) }
                }
            ?: connection.routeCandidates.minByOrNull { it.priority }
    }
  ```
  Edit 2 (import):
  ```OLD (exact)
import com.hermesandroid.relay.network.upstream.GatewayAvailability
  ```
  ```NEW
import com.hermesandroid.relay.network.shared.TailnetRoutePolicy
import com.hermesandroid.relay.network.upstream.GatewayAvailability
  ```
  Imports to add: `com.hermesandroid.relay.network.shared.TailnetRoutePolicy` (same package check as Task C16 step 1).
  With the flag OFF, the `else` branch is the original expression unchanged, so the existing `GatewayRegistryPresentationTest` expectations hold (their `Connection`s use the default `alwaysViaTailscale = false`).
- **Steps:**
  1. `grep -n 'val alwaysViaTailscale: Boolean = false' app/src/main/kotlin/com/hermesandroid/relay/data/ConnectionData.kt` → 1 hit. If there are 0 hits, STOP and report `blocked on C1 field` (ConnectionData.kt is not a slice-C file).
  2. Run the same `TailnetRoutePolicy` package check as Task C16 step 1.
  3. Apply Edits 1–2.
- **Verify:**
  ```bash
  cd <repo>
  F=app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionsSettingsScreen.kt
  grep -n 'if (connection.alwaysViaTailscale)' $F      # expect: 1 hit
  grep -c 'TailnetRoutePolicy.isEligible(it)' $F        # expect: 2
  grep -n '^import .*TailnetRoutePolicy$' $F            # expect: 1 hit
  python3 scripts/check-android-locales.py && python3 scripts/check-android-collection-apis.py && python3 scripts/check-android-capabilities.py && python3 scripts/check-version-tracks.py
  # expect: all four print their pass line (see baseline_guards.md); rc=0
  python3 -m unittest scripts.tests.android_prepush_test scripts.tests.check_android_capabilities_test   # expect: OK
  git status --short    # expect: clean
  ```
- **Commit:** `feat(android): keep gateway card on Tailscale route while Tailscale-only`

## Slice C open questions

1. **Package of slice A's classes (authority conflict).** MERGE C2/C3 freeze `network/shared/TailnetRoutePolicy.kt`. FILE_OWNERSHIP.md row A, which binds who writes where, says `net/tailnet/`. This slice imports `com.hermesandroid.relay.network.shared.TailnetRoutePolicy` (Tasks C16 and C17). Each of those tasks starts with a mandatory `grep` for the real package and a one-line import correction rule, so either outcome is mechanical. Observed while writing this plan: uncommitted slice-A files in the worktree declare `package com.hermesandroid.relay.network.shared` and `object TailnetRoutePolicy { fun isEligible(candidate: EndpointCandidate): Boolean }` (`net/tailnet/TailnetRoutePolicy.kt:1,20,22`), which matches the import used here.
2. **`Connection.alwaysViaTailscale` (MERGE C1) has no owner.** `data/ConnectionData.kt` is not in any FILE_OWNERSHIP row. Task C17 depends on it and STOPs if it is missing. The orchestrator must assign it, most naturally to slice B, alongside `setTailscaleAlwaysConnectEnabled`.
3. **Primary home of the switch: MERGE C8 vs the owner's request.** MERGE C8 says "Primary home: Connection Detail → Routes tab … insert card after the Network routes heading". This slice's brief, which records the owner's explicit request, moves the ONE switch to the new Settings subpage and allows only a status row on the Routes tab. This plan follows the brief. MERGE C8's insertion point (after the "Network routes" heading, `ActiveConnectionSections.kt:1794-1803`) is kept for the status row, and MERGE C8's card type and signature are implemented verbatim (Task C9) and rendered on the subpage. Either way, only one switch exists in the app.
4. **`TailnetGateState` (D1 C9) vs `TailscaleAlwaysConnectUiState` (MERGE C8).** The brief mentions D1's gate state. MERGE wins, so the UI consumes only `TailscaleAlwaysConnectUiState`. D1's `Checking` state has no MERGE equivalent; slice B's ViewModel must map it to one of the 7 MERGE statuses (or hold the previous value). D1's `Recheck` button is covered by the subpage's "Verify my connection" (the same `probeNow()`), because MERGE's card signature has no recheck callback.
5. **S10/S11 split between slices.** Slice B's brief says the S10 UI writers and the S11 picker are "in your slice's files". However, `EndpointsCard.kt` and `ConnectionsSettingsScreen.kt` are slice C files (FILE_OWNERSHIP row C). This plan therefore carries the UI half of S10 (Task C15: chips, hidden actions, last-route warning) and all of S11 (Task C17). Slice B keeps the ViewModel half of S10 (`setPreferredEndpointRole` / `useRouteNow` guards and the `tailnet_route_blocked_toast` emission). The orchestrator must make sure slice B does not also write tasks for these two files.
6. **Wizard toggle and the other D1 §4.6 keys are unowned.** D2's wizard surface and D1's `tailnet_scan_lan_note` / `tailnet_routes_hint_only` would touch `ConnectionWizard.kt`, which is not in any ownership row. This slice adds neither. `tailnet_diag_blocked` and `tailnet_route_blocked_toast` are added to all 7 catalogs (they are frozen), but their consumers are slice B's or unassigned files.
7. **Unit tests for the pure mapping** (`remediationActions()`, `blockedBodyRes()` in Task C9) are not written here, because `app/src/test/...` and `scripts/android-prepush.py` are not slice C files. `PLAN:` if the orchestrator wants them, add a slice D task: `app/src/test/kotlin/com/hermesandroid/relay/ui/components/TailscaleAlwaysConnectCardTest.kt` plus a `FOCUSED_TESTS` entry.
8. `UNPROVEN:` the English-UI label "App split tunneling" is taken from recon T6 (platform research). If a future Tailscale Android release renames the screen, only the four `settings_tailscale_step_split` values need changing. The locale strings quote the label in English on purpose, because the Tailscale app shows it in English.
9. **FROZEN_STRINGS.md** received the 16 slice-C keys (appended, per the common brief). The 15 MERGE C9 keys are frozen by MERGE itself and are therefore not duplicated there.

## Slice C self-check

- Files written by tasks = exactly FILE_OWNERSHIP row C: the 7 `strings.xml` catalogs (C1–C7), `docs/localization-status.json` (C8), NEW `TailscaleAlwaysConnectCard.kt` (C9), `EndpointsCard.kt` (C10, C15), NEW `TailscaleSettingsScreen.kt` (C11), `SettingsScreen.kt` (C12), `RelayApp.kt` (C13), `ActiveConnectionSections.kt` (C14, C16), `ConnectionsSettingsScreen.kt` (C17). No slice A, B or D file is edited, and no second file for the same purpose is created (no `TailnetRemediation.kt`: the mapping lives in the MERGE card file).
- Every OLD block was checked against base `e5989426` to occur exactly once. All 17 tasks were applied to an archive of the base tree: `check-android-locales.py`, `check-android-collection-apis.py`, `check-android-capabilities.py` and `check-version-tracks.py` all passed, and every `R.string` key referenced by the new or edited Kotlin exists in `values/strings.xml`. The apostrophe/quote checker was shown to catch a planted bad value (negative control).
- The locale hash is a real computed value (`7a98fd2c…c023`), with its exact command. 35 keys × 7 catalogs. Translations follow each catalog's existing voice (de "du", es "usted", ru formal "вы", ja です/ます, zh 你, pt-BR você) and the repo's `\'` escaping.
- Settings placement = directly under Gateways (`SettingsScreen.kt:572-578`), using the existing `SettingsCategoryRow` recipe. Nav = `Screen.TailscaleSettings` + a `composable` with the same supervised guard as `Screen.AdvancedSettings` + a `SettingsScreen` callback with a default.
- Exactly ONE switch (the card on the subpage). The Routes tab row is read-only and navigates (Task C14's verify greps for 0 `TailscaleAlwaysConnectCard(` / `setTailscaleAlwaysConnectEnabled` in that file). The switch is enabled whenever an active connection exists, including dormant or disconnected ones.
- "Only this app" copy never claims knowledge of other apps. "Verify my connection" reports only Hermes Relay's own connection. No `VpnService`, no `bindProcessToNetwork`, no `ACTION_VPN_SETTINGS`, no new dependency (all icons are already used in the repo), no `@Preview`, and no QR/pairing change.
- No placeholders, TODOs or `<<…>>`. The one intentional unknown is marked `UNPROVEN:` (item 8) and the unowned work is marked `PLAN:` (item 7). No personal names, hostnames or IPs in any committed text; the only addresses are the documented ranges `100.x` and `fd7a:115c:a1e0::/48`.
- Kotlin compile correctness cannot be proven on this host (no JDK). It is proven by cloud CI (`ci-required.yml`, `android_preset=auto`) after the orchestrator pushes.


---

## Slice D — gates, ADR and docs (executed from the orchestrator brief)

The slice-D planner run was lost to a provider credit error (HTTP 402) on a large single-shot generation, so
these tasks were dispatched directly to builders with a bounded brief each; the tasks and their acceptance
criteria are reproduced here verbatim from those briefs.

#### Task D1 — static transport gate
Create `scripts/check-android-hermes-transports.py` (JDK-free, `--repo-root`, exit 0/1/2): Rule A every OkHttp
client construction under `app/src/main/kotlin` must use `HermesClients.build(` / `.enforceTailnetPolicy(` or be
in an explicit exemption table (derived from the design's 26-transport inventory); Rule B fail on
`bindProcessToNetwork` / `setProcessDefaultNetwork` / `VpnService` / `setUnderlyingNetworks`; Rule C fail on the
retired `com.hermesandroid.relay.net.tailnet` package. Unit test under `scripts/tests/` with a NEGATIVE CONTROL
fixture that must fail. Verify: run it against the real worktree (must pass) and `python3 -m unittest` the test.

#### Task D2 — ADR 75
Append ADR 75 to `docs/decisions.md` in the exact house format of the last two ADRs, adapted from
`adr_draft_75.md`, recording: per-connection opt-in, client-side per-socket enforcement, address-based tailnet
inference, the platform boundary of "only this app", the four rejected alternatives with reasons
(process-wide bind, in-app VpnService, embedded tsnet, server-emitted policy field) and the consequences.
Verify: heading/line range reported; existing ADRs untouched.

#### Task D3 — documentation truth-fixes
Correct every statement about route ordering/fallback that this change makes incomplete, in `docs/spec.md`,
`README.md`, `docs/remote-access.md`, `user-docs/guide/remote-access.md` (and any other markdown found by grep):
minimal, accurate, naming the setting "Always connect via Tailscale", claiming nothing beyond the honest limit.
Verify: before/after snippets; list of docs inspected and deliberately unchanged.

#### Task D4 — test + gate registration
Register the feature's new unit-test classes in `scripts/android-prepush.py`'s hardcoded FOCUSED_TESTS register
(CI does not auto-discover tests) and wire the new static gate only via the house convention. Verify:
`python3 -m unittest scripts.tests.android_prepush_test -v` still passes and `--help` still parses.

## Slice G — gaps closed after the audits (from the orchestrator brief)
The slice-B audit found three files owned by nobody; the slice-C audit confirmed the EndpointsCard/Gateways
picker work belongs to slice C (tasks C10/C15/C17), so it was not duplicated.
- **G1** `data/ConnectionData.kt`: add `alwaysViaTailscale: Boolean = false` to `Connection` (no migration:
  the store already uses `ignoreUnknownKeys`/`encodeDefaults`).
- **G3** `ui/screens/DashboardManagementScreen.kt`: gate its WebView `loadUrl` exactly as the sign-in screen is
  gated, so a non-tailnet dashboard-management URL cannot load while the mode is ON.
- **G4** extend the existing connection-fields test with a round-trip/default case for the new flag.

## Post-review amendments (independent review pass)

Three independent reviewers (spec-compliance, code-quality, compile-risk) read the delivered diff against
ADR 75 and the frozen contracts. Verdicts and dispositions:

1. **Spec gate - one BLOCKING finding, confirmed and fixed.** With the mode already connected over a
   non-Tailscale route, switching it ON called `probeNow()` which funnelled into the resolver's
   `resolved == null && _connectionState == Connected` early-return: the pre-existing transient-miss
   optimisation kept publishing the live (LAN) endpoint, so the socket kept carrying Hermes bytes after the
   user excluded that route. Fixed in `ConnectionManager.kt`: while the mode is on, that early-return no
   longer applies in `probeAndReconnectNow()` or `refreshActiveEndpoint()` - the resolver publishes no
   endpoint, records `PolicyNoEligibleRoute`, and the live socket is torn down. The mode-off path is
   byte-for-byte unchanged. Two further spec notes were also applied: the authenticated-metadata reconnect
   now passes the same dial-time guard as every other connect path, and the resolver's diagnostics no longer
   claim "using configured relay URL" when the mode is what blanked that URL.
2. **Compile gate - one finding, REFUTED with evidence.** It reported `resources.getString(...)` in
   `DashboardManagementScreen.kt` as an unresolved reference. It resolves: the line sits inside
   `WebView(viewContext).apply { ... }`, whose receiver supplies `View.getResources()`. The cloud compile
   lane (`android_on_demand`, which checks out `inputs.head_sha` in every job - verified in the workflow)
   assembles both debug flavors green on that exact tree, which is only possible if the file compiles. The
   call is normalised to `context.getString(...)` for consistency with the rest of the dialog, not because
   it was broken.
3. **Quality gate - PASS**, six non-blocking notes; three were applied (plugin-proxy client now goes through
   `HermesClients.build` so it is `enforcer.register`-ed and its pooled connections are evicted on a
   generation bump; the native dashboard launcher records a diagnostics entry instead of returning silently;
   the `resources`/`context` consistency above).

**Consciously not done, with reasons.** (a) The static gate proves the invariant at the client-construction
seam ("every Hermes OkHttp client is decorated") rather than re-scanning `newWebSocket`/SSE/`loadUrl` call
sites: those transports run on a client already bound at construction, or are URL-gated, so the seam is the
sound place to prove it - re-scanning call sites would produce false positives without adding coverage.
(b) On-device questions (does an excluded app get a bind error or a black hole; does `Network.getAllByName`
use MagicDNS) are settled only by a device matrix, not by any diff or unit test.
