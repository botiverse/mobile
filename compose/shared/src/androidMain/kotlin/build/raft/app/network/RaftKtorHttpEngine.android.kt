package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okio.buffer

/**
 * Android app HTTP: Ktor over OkHttp (artin 2026-10-23). What replaces which curl workaround:
 *
 * - [RAFT_OKHTTP_PING_INTERVAL_SECONDS] HTTP/2 PING detects a dead connection (no PONG before the
 *   next PING fails it) and, after any stream read timeout, OkHttp's degraded-PING health check stops
 *   handing that connection to new calls. This replaces NetworkKMM's 7 s response-headers watchdog
 *   and 3 s curl keepalive ping (AndroidNetworkKmmRecovery), which the Ktor path does not use.
 * - 5 s connect per attempt ([RAFT_KTOR_CONNECT_TIMEOUT_MILLIS]) with OkHttp's fast fallback
 *   (Happy Eyeballs, IPv4/IPv6 raced) instead of curl's 10 s connect timeout.
 * - Separate read / write idle timeouts; the whole-request budget stays with Ktor's request timeout
 *   (30 s ordinary, 3 min multipart, 30 min presigned PUT).
 * - retryOnConnectionFailure only on the idempotent clients (GET/HEAD/OPTIONS): a request that hit a
 *   stale pooled connection is re-run once on a fresh one. POST/PUT/PATCH/DELETE never are.
 * - One connection pool for the process; idle connections close after
 *   [RAFT_OKHTTP_POOL_KEEP_ALIVE_SECONDS], so a backgrounded app does not keep pinging.
 * - System DNS (Android caches per network and honours TTLs); OkHttp offers no resolver cache of its
 *   own, so none is added. System proxy settings apply via the default ProxySelector.
 * - Lanes: one OkHttp Dispatcher per [RaftRequestPriority] with the [RaftRequestScheduler] limits, so
 *   Background work can never take the slots of Foreground/Visible calls.
 */
internal const val RAFT_OKHTTP_PING_INTERVAL_SECONDS = 10L
internal const val RAFT_OKHTTP_READ_TIMEOUT_SECONDS = 20L
internal const val RAFT_OKHTTP_WRITE_TIMEOUT_SECONDS = 20L
internal const val RAFT_OKHTTP_POOL_MAX_IDLE = 8
internal const val RAFT_OKHTTP_POOL_KEEP_ALIVE_SECONDS = 90L

internal fun raftOkHttpLaneLimit(priority: RaftRequestPriority): Int =
    when (priority) {
        RaftRequestPriority.Foreground -> RAFT_DEFAULT_MAX_FOREGROUND_REQUESTS
        RaftRequestPriority.Visible -> RAFT_DEFAULT_MAX_VISIBLE_REQUESTS
        RaftRequestPriority.Background -> RAFT_DEFAULT_MAX_BACKGROUND_REQUESTS
    }

/** The base client every lane derives from: shared pool, ping, timeouts, idempotent retry on. */
internal fun buildRaftBaseOkHttpClient(
    eventListenerFactory: EventListener.Factory? = RaftOkHttpTransportFacts,
): OkHttpClient =
    OkHttpClient.Builder()
        .connectionPool(ConnectionPool(RAFT_OKHTTP_POOL_MAX_IDLE, RAFT_OKHTTP_POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
        .connectTimeout(RAFT_KTOR_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(RAFT_OKHTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(RAFT_OKHTTP_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Ktor's per-request requestTimeout owns the whole-call budget.
        .callTimeout(0L, TimeUnit.MILLISECONDS)
        .pingInterval(RAFT_OKHTTP_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .fastFallback(true)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .apply { eventListenerFactory?.let(::eventListenerFactory) }
        .addNetworkInterceptor(RaftUploadProgressInterceptor)
        .build()

/** Per lane and call kind, derived from [base] (same pool) with the lane's own dispatcher. */
internal fun buildRaftLaneOkHttpClient(
    base: OkHttpClient,
    dispatcher: Dispatcher,
    kind: RaftKtorCallKind,
): OkHttpClient =
    base.newBuilder()
        .dispatcher(dispatcher)
        .apply {
            when (kind) {
                RaftKtorCallKind.Idempotent -> retryOnConnectionFailure(true)
                RaftKtorCallKind.NonIdempotent -> retryOnConnectionFailure(false)
                RaftKtorCallKind.PresignedUpload -> {
                    retryOnConnectionFailure(false)
                    // Same HTTP/1.1 pin curl had for presigned PUTs; also keeps a long upload off the
                    // API's shared HTTP/2 connection.
                    protocols(listOf(Protocol.HTTP_1_1))
                }
            }
        }
        .build()

internal fun raftLaneDispatcher(priority: RaftRequestPriority): Dispatcher =
    Dispatcher().apply {
        val limit = raftOkHttpLaneLimit(priority)
        maxRequests = limit
        // HTTP/2 multiplexes one connection per host; the per-host cap must not be OkHttp's default 5.
        maxRequestsPerHost = limit
    }

internal fun raftKtorClientFor(okHttpClient: OkHttpClient): HttpClient =
    HttpClient(OkHttp) {
        configureRaftKtorClient()
        engine {
            preconfigured = okHttpClient
            // Ktor derives a client from `preconfigured` per timeout setting and, before running this
            // block, swaps in a fresh Dispatcher and forces retryOnConnectionFailure(true) (its OkHttp
            // defaults). Put the lane's dispatcher and this client's retry policy back, or every lane
            // would share no limits and POSTs would be re-sent after a connection failure.
            val laneDispatcher = okHttpClient.dispatcher
            val retryOnConnectionFailure = okHttpClient.retryOnConnectionFailure
            config {
                dispatcher(laneDispatcher)
                retryOnConnectionFailure(retryOnConnectionFailure)
            }
        }
    }

/** The process's OkHttp lanes: one base (pool), one Dispatcher per priority, one client per call kind. */
internal class RaftOkHttpLanes(base: OkHttpClient = buildRaftBaseOkHttpClient()) {
    private val clients: Map<RaftRequestPriority, Map<RaftKtorCallKind, OkHttpClient>> =
        RaftRequestPriority.entries.associateWith { priority ->
            val dispatcher = raftLaneDispatcher(priority)
            RaftKtorCallKind.entries.associateWith { kind -> buildRaftLaneOkHttpClient(base, dispatcher, kind) }
        }

    fun okHttp(priority: RaftRequestPriority, kind: RaftKtorCallKind): OkHttpClient =
        clients.getValue(priority).getValue(kind)

    fun ktorProvider(): RaftKtorClientProvider {
        val ktor = clients.mapValues { (_, byKind) -> byKind.mapValues { (_, client) -> raftKtorClientFor(client) } }
        return RaftKtorClientProvider { priority, kind -> ktor.getValue(priority).getValue(kind) }
    }
}

private val processLanes: RaftOkHttpLanes by lazy { RaftOkHttpLanes() }

internal actual fun createPlatformRaftKtorClientProvider(): RaftKtorClientProvider = processLanes.ktorProvider()

internal actual fun raftKtorEngineLabel(): String = "ktor_okhttp"

internal actual fun raftKtorEngineReportsProtocol(): Boolean = true

internal actual fun classifyPlatformTransportFailure(error: Throwable): RaftTransportFailureKind? =
    when (error) {
        is UnknownHostException -> RaftTransportFailureKind.DNS
        is SocketTimeoutException -> RaftTransportFailureKind.TIMEOUT
        is ConnectException, is NoRouteToHostException, is PortUnreachableException -> RaftTransportFailureKind.CONNECT
        is SSLException -> RaftTransportFailureKind.TLS
        is InterruptedIOException ->
            if (error.message.orEmpty().contains("timeout", ignoreCase = true)) RaftTransportFailureKind.TIMEOUT else null
        is IOException ->
            // OkHttp reports Call.cancel() as IOException("Canceled").
            if (error.message == "Canceled") RaftTransportFailureKind.CANCELLED else null
        else -> null
    }

internal actual fun takeRaftKtorTransportFacts(requestId: String): String? = RaftOkHttpTransportFacts.take(requestId)

/**
 * Per-call connection facts for diagnostics, keyed by the request's `x-request-id`: whether a pooled
 * connection was reused, DNS / connect / TLS time, connect attempts, protocol and route. Bounded; an
 * entry nobody takes is evicted after [MAX_ENTRIES] newer calls.
 */
internal object RaftOkHttpTransportFacts : EventListener.Factory {
    private const val MAX_ENTRIES = 256
    private const val REQUEST_ID_HEADER = "x-request-id"

    private val completed =
        object : LinkedHashMap<String, String>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_ENTRIES
        }

    fun take(requestId: String): String? =
        if (requestId.isEmpty()) null else synchronized(completed) { completed.remove(requestId) }

    private fun publish(requestId: String, facts: String) {
        synchronized(completed) { completed[requestId] = facts }
    }

    override fun create(call: Call): EventListener {
        val requestId = call.request().header(REQUEST_ID_HEADER).orEmpty()
        return if (requestId.isEmpty()) EventListener.NONE else Listener(requestId)
    }

    private class Listener(private val requestId: String) : EventListener() {
        private var dnsStartNs = 0L
        private var dnsMs = 0L
        private var connectStartNs = 0L
        private var connectMs = 0L
        private var tlsStartNs = 0L
        private var tlsMs = 0L
        private var connectAttempts = 0
        private var reused = true
        private var protocol: String? = null
        private var addressFamily: String? = null
        private var tlsVersion: String? = null

        @Synchronized
        override fun dnsStart(call: Call, domainName: String) {
            dnsStartNs = System.nanoTime()
        }

        @Synchronized
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsMs += elapsedMs(dnsStartNs)
        }

        @Synchronized
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            reused = false
            connectAttempts += 1
            if (connectStartNs == 0L) connectStartNs = System.nanoTime()
            addressFamily = if (inetSocketAddress.address is java.net.Inet6Address) "ipv6" else "ipv4"
        }

        @Synchronized
        override fun secureConnectStart(call: Call) {
            tlsStartNs = System.nanoTime()
        }

        @Synchronized
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tlsMs += elapsedMs(tlsStartNs)
            tlsVersion = handshake?.tlsVersion?.javaName
        }

        @Synchronized
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectMs = elapsedMs(connectStartNs)
            addressFamily = if (inetSocketAddress.address is java.net.Inet6Address) "ipv6" else "ipv4"
        }

        @Synchronized
        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
            protocol = connection.protocol().toString()
        }

        override fun callEnd(call: Call) = publishFacts()

        override fun callFailed(call: Call, ioe: IOException) = publishFacts()

        override fun canceled(call: Call) = publishFacts()

        @Synchronized
        private fun publishFacts() {
            publish(
                requestId,
                "reusedConnection=$reused connectionAttempts=$connectAttempts dnsMs=$dnsMs " +
                    "connectTotalMs=$connectMs tlsMs=$tlsMs negotiatedProtocol=${protocol ?: "none"} " +
                    "addressFamily=${addressFamily ?: "none"} tlsVersion=${tlsVersion ?: "none"}"
            )
        }

        private fun elapsedMs(startNs: Long): Long =
            if (startNs == 0L) 0L else TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
    }
}

internal actual fun platformRaftUploadProgressTracker(): RaftUploadProgressTracker =
    RaftUploadProgressInterceptor.tracker

/**
 * Measures upload progress where bytes leave OkHttp for the connection (Hands e9baeedb: counting at
 * the body writer filled Ktor's ~1 MB channel instantly, so a photo showed 100% before it was sent).
 * A request that carries [HEADER] gets the header removed and its body wrapped in a counting sink;
 * HTTP/2 flow control and socket backpressure pace those writes to the real transfer. A retried or
 * rewritten body restarts the count from zero.
 */
internal object RaftUploadProgressInterceptor : okhttp3.Interceptor {
    const val HEADER = "X-Raft-Upload-Progress"
    private const val REPORT_STEP_BYTES = 32L * 1024L

    private val listeners = java.util.concurrent.ConcurrentHashMap<String, (RaftTransferProgress) -> Unit>()
    private val nextId = java.util.concurrent.atomic.AtomicLong(0L)

    val tracker =
        RaftUploadProgressTracker { listener ->
            if (listener == null) {
                RaftUploadProgressRoute(null, null) {}
            } else {
                val id = nextId.incrementAndGet().toString()
                listeners[id] = listener
                RaftUploadProgressRoute(null, HEADER to id) { listeners.remove(id) }
            }
        }

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val id = request.header(HEADER) ?: return chain.proceed(request)
        val stripped = request.newBuilder().removeHeader(HEADER)
        val body = request.body
        val listener = listeners[id]
        if (body == null || listener == null) return chain.proceed(stripped.build())
        return chain.proceed(stripped.method(request.method, CountingBody(body, listener)).build())
    }

    private class CountingBody(
        private val delegate: okhttp3.RequestBody,
        private val listener: (RaftTransferProgress) -> Unit,
    ) : okhttp3.RequestBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun isOneShot(): Boolean = delegate.isOneShot()

        override fun isDuplex(): Boolean = delegate.isDuplex()

        override fun writeTo(sink: okio.BufferedSink) {
            val total = contentLength().takeIf { it >= 0L }
            var written = 0L
            var reported = 0L
            val counting =
                object : okio.ForwardingSink(sink) {
                    override fun write(source: okio.Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        written += byteCount
                        if (written - reported >= REPORT_STEP_BYTES || written == total) {
                            reported = written
                            runCatching { listener(RaftTransferProgress(bytesTransferred = written, bytesTotal = total)) }
                        }
                    }
                }
            val buffered = counting.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
            if (reported != written) {
                runCatching { listener(RaftTransferProgress(bytesTransferred = written, bytesTotal = total)) }
            }
        }
    }
}
