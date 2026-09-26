package com.hermesandroid.relay.network.shared

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
