package com.hermesandroid.relay.network.shared

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
