package com.hermesandroid.relay.network.shared

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
