package com.hermesandroid.relay.network.shared

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
